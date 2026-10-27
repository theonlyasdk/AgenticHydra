package app.agentichydra

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ResponseMetrics(val outputTokens: Int, val elapsedMillis: Long, val estimated: Boolean, val completedAt: Long) {
    val tokensPerSecond get() = outputTokens * 1000.0 / elapsedMillis.coerceAtLeast(1)
    fun json() = JSONObject().put("outputTokens",outputTokens).put("elapsedMillis",elapsedMillis)
        .put("estimated",estimated).put("completedAt",completedAt)
}
data class Message(
    val role: String,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val modelId: String = "",
    val modelName: String = "",
    val providerName: String = "",
    val metrics: ResponseMetrics? = null,
    val previousModelId: String = "",
    val previousModelName: String = "",
    val previousProviderName: String = "",
    val attachments:List<MessageAttachment> = emptyList(),
) {
    fun json() = JSONObject().put("role",role).put("text",text).put("id",id).put("createdAt",createdAt)
        .put("modelId",modelId).put("modelName",modelName).put("providerName",providerName)
        .put("previousModelId",previousModelId).put("previousModelName",previousModelName).put("previousProviderName",previousProviderName)
        .apply {
            metrics?.let { put("metrics",it.json()) }
            if(attachments.isNotEmpty())put("attachments",JSONArray().apply{attachments.forEach{put(it.json())}})
        }
    companion object {
        fun fromJson(data: JSONObject): Message {
            val stats=data.optJSONObject("metrics")
            val attached=data.optJSONArray("attachments")?:JSONArray()
            return Message(data.getString("role"),data.getString("text"),
                data.optString("id").ifBlank { UUID.randomUUID().toString() },
                data.optLong("createdAt",0),data.optString("modelId"),data.optString("modelName"),data.optString("providerName"),
                stats?.let { ResponseMetrics(it.optInt("outputTokens"),it.optLong("elapsedMillis"),it.optBoolean("estimated"),it.optLong("completedAt")) },
                data.optString("previousModelId"),data.optString("previousModelName"),data.optString("previousProviderName"),
                (0 until attached.length()).map{MessageAttachment.fromJson(attached.getJSONObject(it))})
        }
    }
}
data class Conversation(val id: String, val title: String, val messages: List<Message>,val titleSource:String="prompt")
enum class Provider(val label: String, val base: String, val defaultModel: String, val keyRequired: Boolean = true) {
    NIM("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.5-flash"),
    OLLAMA("Ollama", "http://192.168.1.10:11434/v1", "", false),
    LLAMACPP("llama.cpp", "http://192.168.1.10:8080/v1", "", false),
    CUSTOM("Custom endpoint", "https://api.example.com/v1", "", false)
}

/** API credentials use Android Keystore AES-GCM; no keys are bundled in the APK. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hydra.credentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hydra.credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(name: String): String = runCatching {
        val data = prefs.getString(name, null) ?: return ""
        val parts = data.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    fun write(name: String, value: String) {
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
}

class HydraModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("hydra", Context.MODE_PRIVATE)
    private val vault = KeyVault(app)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    var provider by mutableStateOf(runCatching { Provider.valueOf(prefs.getString("provider", "NIM")!!) }.getOrDefault(Provider.NIM)); private set
    var endpoint by mutableStateOf(prefs.getString("endpoint", provider.base)!!); private set
    var model by mutableStateOf(prefs.getString("model", provider.defaultModel)!!); private set
    var apiKey by mutableStateOf(vault.read(provider.name)); private set
    var messages by mutableStateOf<List<Message>>(emptyList()); private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList()); private set
    var activeId by mutableStateOf(UUID.randomUUID().toString()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var attachment by mutableStateOf<Pair<String, String>?>(null)
    var models by mutableStateOf<List<ModelSpec>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    var discoveryError by mutableStateOf<String?>(null); private set
    var catalogUpdated by mutableStateOf(0L); private set
    var effort by mutableStateOf("auto"); private set
    var customModelSelected by mutableStateOf(false); private set
    val selectedModel get() = models.firstOrNull { it.id == model } ?: ModelSpec(model)
    val reasoning get() = ReasoningPolicy.profile(provider, selectedModel)
    private var discoveryGeneration = 0
    private var call: Call? = null
    private var job: Job? = null
    private var generation = 0
    private var responseStartedAt = -1L
    private var responseTokens: Int? = null
    init {
        runCatching {
            val array = JSONArray(prefs.getString("history", "[]"))
            conversations = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i); val a = o.getJSONArray("messages")
                Conversation(o.getString("id"), o.getString("title"), (0 until a.length()).map { j ->
                    Message.fromJson(a.getJSONObject(j))
                },o.optString("titleSource","prompt"))
            }
        }
        loadCatalog()
        if (provider == Provider.NIM || !provider.keyRequired || apiKey.isNotBlank()) refreshModels()
    }
    fun configure(p: Provider, url: String, m: String, key: String) {
        val oldModel=selectedModel;val oldProvider=provider
        val changed = provider != p || model != m.trim()
        provider = p; endpoint = url.trim().trimEnd('/'); model = m.trim(); apiKey = key.trim()
        vault.write(p.name, apiKey)
        prefs.edit().putString("provider", p.name).putString("endpoint", endpoint).putString("model", model)
            .putString("endpoint_${p.name}", endpoint).putString("model_${p.name}", model).apply()
        effort = prefs.getString("effort_${p.name}_$model", "auto")!!
        loadCatalog()
        if(changed)recordModelChange(force=true,oldModel=oldModel,oldProvider=oldProvider)
        refreshModels()
    }
    fun savedKey(p: Provider) = vault.read(p.name)
    fun savedEndpoint(p: Provider) = prefs.getString("endpoint_${p.name}", if (p == provider) endpoint else p.base)!!
    fun savedModel(p: Provider) = prefs.getString("model_${p.name}", if (p == provider) model else "")!!
    fun switchProvider(p: Provider) {
        if (busy || p == provider) return
        configure(p, savedEndpoint(p), savedModel(p), savedKey(p))
    }
    fun chooseModel(id: String, manual: Boolean = false) {
        if (busy || id.isBlank()) return
        val oldModel=selectedModel;val oldProvider=provider
        val changed = model != id.trim()
        model = id.trim()
        customModelSelected=manual
        effort = prefs.getString("effort_${provider.name}_$model", "auto")!!
        prefs.edit().putString("model", model).putString("model_${provider.name}", model).apply {
            if(manual)putString("manual_model_${provider.name}",model).putString("manual_endpoint_${provider.name}",endpoint)
            else remove("manual_model_${provider.name}").remove("manual_endpoint_${provider.name}")
        }.apply()
        if(changed)recordModelChange(force=true,oldModel=oldModel,oldProvider=oldProvider)
    }
    fun chooseEffort(value: String) {
        if (value != "auto" && value !in reasoning.levels) return
        effort = value
        prefs.edit().putString("effort_${provider.name}_$model", value).apply()
    }
    private fun catalogKey(): String {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest((provider.name + endpoint + apiKey).toByteArray()).joinToString("") { "%02x".format(it) }
        return "catalog_$id"
    }
    private fun loadCatalog() {
        customModelSelected=model.isNotBlank()&&prefs.getString("manual_model_${provider.name}","")==model&&
            prefs.getString("manual_endpoint_${provider.name}","")==endpoint
        models = runCatching { ProviderCatalog.parse(JSONObject(prefs.getString(catalogKey(), "{\"data\":[]}")!!)) }.getOrDefault(emptyList())
        catalogUpdated = prefs.getLong(catalogKey() + "_time", 0L)
        discoveryError = null
        effort = prefs.getString("effort_${provider.name}_$model", "auto")!!
    }
    fun refreshModels() {
        val current = ++discoveryGeneration
        val p = provider; val base = endpoint; val key = apiKey; val cache = catalogKey()
        if (p != Provider.NIM && p.keyRequired && key.isBlank()) {
            discovering = false; discoveryError = "Add a ${p.label} API key in Connections."; return
        }
        discovering = true; discoveryError = null
        viewModelScope.launch {
            try {
                val (catalog,catalogJson) = withContext(Dispatchers.IO) {
                    val data=ProviderCatalog(client).fetch(p,base,key)
                    data to JSONObject().put("data",JSONArray().apply{data.forEach{put(it.json())}}).toString()
                }
                if (current != discoveryGeneration) return@launch
                models = catalog; catalogUpdated = System.currentTimeMillis()
                prefs.edit().putString(cache,catalogJson).putLong(cache + "_time", catalogUpdated).apply()
                if (catalog.isEmpty()) discoveryError = "No chat models returned. Try a manual model ID."
                // Do not silently switch to a potentially more expensive model.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current == discoveryGeneration) discoveryError = e.message ?: "Couldn’t refresh models."
            } finally { if (current == discoveryGeneration) discovering = false }
        }
    }
    private fun persist() {
        if (messages.isEmpty()) return
        val saved = conversations.firstOrNull { it.id == activeId }
        val title = saved?.title
            ?: messages.firstOrNull { it.role=="user" }?.text?.substringBefore("\n")?.take(60).orEmpty()
        conversations = listOf(Conversation(activeId, title, messages,saved?.titleSource?:"prompt")) + conversations.filter { it.id != activeId }
        conversations = conversations.take(100)
        persistHistory()
    }
    private fun persistHistory() {
        val all = JSONArray()
        conversations.forEach { c ->
            val ms = JSONArray(); c.messages.forEach { ms.put(it.json()) }
            all.put(JSONObject().put("id", c.id).put("title", c.title).put("titleSource",c.titleSource).put("messages", ms))
        }
        prefs.edit().putString("history", all.toString()).apply()
    }
    fun renameConversation(id: String, title: String) {
        val clean = title.trim().replace(Regex("[\\r\\n]+"), " ")
        if (clean.isBlank()) return
        conversations = conversations.map { if (it.id == id) it.copy(title = clean,titleSource="manual") else it }
        persistHistory()
    }
    fun deleteConversation(id: String) {
        // Cancel streaming before removal so its final checkpoint cannot restore the entry.
        if (activeId == id) newChat()
        conversations = conversations.filterNot { it.id == id }
        persistHistory()
    }
    fun newChat() { stop(); messages = emptyList(); activeId = UUID.randomUUID().toString(); error = null; attachment = null }
    fun open(c: Conversation) { stop(); activeId = c.id; messages = c.messages; error = null; attachment = null }
    private fun recordModelChange(force: Boolean = false, oldModel: ModelSpec? = null, oldProvider: Provider? = null) {
        if(model.isBlank()||messages.isEmpty())return
        val previous=messages.lastOrNull { it.modelId.isNotBlank() }
        if(previous?.modelId==model&&previous.providerName==provider.label)return
        if(previous==null&&!force)return
        messages=messages+Message("model",selectedModel.name,modelId=model,modelName=selectedModel.name,providerName=provider.label,
            previousModelId=previous?.modelId?:oldModel?.id.orEmpty(),
            previousModelName=previous?.modelName?.ifBlank { previous.modelId }?:oldModel?.name.orEmpty(),
            previousProviderName=previous?.providerName?:oldProvider?.label.orEmpty())
        persist()
    }
    private fun finishResponseMetrics() {
        val last=messages.lastOrNull()?:return
        if(last.role!="assistant"||last.text.isBlank()||last.metrics!=null||responseStartedAt<0L)return
        val elapsed=(SystemClock.elapsedRealtime()-responseStartedAt).coerceAtLeast(1)
        val tokens=responseTokens?:kotlin.math.ceil(last.text.toByteArray(Charsets.UTF_8).size/4.0).toInt().coerceAtLeast(1)
        messages=messages.dropLast(1)+last.copy(metrics=ResponseMetrics(tokens,elapsed,responseTokens==null,System.currentTimeMillis()))
    }
    fun stop() {
        generation++
        call?.cancel(); call = null; job?.cancel(); job = null
        if (messages.lastOrNull()?.text?.isBlank() == true) messages = messages.dropLast(1)
        finishResponseMetrics();responseStartedAt=-1L;responseTokens=null
        busy = false; persist()
    }
    fun send(input: String): Boolean {
        if (busy || input.isBlank()) return false
        if (model.isBlank()) { error = "Choose a model from the composer first."; return false }
        if (provider.keyRequired && apiKey.isBlank()) { error = "Add your ${provider.label} API key to start chatting."; return false }
        val url = runCatching { java.net.URI(endpoint) }.getOrNull()
        if (url?.scheme !in listOf("http", "https") || url?.host.isNullOrBlank()) { error = "Enter a valid server URL in Connections."; return false }
        if (url?.scheme == "http" && apiKey.isNotEmpty()) { error = "Use HTTPS when sending an API key."; return false }
        val content = input.trim() + (attachment?.let { "\n\nAttached file: ${it.first}\n```\n${it.second}\n```" } ?: "")
        recordModelChange()
        messages = messages + Message("user", content); attachment = null; error = null; busy = true; persist()
        val previous = messages.filter { it.role=="user"||it.role=="assistant" }
        val currentGeneration = ++generation
        val selectedReasoning = reasoning; val selectedEffort = effort
        val requestProvider=provider;val requestModel=model;val requestEndpoint=endpoint;val requestKey=apiKey
        val requestConversationId=activeId
        responseStartedAt=SystemClock.elapsedRealtime();responseTokens=null
        messages = messages + Message("assistant", "",modelId=model,modelName=selectedModel.name,providerName=provider.label)
        job = viewModelScope.launch {
            var completed=false
            try {
                withContext(Dispatchers.IO) {
                    val ms = JSONArray().put(JSONObject().put("role", "system").put("content", "You are Hydra, a helpful, precise assistant. This is chat mode. Do not claim to execute commands or edit files. Offer clear practical answers."))
                    previous.forEach { ms.put(JSONObject().put("role", it.role).put("content", it.text)) }
                    val payload = JSONObject().put("model", requestModel).put("messages", ms).put("stream", true)
                    if(requestProvider!=Provider.CUSTOM)payload.put("stream_options",JSONObject().put("include_usage",true))
                    ReasoningPolicy.apply(payload, selectedReasoning, selectedEffort)
                    val request = Request.Builder().url("$requestEndpoint/chat/completions")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .header("Accept", "text/event-stream")
                        .apply { if (requestKey.isNotBlank()) header("Authorization", "Bearer $requestKey") }.build()
                    var currentCall = client.newCall(request); call = currentCall
                    var streamedResponse=currentCall.execute()
                    if(streamedResponse.code in listOf(400,422)&&payload.has("stream_options")) {
                        val detail=streamedResponse.peekBody(16384).string()
                        if(detail.contains("stream_options",true)||detail.contains("include_usage",true)) {
                            streamedResponse.close();currentCoroutineContext().ensureActive()
                            payload.remove("stream_options")
                            currentCall=client.newCall(request.newBuilder().post(payload.toString().toRequestBody("application/json".toMediaType())).build())
                            call=currentCall;streamedResponse=currentCall.execute()
                        }
                    }
                    streamedResponse.use { response ->
                        if (!response.isSuccessful) throw IOException(when(response.code) {
                            400, 422 -> "The model rejected this request. Try Default reasoning or choose a compatible model."
                            401, 403 -> "Access denied. Check your API key and model permissions."
                            404 -> "Model or endpoint not found. Check the connection settings."
                            429 -> "Provider limit reached. Wait a moment and try again."
                            else -> "Provider returned HTTP ${response.code}. Please try again."
                        })
                        val source = response.body?.source() ?: throw IOException("Provider returned an empty response.")
                        var output = ""
                        while (!source.exhausted()) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue
                            val event = JSONObject(data)
                            if (event.has("error")) throw IOException("The provider reported a streaming error. Check the model and try again.")
                            val usedTokens=event.optJSONObject("usage")?.optInt("completion_tokens",-1)?:-1
                            if(usedTokens>0)withContext(Dispatchers.Main){if(currentGeneration==generation)responseTokens=usedTokens}
                            val delta = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                            val chunk = if (delta?.isNull("content") == false) delta.optString("content", "") else ""
                            if (chunk.isNotEmpty()) {
                                output += chunk
                                val snapshot = output
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) messages = messages.dropLast(1) + messages.last().copy(text=snapshot)
                                }
                            }
                        }
                        if (output.isBlank()) throw IOException("The model returned no text. Try another model or prompt.")
                    }
                }
                completed=true
            } catch (_: CancellationException) {
                // Stop preserves any partial response.
            } catch (e: Exception) {
                if (currentGeneration == generation) error = (if (e is IOException) e.message else null) ?: "Couldn’t read the provider response. Check your connection and try again."
            } finally {
                if (currentGeneration == generation) {
                    if (messages.lastOrNull()?.text?.isBlank() == true) messages = messages.dropLast(1)
                    finishResponseMetrics();responseStartedAt=-1L;responseTokens=null
                    busy = false; call = null; persist()
                    if(completed&&messages.count{it.role=="assistant"}==1) {
                        nameConversation(requestConversationId,requestEndpoint,requestKey,requestModel,selectedReasoning)
                    }
                }
            }
        }
        return true
    }

    private fun nameConversation(id:String,url:String,key:String,model:String,reasoning:ReasoningProfile) {
        val conversation=conversations.firstOrNull{it.id==id&&it.titleSource=="prompt"}?:return
        val prompt=conversation.messages.firstOrNull{it.role=="user"}?.text?:return
        val answer=conversation.messages.firstOrNull{it.role=="assistant"}?.text?.takeIf{it.isNotBlank()}?:return
        viewModelScope.launch {
            try {
                val title=ConversationNamer(client).generate(url,key,model,prompt,answer,reasoning)
                if(title.isBlank())return@launch
                // Re-read after the network call: a rename, deletion, or another chat may have happened.
                conversations=conversations.map{if(it.id==id&&it.titleSource=="prompt")it.copy(title=title,titleSource="ai")else it}
                persistHistory()
            } catch(e:CancellationException){throw e}
            catch(_:Exception){ /* Keep the prompt title if the provider cannot name this chat. */ }
        }
    }
}
