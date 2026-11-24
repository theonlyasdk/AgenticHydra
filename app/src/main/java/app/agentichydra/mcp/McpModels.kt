package app.agentichydra.mcp

import org.json.JSONArray
import org.json.JSONObject

enum class McpTransportType { REMOTE_HTTP, STDIO }

enum class McpAuthType { NONE, OAUTH, BEARER }

enum class McpStatus { DISCONNECTED, CONNECTING, CONNECTED, OFFLINE, NEEDS_AUTH, ERROR }

data class McpServerConfig(
    val id: String,
    val displayName: String,
    val transportType: McpTransportType = McpTransportType.REMOTE_HTTP,
    val url: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val authType: McpAuthType = McpAuthType.NONE,
    val scopes: String = "default",
    val autoConnect: Boolean = true,
    val allowPrivateUrl: Boolean = false,
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet(),
    val allowedTools: Set<String>? = null,
    val bearerToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String = "app.agentichydra://oauth/mcp/callback",
) {
    fun permitsTool(name: String): Boolean =
        name !in disabledTools && (allowedTools == null || name in allowedTools)
}

data class McpTool(
    val serverId: String,
    val originalName: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnlyHint: Boolean,
) {
    fun namespaced(): String {
        val serverSlug = sanitize(serverId)
        val toolSlug = sanitize(originalName)
        var name = "mcp_${serverSlug}_${toolSlug}"
        if (name.length > 64) name = name.take(64)
        return name
    }

    fun toOpenAiSchema(): JSONObject {
        val props = inputSchema.optJSONObject("properties") ?: JSONObject()
        val required = inputSchema.optJSONArray("required") ?: JSONArray()
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", namespaced())
                    .put("description", description.ifBlank { originalName })
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", required)),
            )
    }

    companion object {
        fun sanitize(raw: String): String {
            val sb = StringBuilder()
            for (c in raw.lowercase()) {
                if (c in 'a'..'z' || c in '0'..'9') sb.append(c) else sb.append('_')
            }
            var s = sb.toString().trim('_')
            while (s.contains("__")) s = s.replace("__", "_")
            if (s.isBlank()) s = "x"
            if (s[0].isDigit()) s = "s$s"
            return s
        }
    }
}

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val tools: List<McpTool> = emptyList(),
    val lastError: String? = null,
    val instructions: String? = null,
    val session: McpSession? = null,
)

fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

private val ALLOWED_SCHEMA_KEYS = setOf(
    "type", "properties", "required", "items", "description", "default",
    "enum", "minimum", "maximum", "minLength", "maxLength", "pattern",
    "oneOf", "anyOf", "title", "additionalProperties",
)

fun sanitizeSchema(input: JSONObject): JSONObject {
    val out = JSONObject()
    for (key in input.keys()) {
        if (key !in ALLOWED_SCHEMA_KEYS) continue
        val v = input.get(key)
        when (v) {
            is JSONObject -> out.put(key, sanitizeSchema(v))
            is JSONArray -> {
                val arr = JSONArray()
                for (i in 0 until v.length()) {
                    val e = v.get(i)
                    if (e is JSONObject) arr.put(sanitizeSchema(e)) else arr.put(e)
                }
                out.put(key, arr)
            }
            else -> out.put(key, v)
        }
    }
    if (!out.has("type")) out.put("type", "object")
    return out
}