package app.agentichydra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

enum class ConnectionCheckPhase { Editing, Checking, Verified }

data class ConnectionCheckUiState(
    val phase: ConnectionCheckPhase = ConnectionCheckPhase.Editing,
    val message: String = "",
    val modelName: String = "",
)

/** Uses its own dispatcher so cancelling a check never cancels Chat or Work requests. */
class ConnectionValidator {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    fun cancel() = client.dispatcher.cancelAll()

    suspend fun verify(
        provider: Provider,
        base: String,
        key: String,
        preferredModel: String,
    ): ModelSpec =
        withContext(Dispatchers.IO) {
            try {
                val root = base.trim().trimEnd('/').toHttpUrl()
                require(
                    root.username.isEmpty() && root.password.isEmpty() && root.query == null && root.fragment == null,
                ) {
                    "Use a base URL without credentials or query parameters."
                }
                require(key.isBlank() || root.isHttps) { "Use HTTPS when sending an API key." }
                require(!provider.keyRequired || key.isNotBlank()) { "Enter your ${provider.label} API key." }
                // Fall back to the provider's documented default when the user hasn't
                // entered a model id, so a cold catalog endpoint can't block the test.
                val modelId = preferredModel.ifBlank { provider.defaultModel }
                val catalog =
                    try {
                        ProviderCatalog(client).fetch(provider, root.toString().trimEnd('/'), key.trim())
                    } catch (e: IOException) {
                        if (e is CatalogHttpException) {
                            when (e.statusCode) {
                                401 -> throw IOException("This API key was rejected. Check it and try again.")

                                403 -> throw IOException(
                                    "Access was denied. Check the key’s permissions and model access.",
                                )

                                429 -> throw IOException(
                                    "The provider’s rate or quota limit was reached. Try again later.",
                                )
                            }
                        }
                        // Servers without discovery can still validate a saved manual model.
                        if (modelId.isBlank()) {
                            throw IOException(
                                "Couldn’t load models. Check the connection or enter a model ID below to verify it.",
                            )
                        }
                        emptyList()
                    }
                coroutineContext.ensureActive()
                val model =
                    catalog.firstOrNull { it.id == modelId }
                        ?: modelId.takeIf { it.isNotBlank() }?.let { ModelSpec(it) }
                        ?: catalog.firstOrNull { !ReasoningPolicy.profile(provider, it).available }
                        ?: catalog.firstOrNull()
                        ?: throw IOException(
                            "No chat models were found. Enter a model ID below to check the connection.",
                        )

                // Catalogs may be public. Only a successful authenticated completion verifies access.
                val payload =
                    JSONObject()
                        .put("model", model.id)
                        .put("stream", false)
                        .put("max_tokens", 4)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")))
                val request =
                    Request
                        .Builder()
                        .url(root.toString().trimEnd('/') + "/chat/completions")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}") }
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            when (response.code) {
                                401 -> "This API key was rejected. Check it and try again."
                                403 -> "Access was denied. Check the key’s permissions and model access."
                                404 -> "The saved model or chat endpoint was not found. Choose another model and try again."
                                429 -> "The provider’s rate or quota limit was reached. Try again later."
                                400, 422 -> "The provider rejected the check for this model. Enter another model ID and try again."
                                in 300..399 -> "The endpoint redirected the request. Enter the final provider URL and try again."
                                else -> "The provider returned HTTP ${response.code}. Try again shortly."
                            },
                        )
                    }
                    val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    if ((body?.optJSONArray("choices")?.length() ?: 0) == 0 || body?.has("error") == true) {
                        throw IOException(
                            "The endpoint did not return a chat response. Check the provider URL and model.",
                        )
                    }
                }
                coroutineContext.ensureActive()
                model
            } catch (e: SocketTimeoutException) {
                throw IOException("The connection timed out. Check the server and try again.")
            }
        }
}
