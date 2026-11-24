package app.agentichydra

import app.agentichydra.mcp.JsonRpc
import app.agentichydra.mcp.McpRegistry
import app.agentichydra.mcp.McpServerConfig
import app.agentichydra.mcp.McpSession
import app.agentichydra.mcp.McpTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkProtocolTest {
    @Test
    fun diffPreservesWhitespaceAndSupportsDeletionAcrossBlocks() {
        val diff = "------- SEARCH\r\n  old  \r\n=======\r\n  new  \r\n+++++++ REPLACE\r\n" +
            "------- SEARCH\r\nremove me\r\n=======\r\n+++++++ REPLACE"
        assertEquals(
            listOf(DiffBlock("  old  \n", "  new  \n"), DiffBlock("remove me\n", "")),
            parseDiffBlocks(diff),
        )
        assertThrows(IllegalArgumentException::class.java) { parseDiffBlocks("------- SEARCH\nold\n=======") }
        assertThrows(IllegalArgumentException::class.java) {
            parseDiffBlocks("------- SEARCH\n=======\nnew\n+++++++ REPLACE")
        }
    }

    @Test
    fun toolBufferAssemblesInterleavedFragmentsAndFillsMissingIds() {
        fun delta(index: Int, name: String, args: String, id: String? = null): JSONObject =
            JSONObject().put("index", index).put("id", id)
                .put("function", JSONObject().put("name", name).put("arguments", args))

        val buffer = ToolCallBuffer()
        assertTrue(buffer.absorb(JSONArray().put(delta(1, "fetch_url", "{\"url\":")).put(delta(0, "read_file", "{\"path\":", "file-1"))))
        assertEquals(listOf("read_file", "fetch_url"), buffer.names())
        assertFalse(buffer.absorb(JSONArray().put(delta(0, "", "\"readme.md\"}")).put(delta(1, "", "\"https://example.org\"}"))))
        val calls = buffer.finish()
        assertEquals(2, calls.length())
        val first = calls.getJSONObject(0)
        val second = calls.getJSONObject(1)
        assertEquals("file-1", first.getString("id"))
        assertEquals("read_file", first.getJSONObject("function").getString("name"))
        assertEquals("readme.md", JSONObject(first.getJSONObject("function").getString("arguments")).getString("path"))
        assertEquals("call_2", second.getString("id"))
        assertEquals("https://example.org", JSONObject(second.getJSONObject("function").getString("arguments")).getString("url"))
    }

    @Test
    fun mcpConnectNegotiatesThenListsEveryPageAndReturnsProtocolErrors() = runBlocking {
        val transport = TestTransport()
        val session = McpSession(McpServerConfig("docs", "Docs"), transport)
        try {
            val tools = session.connect()
            assertEquals("2025-06-18", session.protocolVersion)
            assertEquals(listOf("lookup", "update"), tools.map { it.originalName })
            assertEquals(listOf(true, false), tools.map { it.readOnlyHint })
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list"), transport.methods)
            val result = session.callTool("lookup", JSONObject().put("query", "example"), "progress")
            assertTrue(result.getBoolean("ok"))
            val error = runCatching { session.callTool("missing", JSONObject()) }.exceptionOrNull()
            assertEquals("MCP error -32602: Unknown tool", error?.message)
        } finally {
            session.close()
        }
    }

    @Test
    fun allowlistDistinguishesMissingFromEmptyAndRejectsFutureTools() {
        val base = McpServerConfig("test", "Test", disabledTools = setOf("write"))
        val legacy = McpRegistry.configFromJson(McpRegistry.configToJson(base))
        assertTrue(legacy.permitsTool("read"))
        assertFalse(legacy.permitsTool("write"))
        val locked = McpRegistry.configFromJson(McpRegistry.configToJson(base.copy(allowedTools = setOf("read"))))
        assertTrue(locked.permitsTool("read"))
        assertFalse(locked.permitsTool("new_tool"))
        val empty = McpRegistry.configFromJson(McpRegistry.configToJson(base.copy(allowedTools = emptySet())))
        assertFalse(empty.permitsTool("read"))
        val response = JsonRpc.response(null, error = JSONObject().put("code", -32700))
        assertTrue(response.has("id"))
        assertTrue(response.isNull("id"))
    }

    private class TestTransport : McpTransport {
        override val messages = MutableSharedFlow<JSONObject>()
        override var isConnected = false
            private set
        val methods = mutableListOf<String>()

        override suspend fun connect() {
            isConnected = true
        }

        override suspend fun sendNotification(notification: JSONObject) {
            assertFalse(notification.has("id"))
            methods.add(notification.getString("method"))
        }

        override suspend fun sendRequest(request: JSONObject): JSONObject {
            val method = request.getString("method")
            val params = request.getJSONObject("params")
            val id = request.getInt("id")
            assertEquals("2.0", request.getString("jsonrpc"))
            methods.add(method)
            val result = when (method) {
                "initialize" -> {
                    assertEquals("2025-06-18", params.getString("protocolVersion"))
                    JSONObject().put("protocolVersion", "2025-06-18")
                }
                "tools/list" -> {
                    val first = !params.has("cursor")
                    if (!first) assertEquals("next", params.getString("cursor"))
                    JSONObject().put(
                        "tools",
                        JSONArray().put(
                            JSONObject().put("name", if (first) "lookup" else "update")
                                .put("annotations", JSONObject().put("readOnlyHint", first)),
                        ),
                    ).apply { if (first) put("nextCursor", "next") }
                }
                "tools/call" -> {
                    if (params.getString("name") == "missing") {
                        return JsonRpc.response(id, error = JSONObject().put("code", -32602).put("message", "Unknown tool"))
                    }
                    assertEquals("example", params.getJSONObject("arguments").getString("query"))
                    assertEquals("progress", params.getJSONObject("_meta").getString("progressToken"))
                    JSONObject().put("ok", true)
                }
                else -> error("Unexpected method $method")
            }
            return JsonRpc.response(id, result)
        }

        override suspend fun sendResponse(response: JSONObject) = Unit

        override fun close() {
            isConnected = false
        }
    }
}
