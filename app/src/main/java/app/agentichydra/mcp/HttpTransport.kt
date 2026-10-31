package app.agentichydra.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress

class McpHttpException(val code: Int, val body: String) : RuntimeException("HTTP $code: $body")
class McpAuthException(val wwwAuthenticate: String?) : RuntimeException("MCP auth required")

class HttpTransport(
    private val url: String,
    private val allowPrivate: Boolean,
    private val bearerToken: String? = null,
) : McpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _messages = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: SharedFlow<JSONObject> = _messages.asSharedFlow()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    override var isConnected: Boolean = false
        private set

    init {
        if (!allowPrivate) {
            val host = java.net.URL(url).host
            if (isPrivateHost(host)) throw IllegalArgumentException("Private/LAN URL not allowed: $host")
        }
    }

    override suspend fun connect() {
        isConnected = true
    }

    override suspend fun sendRequest(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val reqId = request.opt("id")?.toString()
        val rb = Request.Builder().url(url)
            .post(request.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        sessionId?.let { rb.header("Mcp-Session-Id", it) }
        bearerToken?.let { rb.header("Authorization", "Bearer $it") }
        val call = client.newCall(rb.build())
        // Cancellation of the coroutine cancels the OkHttp call.
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        job?.let { j ->
            j.invokeOnCompletion { if (j.isCancelled) call.cancel() }
        }
        val response = call.execute()
        try {
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            if (response.code == 401) {
                throw McpAuthException(response.header("WWW-Authenticate"))
            }
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw McpHttpException(response.code, body)
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("text/event-stream")) {
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                val dataBuf = StringBuilder()
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val chunk = line.removePrefix("data:").trim()
                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                        dataBuf.append(chunk)
                    } else if (line.isBlank()) {
                        if (dataBuf.isNotEmpty()) {
                            val payload = dataBuf.toString().trim()
                            dataBuf.setLength(0)
                            if (payload.isNotEmpty() && payload != "[DONE]") {
                                val json = JSONObject(payload)
                                if (json.has("method") && !json.has("result") && !json.has("error")) {
                                    _messages.tryEmit(json)
                                }
                                val rid = if (json.has("id")) json.get("id").toString() else null
                                if (rid != null && rid == reqId) {
                                    return@withContext json
                                }
                                if ((json.has("result") || json.has("error")) && reqId == null) {
                                    return@withContext json
                                }
                            }
                        }
                    }
                }
                // stream ended; try parse remainder
                if (dataBuf.isNotEmpty()) {
                    val payload = dataBuf.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        val json = JSONObject(payload)
                        return@withContext json
                    }
                }
                throw RuntimeException("SSE stream ended without response")
            } else {
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) throw RuntimeException("Empty response")
                return@withContext JSONObject(body)
            }
        } finally {
            response.close()
        }
    }

    override suspend fun sendNotification(notification: JSONObject) = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
            .post(notification.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
        sessionId?.let { rb.header("Mcp-Session-Id", it) }
        bearerToken?.let { rb.header("Authorization", "Bearer $it") }
        client.newCall(rb.build()).execute().close()
    }

    override suspend fun sendResponse(response: JSONObject) = sendNotification(response)

    override fun close() {
        isConnected = false
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        fun isPrivateHost(host: String): Boolean {
            return try {
                val addr = InetAddress.getByName(host)
                addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress
            } catch (_: Exception) {
                // hostname not resolvable -> treat as potentially private? default false for hostnames
                false
            }
        }
    }
}