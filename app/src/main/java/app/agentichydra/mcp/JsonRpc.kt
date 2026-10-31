package app.agentichydra.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object JsonRpc {
    private val idCounter = AtomicInteger(1)

    fun nextId(): Int = idCounter.getAndIncrement()

    fun request(method: String, params: JSONObject? = null, id: Int = nextId()): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    fun response(id: Any?, result: JSONObject? = null, error: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            if (error != null) put("error", error) else put("result", result ?: JSONObject())
        }

    fun isError(msg: JSONObject): Boolean = msg.has("error") && !msg.isNull("error")

    fun errorMessage(msg: JSONObject): String {
        val e = msg.optJSONObject("error") ?: return "Unknown MCP error"
        val code = e.optInt("code", -1)
        val m = e.optString("message", "Unknown error")
        return "MCP error $code: $m"
    }
}