package app.agentichydra.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class McpSession(
    val config: McpServerConfig,
    private val transport: McpTransport,
    private val onProgress: (token: String?, message: String) -> Unit = { _, _ -> },
    private val onElicitation: suspend (JSONObject) -> JSONObject? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var protocolVersion: String? = null
        private set
    var serverInstructions: String? = null
        private set
    var serverCapabilities: JSONObject? = null
        private set

    suspend fun connect(): List<McpTool> {
        transport.connect()
        scope.launch {
            transport.messages.collect { handleServerMessage(it) }
        }
        // initialize
        val params = JSONObject().apply {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
                put("elicitation", JSONObject())
            })
            put("clientInfo", JSONObject().apply {
                put("name", "Hydra")
                put("version", "1.3.0")
            })
        }
        val initReq = JsonRpc.request("initialize", params)
        val initResp = transport.sendRequest(initReq)
        if (JsonRpc.isError(initResp)) throw RuntimeException(JsonRpc.errorMessage(initResp))
        val result = initResp.getJSONObject("result")
        protocolVersion = result.optString("protocolVersion")
        serverCapabilities = result.optJSONObject("capabilities")
        serverInstructions = result.optStringOrNull("instructions")
        transport.sendNotification(JsonRpc.notification("notifications/initialized"))
        return listTools()
    }

    suspend fun listTools(): List<McpTool> {
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject()
            if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
            val req = JsonRpc.request("tools/list", params)
            val resp = transport.sendRequest(req)
            if (JsonRpc.isError(resp)) throw RuntimeException(JsonRpc.errorMessage(resp))
            val result = resp.getJSONObject("result")
            val toolsArr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.getJSONObject(i)
                val schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
                val annotations = t.optJSONObject("annotations")
                val readOnly = annotations?.optBoolean("readOnlyHint", false) ?: false
                out.add(McpTool(config.id, t.getString("name"), t.optString("description"), sanitizeSchema(schema), readOnly))
            }
            cursor = result.optStringOrNull("nextCursor")
        } while (!cursor.isNullOrBlank())
        return out
    }

    suspend fun callTool(name: String, args: JSONObject, progressToken: String? = null): JSONObject {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", args)
            if (progressToken != null) {
                put("_meta", JSONObject().put("progressToken", progressToken))
            }
        }
        val req = JsonRpc.request("tools/call", params)
        val resp = transport.sendRequest(req)
        if (JsonRpc.isError(resp)) throw RuntimeException(JsonRpc.errorMessage(resp))
        return resp.getJSONObject("result")
    }

    private suspend fun handleServerMessage(msg: JSONObject) {
        val method = msg.optString("method")
        val id = if (msg.has("id") && !msg.isNull("id")) msg.get("id") else null
        when (method) {
            "ping" -> {
                if (id != null) transport.sendResponse(JsonRpc.response(id, JSONObject()))
            }
            "roots/list" -> {
                if (id != null) transport.sendResponse(JsonRpc.response(id, JSONObject().put("roots", JSONArray())))
            }
            "notifications/progress" -> {
                val params = msg.optJSONObject("params")
                val token = params?.opt("progressToken")?.toString()
                val message = params?.optString("message")
                    ?: params?.optString("progress")
                    ?: ""
                onProgress(token, message)
            }
            "notifications/message" -> {
                val params = msg.optJSONObject("params")
                val text = params?.optString("data") ?: params?.toString() ?: ""
                onProgress(null, text)
            }
            "elicitation/create", "elicitation/createRequest" -> {
                if (id != null) {
                    val params = msg.optJSONObject("params")
                    val answer = onElicitation(params ?: JSONObject())
                    val result = if (answer == null) {
                        JSONObject().put("action", "cancel")
                    } else {
                        JSONObject().put("action", "accept").put("content", answer)
                    }
                    transport.sendResponse(JsonRpc.response(id, result))
                }
            }
        }
    }

    fun close() {
        scope.cancel()
        transport.close()
    }
}