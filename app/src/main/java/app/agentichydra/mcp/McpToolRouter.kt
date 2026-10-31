package app.agentichydra.mcp

import org.json.JSONObject

object McpToolRouter {
    @Volatile
    var registry: McpRegistry? = null

    suspend fun call(namespaced: String, args: JSONObject, onProgress: (String?) -> Unit = {}): JSONObject {
        val reg = registry ?: throw IllegalStateException("MCP registry not initialized")
        val (state, tool) = reg.findTool(namespaced) ?: throw IllegalArgumentException("Unknown MCP tool: $namespaced")
        val session = state.session ?: throw IllegalStateException("MCP server not connected: ${state.config.displayName}")
        val progressToken = "pt-${System.currentTimeMillis()}"
        val result = session.callTool(tool.originalName, args, progressToken)
        return result
    }
}