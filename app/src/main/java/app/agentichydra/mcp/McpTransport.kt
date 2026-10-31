package app.agentichydra.mcp

import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

interface McpTransport {
    val messages: SharedFlow<JSONObject> // server-initiated messages
    val isConnected: Boolean

    suspend fun connect()
    /** Send a JSON-RPC request and return the matching response object. */
    suspend fun sendRequest(request: JSONObject): JSONObject
    /** Send a JSON-RPC notification (no response). */
    suspend fun sendNotification(notification: JSONObject)
    /** Send a JSON-RPC response to a server-initiated request. */
    suspend fun sendResponse(response: JSONObject)
    fun close()
}