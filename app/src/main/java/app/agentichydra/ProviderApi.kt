package app.agentichydra

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class ModelSpec(
    val id: String,
    val name: String = id.substringAfter('/'),
    val owner: String = id.substringBefore('/', ""),
    val thinking: Boolean? = null,
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("owned_by", owner)
            .apply { thinking?.let { put("thinking", it) } }
}

class CatalogHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/** Live discovery, not a bundled list. Native Gemini metadata excludes non-text endpoints. */
class ProviderCatalog(
    private val client: OkHttpClient,
) {
    fun fetch(
        provider: Provider,
        base: String,
        credential: String,
    ): List<ModelSpec> {
        val root = base.trim().trimEnd('/').toHttpUrl()
        require(credential.isBlank() || root.isHttps) { "Use HTTPS with an API key." }
        require(root.username.isEmpty() && root.password.isEmpty() && root.query == null && root.fragment == null) {
            "Use a base URL without credentials or query parameters."
        }
        val nativeGemini = provider == Provider.GEMINI && root.host == "generativelanguage.googleapis.com"
        val result = mutableListOf<ModelSpec>()
        var page = ""
        val seenPages = mutableSetOf<String>()
        repeat(30) {
            val url =
                (
                    if (nativeGemini) {
                        root.newBuilder().encodedPath(
                            "/v1beta/models",
                        )
                    } else {
                        (base.trimEnd('/') + "/models").toHttpUrl().newBuilder()
                    }
                ).apply {
                    if (nativeGemini) {
                        addQueryParameter("pageSize", "1000")
                        if (page.isNotEmpty()) addQueryParameter("pageToken", page)
                    } else if (page.isNotEmpty()) {
                        addQueryParameter("after", page)
                    }
                }.build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply {
                        if (credential.isNotBlank()) {
                            header(
                                if (nativeGemini) "x-goog-api-key" else "Authorization",
                                if (nativeGemini) credential else "Bearer $credential",
                            )
                        }
                    }.build()
            val data =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw CatalogHttpException(
                            response.code,
                            when (response.code) {
                                401, 403 -> "Model discovery denied. Check your API key."
                                404 -> "This server has no model catalog at this URL. You can enter a model ID manually."
                                429 -> "Model catalog rate limit reached. Try again shortly."
                                else -> "Model discovery returned HTTP ${response.code}."
                            },
                        )
                    }
                    JSONObject(response.body?.string() ?: throw IOException("Empty model catalog."))
                }
            result += parse(data, nativeGemini)
            val next =
                if (nativeGemini) {
                    data.optString("nextPageToken", "")
                } else if (data.optBoolean("has_more", false)) {
                    data.optString("last_id", "").ifBlank {
                        data
                            .optJSONArray(
                                "data",
                            )?.optJSONObject((data.optJSONArray("data")?.length() ?: 0) - 1)
                            ?.optString("id", "")
                            ?: ""
                    }
                } else {
                    ""
                }
            if (next.isBlank() || !seenPages.add(next)) {
                return result.distinctBy { it.id }.sortedBy { it.name.lowercase() }
            }
            page = next
        }
        throw IOException("Model catalog exceeded 30 pages. Narrow the server catalog or enter a model ID.")
    }

    companion object {
        fun parse(
            data: JSONObject,
            nativeGemini: Boolean = false,
        ): List<ModelSpec> {
            val items =
                data.optJSONArray(if (nativeGemini) "models" else "data")
                    ?: throw IOException("Unrecognized model catalog format.")
            return (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString(if (nativeGemini) "name" else "id", "").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")
                if (id.isBlank() ||
                    (
                        nativeGemini &&
                            (0 until (methods?.length() ?: 0)).none { methods?.optString(it) == "generateContent" }
                    )
                ) {
                    return@mapNotNull null
                }
                // /models often mixes chat with embedding, reranking and media-only endpoints.
                if (Regex(
                        "(?i)(embed|rerank|whisper|transcribe|(?:^|[-/])tts(?:[-/]|$)|imagen|stable-diffusion|flux[.-]|live-|native-audio|computer-use)",
                    ).containsMatchIn(id)
                ) {
                    return@mapNotNull null
                }
                ModelSpec(
                    id,
                    item.optString(if (nativeGemini) "displayName" else "name", id.substringAfter('/')),
                    item.optString("owned_by", if (nativeGemini) "Google" else id.substringBefore('/', "")),
                    if (item.has("thinking")) item.optBoolean("thinking") else null,
                )
            }
        }
    }
}

data class ReasoningProfile(
    val levels: List<String>,
    val style: String = "effort",
) {
    val available get() = levels.size > 1
}

/** Conservative, documented model-family mappings. Unknown models keep server defaults. */
object ReasoningPolicy {
    fun label(
        profile: ReasoningProfile,
        effort: String,
    ): String =
        when {
            effort == "auto" -> "Default"
            effort == "none" -> "Off"
            profile.levels == listOf("none", "high") -> "On"
            else -> effort.replaceFirstChar { it.uppercase() }
        }

    fun profile(
        provider: Provider,
        model: ModelSpec,
    ): ReasoningProfile {
        val id = model.id.lowercase()
        if (model.thinking == false) return ReasoningProfile(emptyList())
        if (provider == Provider.GEMINI && id.startsWith("gemini-")) {
            return when {
                id.startsWith("gemini-3-pro") || id.startsWith("gemini-3.1-pro") -> ReasoningProfile(listOf("low", "high"))

                Regex(
                    "gemini-[34]",
                ).containsMatchIn(
                    id,
                ) || (
                    id.startsWith(
                        "gemini-2.5",
                    ) && !id.contains("image")
                ) -> ReasoningProfile(listOf("low", "medium", "high"))

                else -> ReasoningProfile(emptyList())
            }
        }
        if (id.contains("gpt-oss")) return ReasoningProfile(listOf("low", "medium", "high"))
        if (provider == Provider.NIM) {
            return when {
                id.contains("nemotron-3-super") -> ReasoningProfile(listOf("none", "low", "high"))

                id.contains("glm-5.3-flash") -> ReasoningProfile(listOf("low", "medium", "high"), "template_effort")

                id.contains("qwen3") -> ReasoningProfile(listOf("none", "high"), "enable_thinking")

                id.contains(
                    "deepseek",
                ) || id.contains("r1") -> ReasoningProfile(listOf("none", "high"), "enable_thinking")

                else -> ReasoningProfile(emptyList())
            }
        }
        if (provider == Provider.OLLAMA &&
            (id.contains("qwen3") || id.contains("deepseek-r1") || id.contains("deepseek") || id.contains("r1"))
        ) {
            return ReasoningProfile(listOf("none", "high"))
        }
        if (id.contains("deepseek-r1") || id.contains("deepseek-reasoner") || id.contains("r1")) {
            return ReasoningProfile(listOf("none", "high"))
        }
        if (id.contains("o1") || id.contains("o3") || id.contains("o4")) {
            return ReasoningProfile(listOf("low", "medium", "high"))
        }
        if (model.thinking == true) return ReasoningProfile(listOf("low", "medium", "high"))
        return ReasoningProfile(emptyList())
    }

    fun apply(
        payload: JSONObject,
        profile: ReasoningProfile,
        effort: String,
    ) {
        if (effort == "auto" || effort !in profile.levels) return
        when (profile.style) {
            "enable_thinking" -> {
                payload.put(
                    "chat_template_kwargs",
                    JSONObject().put(
                        "enable_thinking",
                        effort != "none",
                    ),
                )
            }

            "template_effort" -> {
                payload.put("chat_template_kwargs", JSONObject().put("reasoning_effort", effort))
            }

            else -> {
                payload.put("reasoning_effort", effort)
            }
        }
    }
}
