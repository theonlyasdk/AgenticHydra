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

data class ResponseMetrics(
    val outputTokens: Int,
    val elapsedMillis: Long,
    val estimated: Boolean,
    val completedAt: Long,
) {
    val tokensPerSecond get() = outputTokens * 1000.0 / elapsedMillis.coerceAtLeast(1)

    fun json() =
        JSONObject()
            .put("outputTokens", outputTokens)
            .put("elapsedMillis", elapsedMillis)
            .put("estimated", estimated)
            .put("completedAt", completedAt)
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
    val attachments: List<MessageAttachment> = emptyList(),
    val finalResponse: Boolean = true,
    val thought: String = "",
) {
    fun json() =
        JSONObject()
            .put("role", role)
            .put("text", text)
            .put("id", id)
            .put("createdAt", createdAt)
            .put("modelId", modelId)
            .put("modelName", modelName)
            .put("providerName", providerName)
            .put(
                "previousModelId",
                previousModelId,
            ).put("previousModelName", previousModelName)
            .put("previousProviderName", previousProviderName)
            .apply {
                metrics?.let { put("metrics", it.json()) }
                if (attachments.isNotEmpty()) {
                    put(
                        "attachments",
                        JSONArray().apply { attachments.forEach { put(it.json()) } },
                    )
                }
                put("finalResponse", finalResponse)
                if (thought.isNotBlank()) put("thought", thought)
            }

    companion object {
        fun fromJson(data: JSONObject): Message {
            val stats = data.optJSONObject("metrics")
            val attached = data.optJSONArray("attachments") ?: JSONArray()
            return Message(
                data.getString("role"),
                data.getString("text"),
                data.optString("id").ifBlank { UUID.randomUUID().toString() },
                data.optLong("createdAt", 0),
                data.optString("modelId"),
                data.optString("modelName"),
                data.optString("providerName"),
                stats?.let {
                    ResponseMetrics(
                        it.optInt("outputTokens"),
                        it.optLong("elapsedMillis"),
                        it.optBoolean("estimated"),
                        it.optLong("completedAt"),
                    )
                },
                data.optString("previousModelId"),
                data.optString("previousModelName"),
                data.optString("previousProviderName"),
                (0 until attached.length()).map { MessageAttachment.fromJson(attached.getJSONObject(it)) },
                data.optBoolean("finalResponse", true),
                data.optString("thought", ""),
            )
        }
    }
}

data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message>,
    val titleSource: String = "prompt",
    val steps: List<WorkStep> = emptyList(),
    val apiHistory: String = "[]",
)

enum class Provider(
    val label: String,
    val base: String,
    val defaultModel: String,
    val keyRequired: Boolean = true,
) {
    NIM("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.5-flash"),
    OLLAMA("Ollama", "http://192.168.1.10:11434/v1", "", false),
    LLAMACPP("llama.cpp", "http://192.168.1.10:8080/v1", "", false),
    CUSTOM("Custom endpoint", "https://api.example.com/v1", "", false),
}

/** API credentials use Android Keystore AES-GCM; no keys are bundled in the APK. */
class KeyVault(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hydra.credentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder("hydra.credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    fun read(name: String): String =
        runCatching {
            val data = prefs.getString(name, null) ?: return ""
            val parts = data.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")

    fun write(
        name: String,
        value: String,
    ) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs
            .edit()
            .putString(
                name,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ).apply()
    }
}

class HydraModel(
    app: Application,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("hydra", Context.MODE_PRIVATE)
    private val vault = KeyVault(app)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    var provider by mutableStateOf(
        runCatching {
            Provider.valueOf(prefs.getString("provider", "NIM")!!)
        }.getOrDefault(Provider.NIM),
    )
        private set
    var endpoint by mutableStateOf(prefs.getString("endpoint", provider.base)!!)
        private set
    var model by mutableStateOf(prefs.getString("model", provider.defaultModel)!!)
        private set
    var apiKey by mutableStateOf(vault.read(provider.name))
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var activeId by mutableStateOf(UUID.randomUUID().toString())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var attachment by mutableStateOf<Pair<String, String>?>(null)
    var models by mutableStateOf<List<ModelSpec>>(emptyList())
        private set
    var discovering by mutableStateOf(false)
        private set
    var discoveryError by mutableStateOf<String?>(null)
        private set
    var catalogUpdated by mutableStateOf(0L)
        private set
    var effort by mutableStateOf("auto")
        private set
    var customModelSelected by mutableStateOf(false)
        private set
    val selectedModel get() = models.firstOrNull { it.id == model } ?: ModelSpec(model)
    val reasoning get() = ReasoningPolicy.profile(provider, selectedModel)
    private var discoveryGeneration = 0
    private var call: Call? = null
    private var job: Job? = null
    private var generation = 0
    private var responseStartedAt = -1L
    private var responseTokens: Int? = null
    var steps by mutableStateOf<List<WorkStep>>(emptyList())
        private set
    var pendingQuestions by mutableStateOf<AgentQuestionRequest?>(null)
        private set
    private var questionDecision: CompletableDeferred<String>? = null
    private val web = WorkWeb(client)

    // Raw API-side conversation (assistant tool calls + their results), so follow-ups stay well-formed.
    private var history = JSONArray()

    init {
        runCatching {
            val array = JSONArray(prefs.getString("history", "[]"))
            conversations =
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val a = o.getJSONArray("messages")
                    val savedSteps = o.optJSONArray("steps") ?: JSONArray()
                    Conversation(
                        o.getString("id"),
                        o.getString("title"),
                        (0 until a.length()).map { j ->
                            Message.fromJson(a.getJSONObject(j))
                        },
                        o.optString("titleSource", "prompt"),
                        (0 until savedSteps.length()).map {
                            WorkStep.fromJson(savedSteps.getJSONObject(it))
                        },
                        o.optString("apiHistory", "[]"),
                    )
                }
        }
        loadCatalog()
        if (provider == Provider.NIM || !provider.keyRequired || apiKey.isNotBlank()) refreshModels()
    }

    fun configure(
        p: Provider,
        url: String,
        m: String,
        key: String,
    ) {
        val oldModel = selectedModel
        val oldProvider = provider
        val changed = provider != p || model != m.trim()
        provider = p
        endpoint = url.trim().trimEnd('/')
        model = m.trim()
        apiKey = key.trim()
        vault.write(p.name, apiKey)
        prefs
            .edit()
            .putString("provider", p.name)
            .putString("endpoint", endpoint)
            .putString("model", model)
            .putString("endpoint_${p.name}", endpoint)
            .putString("model_${p.name}", model)
            .apply()
        effort = prefs.getString("effort_${p.name}_$model", "auto")!!
        loadCatalog()
        if (changed) recordModelChange(force = true, oldModel = oldModel, oldProvider = oldProvider)
        refreshModels()
    }

    fun savedKey(p: Provider) = vault.read(p.name)

    fun savedEndpoint(p: Provider) = prefs.getString("endpoint_${p.name}", if (p == provider) endpoint else p.base)!!

    fun savedModel(p: Provider) = prefs.getString("model_${p.name}", if (p == provider) model else "")!!

    fun switchProvider(p: Provider) {
        if (busy || p == provider) return
        configure(p, savedEndpoint(p), savedModel(p), savedKey(p))
    }

    fun chooseModel(
        id: String,
        manual: Boolean = false,
    ) {
        if (busy || id.isBlank()) return
        val oldModel = selectedModel
        val oldProvider = provider
        val changed = model != id.trim()
        model = id.trim()
        customModelSelected = manual
        effort = prefs.getString("effort_${provider.name}_$model", "auto")!!
        prefs
            .edit()
            .putString("model", model)
            .putString("model_${provider.name}", model)
            .apply {
                if (manual) {
                    putString(
                        "manual_model_${provider.name}",
                        model,
                    ).putString("manual_endpoint_${provider.name}", endpoint)
                } else {
                    remove("manual_model_${provider.name}").remove("manual_endpoint_${provider.name}")
                }
            }.apply()
        if (changed) recordModelChange(force = true, oldModel = oldModel, oldProvider = oldProvider)
    }

    fun chooseEffort(value: String) {
        if (value != "auto" && value !in reasoning.levels) return
        effort = value
        prefs.edit().putString("effort_${provider.name}_$model", value).apply()
    }

    private fun catalogKey(
        p: Provider = provider,
        base: String = endpoint,
        key: String = apiKey,
    ): String {
        val id =
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest((p.name + base + key).toByteArray())
                .joinToString("") {
                    "%02x".format(it)
                }
        return "catalog_$id"
    }

    /** Cached catalog of any provider without making it the active connection, for draft previews in the picker. */
    fun cachedCatalog(p: Provider): List<ModelSpec> =
        runCatching {
            ProviderCatalog.parse(
                JSONObject(prefs.getString(catalogKey(p, savedEndpoint(p), savedKey(p)), "{\"data\":[]}")!!),
            )
        }.getOrDefault(emptyList())

    fun catalogAge(p: Provider): Long =
        System.currentTimeMillis() - prefs.getLong(catalogKey(p, savedEndpoint(p), savedKey(p)) + "_time", 0L)

    fun savedEffort(
        p: Provider,
        model: String,
    ) = prefs.getString("effort_${p.name}_$model", "auto")!!

    fun manualSelection(
        p: Provider,
        model: String,
    ) = model.isNotBlank() &&
        prefs.getString("manual_model_${p.name}", "") == model &&
        prefs.getString("manual_endpoint_${p.name}", "") == savedEndpoint(p)

    private fun loadCatalog() {
        customModelSelected = model.isNotBlank() && prefs.getString("manual_model_${provider.name}", "") == model &&
            prefs.getString("manual_endpoint_${provider.name}", "") == endpoint
        models =
            runCatching {
                ProviderCatalog.parse(JSONObject(prefs.getString(catalogKey(), "{\"data\":[]}")!!))
            }.getOrDefault(emptyList())
        catalogUpdated = prefs.getLong(catalogKey() + "_time", 0L)
        discoveryError = null
        effort = prefs.getString("effort_${provider.name}_$model", "auto")!!
    }

    fun refreshModels(target: Provider = provider) {
        val current = ++discoveryGeneration
        val p = target
        val base = savedEndpoint(p)
        val key = savedKey(p)
        val cache = catalogKey(p, base, key)
        // Only the active connection updates shared discovery state; drafts of other
        // providers just warm their cache so the picker can preview them.
        val visible = p == provider
        if (p != Provider.NIM && p.keyRequired && key.isBlank()) {
            discovering = false
            if (visible) discoveryError = "Add a ${p.label} API key in Connections."
            return
        }
        discovering = true
        if (visible) discoveryError = null
        viewModelScope.launch {
            try {
                val (catalog, catalogJson) =
                    withContext(Dispatchers.IO) {
                        val data = ProviderCatalog(client).fetch(p, base, key)
                        data to
                            JSONObject().put("data", JSONArray().apply { data.forEach { put(it.json()) } }).toString()
                    }
                if (current != discoveryGeneration) return@launch
                catalogUpdated = System.currentTimeMillis()
                prefs
                    .edit()
                    .putString(cache, catalogJson)
                    .putLong(cache + "_time", catalogUpdated)
                    .apply()
                if (visible) {
                    models = catalog
                    if (catalog.isEmpty()) discoveryError = "No chat models returned. Try a manual model ID."
                }
                // Do not silently switch to a potentially more expensive model.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current == discoveryGeneration && visible) discoveryError = e.message ?: "Couldn’t refresh models."
            } finally {
                if (current == discoveryGeneration) discovering = false
            }
        }
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val saved = conversations.firstOrNull { it.id == activeId }
        val title =
            saved?.title
                ?: messages
                    .firstOrNull { it.role == "user" }
                    ?.text
                    ?.substringBefore("\n")
                    ?.take(60)
                    .orEmpty()
        conversations =
            listOf(Conversation(activeId, title, messages, saved?.titleSource ?: "prompt", steps, history.toString())) +
            conversations.filter { it.id != activeId }
        conversations = conversations.take(100)
        persistHistory()
    }

    private fun persistHistory() {
        val all = JSONArray()
        conversations.forEach { c ->
            val ms = JSONArray()
            c.messages.forEach { ms.put(it.json()) }
            all.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("titleSource", c.titleSource)
                    .put("messages", ms)
                    .put("steps", JSONArray().apply { c.steps.forEach { put(it.json()) } })
                    .put("apiHistory", c.apiHistory),
            )
        }
        prefs.edit().putString("history", all.toString()).apply()
    }

    fun renameConversation(
        id: String,
        title: String,
    ) {
        val clean = title.trim().replace(Regex("[\\r\\n]+"), " ")
        if (clean.isBlank()) return
        conversations = conversations.map { if (it.id == id) it.copy(title = clean, titleSource = "manual") else it }
        persistHistory()
    }

    /** Deletes every saved chat conversation at once. Cannot be undone. */
    fun clearAllConversations() {
        stop()
        conversations = emptyList()
        newChat()
        persistHistory()
    }

    fun deleteConversation(id: String) {
        // Cancel streaming before removal so its final checkpoint cannot restore the entry.
        if (activeId == id) newChat()
        conversations = conversations.filterNot { it.id == id }
        persistHistory()
    }

    fun newChat() {
        stop()
        messages = emptyList()
        steps = emptyList()
        history = JSONArray()
        activeId = UUID.randomUUID().toString()
        error =
            null
        attachment = null
    }

    fun open(c: Conversation) {
        stop()
        activeId = c.id
        messages = c.messages
        steps = c.steps
        history = repairedWorkHistory(c.apiHistory)
        error =
            null
        attachment = null
    }

    private fun recordModelChange(
        force: Boolean = false,
        oldModel: ModelSpec? = null,
        oldProvider: Provider? = null,
    ) {
        if (model.isBlank() || messages.isEmpty()) return
        val previous = messages.lastOrNull { it.modelId.isNotBlank() }
        if (previous?.modelId == model && previous.providerName == provider.label) return
        if (previous == null && !force) return
        messages = messages +
            Message(
                "model",
                selectedModel.name,
                modelId = model,
                modelName = selectedModel.name,
                providerName = provider.label,
                previousModelId = previous?.modelId ?: oldModel?.id.orEmpty(),
                previousModelName = previous?.modelName?.ifBlank { previous.modelId } ?: oldModel?.name.orEmpty(),
                previousProviderName = previous?.providerName ?: oldProvider?.label.orEmpty(),
            )
        persist()
    }

    private fun finishResponseMetrics() {
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant" || (last.text.isBlank() && last.thought.isBlank()) || last.metrics != null || responseStartedAt < 0L) {
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - responseStartedAt).coerceAtLeast(1)
        val tokens =
            responseTokens ?: kotlin.math
                .ceil(
                    (last.text + last.thought).toByteArray(Charsets.UTF_8).size / 4.0,
                ).toInt()
                .coerceAtLeast(1)
        messages = messages.dropLast(
            1,
        ) + last.copy(metrics = ResponseMetrics(tokens, elapsed, responseTokens == null, System.currentTimeMillis()))
    }

    fun stop() {
        generation++
        call?.cancel()
        call = null
        job?.cancel()
        job = null
        questionDecision?.cancel()
        questionDecision = null
        pendingQuestions = null
        if (messages.lastOrNull()?.let { it.text.isBlank() && it.thought.isBlank() } == true) {
            messages = messages.dropLast(1)
        }
        finishResponseMetrics()
        responseStartedAt = -1L
        responseTokens = null
        busy = false
        persist()
    }

    fun send(input: String): Boolean {
        if (busy || input.isBlank()) return false
        if (model.isBlank()) {
            error = "Choose a model from the composer first."
            return false
        }
        if (provider.keyRequired && apiKey.isBlank()) {
            error = "Add your ${provider.label} API key to start chatting."
            return false
        }
        val url = runCatching { java.net.URI(endpoint) }.getOrNull()
        if (url?.scheme !in listOf("http", "https") || url?.host.isNullOrBlank()) {
            error = "Enter a valid server URL in Connections."
            return false
        }
        if (url?.scheme == "http" && apiKey.isNotEmpty()) {
            error = "Use HTTPS when sending an API key."
            return false
        }
        val content =
            input.trim() + (attachment?.let { "\n\nAttached file: ${it.first}\n```\n${it.second}\n```" } ?: "")
        recordModelChange()
        // Seed the raw API history from visible messages once, then add this turn.
        history = repairedWorkHistory(history.toString())
        if (history.length() == 0) {
            messages
                .filter {
                    it.role == "user" || it.role == "assistant"
                }.forEach { history.put(JSONObject().put("role", it.role).put("content", it.text)) }
        }
        messages = messages + Message("user", content)
        attachment = null
        error = null
        busy = true
        persist()
        history.put(JSONObject().put("role", "user").put("content", content))
        beginTurn()
        return true
    }

    /** Launch a provider turn against the current history without appending a user prompt. */
    private fun beginTurn(): Boolean {
        error = null
        busy = true
        val currentGeneration = ++generation
        val selectedReasoning = reasoning
        val selectedEffort = effort
        val requestProvider = provider
        val requestModel = model
        val requestEndpoint = endpoint
        val requestKey = apiKey
        val requestConversationId = activeId
        responseStartedAt = SystemClock.elapsedRealtime()
        responseTokens = null
        // The placeholder shows the waiting indicator until the first streamed chunk arrives.
        val placeholder =
            Message(
                "assistant",
                "",
                modelId = requestModel,
                modelName = selectedModel.name,
                providerName = requestProvider.label,
                finalResponse = false,
            )
        messages = messages + placeholder
        job =
            viewModelScope.launch {
                var completed = false
                var droppedTools = false
                var includeTools = true
                var streamDraftId: String? = placeholder.id
                var turns = 0
                var streamText = ""
                var streamThought = ""
                var lastDraftPush = 0L
                var pendingDraftText: String? = null
                var pendingDraftThought: String? = null
                var draftFlushJob: Job? = null

                suspend fun dispatchDraft(
                    snapshot: String,
                    thoughtSnapshot: String,
                ) {
                    val existing = streamDraftId
                    if (existing == null) {
                        val draft =
                            Message(
                                "assistant",
                                snapshot,
                                modelId = requestModel,
                                modelName = selectedModel.name,
                                providerName = requestProvider.label,
                                thought = thoughtSnapshot,
                                finalResponse = false,
                            )
                        streamDraftId = draft.id
                        withContext(Dispatchers.Main) {
                            if (currentGeneration == generation) {
                                messages = messages + draft
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (currentGeneration == generation) {
                                messages =
                                    messages.map {
                                        if (it.id == existing) {
                                            it.copy(text = snapshot, thought = thoughtSnapshot, finalResponse = false)
                                        } else {
                                            it
                                        }
                                    }
                            }
                        }
                    }
                }

                suspend fun publishDraft(
                    snapshot: String,
                    thoughtSnapshot: String = "",
                ) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val isFirst = streamDraftId == null
                    if (isFirst || now - lastDraftPush >= 32L) {
                        draftFlushJob?.cancel()
                        draftFlushJob = null
                        lastDraftPush = now
                        dispatchDraft(snapshot, thoughtSnapshot)
                    } else {
                        pendingDraftText = snapshot
                        pendingDraftThought = thoughtSnapshot
                        if (draftFlushJob?.isActive != true) {
                            draftFlushJob =
                                viewModelScope.launch {
                                    delay(32L)
                                    val pText = pendingDraftText ?: snapshot
                                    val pThought = pendingDraftThought ?: thoughtSnapshot
                                    lastDraftPush = android.os.SystemClock.elapsedRealtime()
                                    dispatchDraft(pText, pThought)
                                }
                        }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            // Rebuild the wire every round so prior tool calls and results are replayed.
                            val wire =
                                JSONArray().put(
                                    JSONObject().put("role", "system").put("content", chatSystemPrompt()),
                                )
                            for (i in 0 until history.length()) wire.put(history.getJSONObject(i))
                            val payload =
                                JSONObject()
                                    .put("model", requestModel)
                                    .put("messages", wire)
                                    .put("tools", ChatTools.schema())
                                    .put("tool_choice", "auto")
                                    .put("stream", true)
                            if (requestProvider != Provider.CUSTOM) {
                                payload.put("stream_options", JSONObject().put("include_usage", true))
                            }
                            ReasoningPolicy.apply(payload, selectedReasoning, selectedEffort)
                            val turnPayload =
                                if (includeTools) {
                                    payload
                                } else {
                                    JSONObject(payload.toString()).apply {
                                        remove("tools")
                                        remove("tool_choice")
                                    }
                                }
                            val turn =
                                WorkStreamTurn(
                                    client,
                                    "$requestEndpoint/chat/completions",
                                    requestKey,
                                    turnPayload,
                                    requestProvider,
                                    onCall = { active -> call = active },
                                    retryWithoutTools = true,
                                    onThought = { thoughtChunk ->
                                        streamThought = thoughtChunk
                                        publishDraft(streamText, thoughtChunk)
                                    },
                                ) { snapshot ->
                                    streamText = snapshot
                                    publishDraft(snapshot, streamThought)
                                }
                            val response = turn.run()
                            if (turn.droppedTools) {
                                droppedTools = true
                                includeTools = false
                            }
                            currentCoroutineContext().ensureActive()
                            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                            history.put(message)
                            val rawText = if (message.isNull("content")) "" else message.optString("content", "")
                            val rawThought =
                                message
                                    .optString(
                                        "reasoning_content",
                                    ).ifEmpty {
                                        message
                                            .optString(
                                                "reasoning",
                                            ).ifEmpty { message.optString("thought") }
                                    }
                            val (text, finalThought) = extractThought(rawText, rawThought.ifEmpty { streamThought })
                            val usageTokens =
                                response.optJSONObject("usage")?.optInt("completion_tokens", -1) ?: -1
                            if (usageTokens > 0) {
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        responseTokens = usageTokens
                                    }
                                }
                            }
                            draftFlushJob?.cancel()
                            draftFlushJob = null
                            val calls = message.optJSONArray("tool_calls")
                            val hasToolCalls = calls != null && calls.length() > 0
                            val finishedDraft = streamDraftId
                            if (finishedDraft != null) {
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        messages =
                                            messages.map {
                                                if (it.id == finishedDraft) {
                                                    it.copy(
                                                        text = text,
                                                        thought = finalThought,
                                                        finalResponse = !hasToolCalls,
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                    }
                                }
                            } else if (text.isNotBlank() || finalThought.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        messages = messages +
                                            Message(
                                                "assistant",
                                                text,
                                                modelId = requestModel,
                                                modelName = selectedModel.name,
                                                providerName = requestProvider.label,
                                                finalResponse = !hasToolCalls,
                                                thought = finalThought,
                                            )
                                    }
                                }
                            }
                            if (!hasToolCalls) {
                                check(
                                    text.isNotBlank() || finalThought.isNotBlank(),
                                ) { "The model returned no text. Try another model or prompt." }
                                completed = true
                                return@withContext
                            }
                            for (i in 0 until calls.length()) {
                                currentCoroutineContext().ensureActive()
                                val tool = calls.getJSONObject(i)
                                val fn = tool.getJSONObject("function")
                                val name = fn.getString("name")
                                val argsText = fn.get("arguments").toString()
                                val step = WorkStep(name, argsText)
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        steps = steps + step
                                        messages =
                                            messages + Message("tool", name, id = step.id, createdAt = step.createdAt)
                                        persist()
                                    }
                                }
                                val result =
                                    try {
                                        executeChatTool(name, JSONObject(argsText))
                                    } catch (
                                        e: CancellationException,
                                    ) {
                                        throw e
                                    } catch (
                                        e: Exception,
                                    ) {
                                        WorkToolResult("Tool error: ${e.message ?: "Action failed."}", failed = true)
                                    }
                                currentCoroutineContext().ensureActive()
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        steps =
                                            steps.map {
                                                if (it.id == step.id) {
                                                    it.copy(
                                                        state = if (result.failed) "Failed" else "Done",
                                                        output = result.output.take(16000),
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                        history.put(
                                            JSONObject()
                                                .put(
                                                    "role",
                                                    "tool",
                                                ).put("tool_call_id", tool.getString("id"))
                                                .put("content", result.output.take(16000)),
                                        )
                                        persist()
                                    }
                                }
                            }
                            // A tool-only turn leaves the waiting placeholder blank; remove it so the tool rows stand alone.
                            streamDraftId = null
                            if (text.isBlank() && finalThought.isBlank()) {
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        messages =
                                            messages.filterNot {
                                                it.id == finishedDraft && it.text.isBlank() &&
                                                    it.thought.isBlank()
                                            }
                                    }
                                }
                            }
                            turns++
                            if (turns >= 8) {
                                withContext(Dispatchers.Main) {
                                    if (currentGeneration == generation) {
                                        messages =
                                            messages +
                                            Message(
                                                "notice",
                                                "Paused after 8 tool rounds. Send a follow-up to continue.",
                                            )
                                    }
                                }
                                completed = true
                                return@withContext
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Stop preserves any partial response.
                } catch (e: Exception) {
                    if (currentGeneration == generation) {
                        error =
                            (if (e is IOException) e.message else null)
                                ?: "Couldn’t read the provider response. Check your connection and try again."
                    }
                } finally {
                    draftFlushJob?.cancel()
                    draftFlushJob = null
                    if (currentGeneration == generation) {
                        if (messages.lastOrNull()?.let { it.text.isBlank() && it.thought.isBlank() } == true) {
                            messages = messages.dropLast(1)
                        }
                        finishResponseMetrics()
                        responseStartedAt = -1L
                        responseTokens = null
                        if (droppedTools) {
                            messages =
                                messages +
                                Message(
                                    "notice",
                                    "This model doesn't support tools, so Hydra answered without web tools.",
                                )
                        }
                        busy = false
                        call = null
                        pendingQuestions = null
                        questionDecision = null
                        persist()
                        if (completed && messages.count { it.role == "assistant" } == 1) {
                            nameConversation(
                                requestConversationId,
                                requestEndpoint,
                                requestKey,
                                requestModel,
                                selectedReasoning,
                            )
                        }
                    }
                }
            }
        return true
    }

    /** Cut raw history back at the newest user prompt, dropping replies after it. */
    private fun historyToLastPrompt(): String {
        var last = -1
        for (i in 0 until history.length())if (history.getJSONObject(i).optString("role") == "user") last = i
        if (last < 0) return ""
        val out = JSONArray()
        for (i in 0..last)out.put(history.getJSONObject(i))
        return out.toString()
    }

    /** Replay this assistant answer, resending the same user prompt. */
    fun regenerate(messageId: String): Boolean {
        if (busy) return false
        val index = messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) return false
        messages = messages.take(index)
        val trimmed = historyToLastPrompt()
        if (trimmed.isNotEmpty()) history = JSONArray(trimmed)
        return beginTurn()
    }

    /** Drop this assistant reply and everything after it, restoring the user prompt. */
    fun revert(messageId: String): Boolean {
        if (busy) return false
        val index = messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) return false
        messages = messages.take(index)
        val trimmed = historyToLastPrompt()
        if (trimmed.isNotEmpty()) history = JSONArray(trimmed)
        persist()
        return true
    }

    /** Chat tools are read-only: research, the clock, and question wizards — never state changes. */
    private suspend fun executeChatTool(
        name: String,
        args: JSONObject,
    ): WorkToolResult =
        when (name) {
            "get_time" -> {
                WorkToolResult(ScheduleTools.currentTime())
            }

            "web_search" -> {
                withContext(
                    Dispatchers.IO,
                ) { WorkToolResult(web.search(args.getString("query"), SearchOptions.fromJson(args))) }
            }

            "web_search_batch" -> {
                withContext(Dispatchers.IO) {
                    val queries = args.getJSONArray("queries")
                    WorkToolResult(
                        web.searchBatch(
                            (0 until queries.length()).map { queries.getString(it) },
                            SearchOptions.fromJson(args),
                        ),
                    )
                }
            }

            "find_in_page" -> {
                withContext(
                    Dispatchers.IO,
                ) {
                    WorkToolResult(
                        web.find(args.getString("url"), args.getString("query"), args.optInt("max_matches", 6)),
                    )
                }
            }

            "fetch_url" -> {
                withContext(
                    Dispatchers.IO,
                ) {
                    WorkToolResult(
                        web.fetch(args.getString("url"), args.optInt("max_chars", 10000), args.optInt("offset", 0)),
                    )
                }
            }

            "ask_user" -> {
                val request = QuestionTool.parse(args)
                val answer = CompletableDeferred<String>()
                questionDecision = answer
                pendingQuestions = request
                try {
                    WorkToolResult(answer.await())
                } finally {
                    if (pendingQuestions?.id == request.id) pendingQuestions = null
                    questionDecision = null
                }
            }

            else -> {
                WorkToolResult("Tool error: $name is not available in chat mode.", failed = true)
            }
        }

    private fun chatSystemPrompt() =
        "You are Hydra, a helpful, precise assistant running inside the Hydra app on the user's Android phone or tablet. This is chat mode: you cannot edit files or execute commands, so describe steps instead of claiming to run them, and never assume a desktop OS such as Windows or macOS. You have read-only tools for current information: web_search, web_search_batch, fetch_url, find_in_page, and get_time; cite retrieved source URLs and be honest when search fails or returns nothing. Use ask_user only when choices or missing information need the user's answer."

    fun answerQuestions(
        id: String,
        answers: String?,
    ) {
        val request = pendingQuestions?.takeIf { it.id == id } ?: return
        val result =
            if (answers == null) {
                JSONObject()
                    .put("status", "cancelled")
                    .put(
                        "instruction",
                        "The user dismissed these questions. Do not repeat them without new instructions.",
                    ).toString()
            } else {
                val response = JSONObject(answers)
                require(
                    response.getString("status") == "answered" &&
                        response.getJSONArray("answers").length() == request.questions.size,
                ) { "Invalid question response." }
                val values = response.getJSONArray("answers")
                require(
                    answers.length <= 15000 &&
                        (0 until values.length()).all { index ->
                            val value = values.getJSONObject(index)
                            val question = request.questions[index]
                            value.getString("id") == question.id && QuestionTool.valid(question, value)
                        },
                ) { "Question answers did not match the request." }
                answers
            }
        questionDecision?.complete(result)
        pendingQuestions = null
    }

    private fun nameConversation(
        id: String,
        url: String,
        key: String,
        model: String,
        reasoning: ReasoningProfile,
    ) {
        val conversation = conversations.firstOrNull { it.id == id && it.titleSource == "prompt" } ?: return
        val prompt = conversation.messages.firstOrNull { it.role == "user" }?.text ?: return
        val answer =
            conversation.messages
                .firstOrNull { it.role == "assistant" }
                ?.text
                ?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            try {
                val title = ConversationNamer(client).generate(url, key, model, prompt, answer, reasoning)
                // A model that cannot name the chat may answer `null` (or nothing): fall back to the first prompt.
                if (title.isBlank() || title.equals("null", ignoreCase = true)) return@launch
                // Re-read after the network call: a rename, deletion, or another chat may have happened.
                conversations =
                    conversations.map {
                        if (it.id == id && it.titleSource == "prompt") {
                            it.copy(
                                title = title,
                                titleSource = "ai",
                            )
                        } else {
                            it
                        }
                    }
                persistHistory()
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                // Keep the prompt title if the provider cannot name this chat.
            }
        }
    }
}
