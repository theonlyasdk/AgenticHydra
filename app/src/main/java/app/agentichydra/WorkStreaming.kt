package app.agentichydra

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * One streaming `/chat/completions` turn for the task loop.
 *
 * Callers build the request as usual (tools, reasoning policy, `stream: true`)
 * and hand it here. The turn reads the server-sent-event stream, surfaces text
 * through [onText] while it arrives, merges tool-call fragments, and then
 * returns the response in the exact JSON shape a non-streaming call would have
 * produced (`choices[0].message` + `usage`), so the surrounding task loop
 * doesn't need a second code path.
 */
internal class WorkStreamTurn(
    private val client: OkHttpClient,
    private val url: String,
    private val credential: String,
    private val payload: JSONObject,
    private val provider: Provider,
    private val onCall: (okhttp3.Call) -> Unit = {},
    /** One retry without tools when a model rejects the tool schema outright. */
    private val retryWithoutTools: Boolean = false,
    private val onThought: suspend (thought: String) -> Unit = {},
    private val onText: suspend (text: String) -> Unit = {},
) {
    /** Set when this turn answered without tools after the model rejected them. */
    var droppedTools = false
        private set

    /** Runs the turn and returns a non-streaming-shaped response object. */
    suspend fun run(): JSONObject {
        val firstCall = client.newCall(request())
        onCall(firstCall)
        var response = firstCall.execute()

        // Some gateways reject stream_options instead of ignoring it; drop it
        // and retry once, mirroring the chat-mode behaviour.
        if (rejectedStreamOptions(response)) {
            response.close()
            currentCoroutineContext().ensureActive()
            val retryPayload = JSONObject(payload.toString()).apply { remove(STREAM_OPTIONS) }
            val retryCall = client.newCall(request(retryPayload))
            onCall(retryCall)
            response = retryCall.execute()
        }

        // Some models reject the tools array itself; fall back to a plain answer.
        if (response.code in RETRYABLE_CODES && retryWithoutTools && payload.has(TOOLS)) {
            response.close()
            currentCoroutineContext().ensureActive()
            val fallbackPayload =
                JSONObject(payload.toString()).apply {
                    remove(TOOLS)
                    remove(TOOL_CHOICE)
                }
            val fallbackCall = client.newCall(request(fallbackPayload))
            onCall(fallbackCall)
            response = fallbackCall.execute()
            if (response.isSuccessful) droppedTools = true
        }

        response.use { result ->
            if (!result.isSuccessful) throw IOException(describeFailure(result.code))

            val peek = runCatching { result.peekBody(1024).string() }.getOrDefault("")
            if (peek.trimStart().startsWith("{")) {
                val fullBody = result.body?.string().orEmpty()
                val json = JSONObject(fullBody)
                val firstChoice = json.optJSONArray("choices")?.optJSONObject(0)
                val msg = firstChoice?.optJSONObject("message")
                val content = msg?.optString("content", "") ?: ""
                val thought =
                    msg?.optString("reasoning_content").orEmpty().ifEmpty {
                        msg?.optString("reasoning").orEmpty().ifEmpty {
                            msg?.optString("thought").orEmpty()
                        }
                    }
                if (thought.isNotEmpty()) onThought(thought)
                if (content.isNotEmpty()) onText(content)
                return json
            }

            val source = result.body?.source() ?: throw IOException("Provider returned an empty response.")
            var text = ""
            var thought = ""
            var rawContent = ""
            var hasInlineThink = false
            var usage: JSONObject? = null
            val toolCalls = ToolCallBuffer()

            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()

                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(DATA_PREFIX)) continue

                val data = line.removePrefix(DATA_PREFIX).trim()
                if (data == DONE_MARKER) break
                if (data.isEmpty()) continue

                val event = JSONObject(data)
                if (event.has("error")) {
                    throw IOException("The provider reported a streaming error. Check the model and try again.")
                }

                event.optJSONObject("usage")?.let { usage = it }

                val delta = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue

                val reasoningChunk =
                    delta.optString("reasoning_content").ifEmpty {
                        delta.optString("reasoning").ifEmpty {
                            delta.optString("thought")
                        }
                    }
                if (reasoningChunk.isNotEmpty()) {
                    thought += reasoningChunk
                    onThought(thought)
                }

                if (!delta.isNull("content")) {
                    val chunk = delta.optString("content", "")
                    if (chunk.isNotEmpty()) {
                        rawContent += chunk
                        if (thought.isEmpty() && (hasInlineThink || rawContent.trimStart().startsWith("<think>"))) {
                            hasInlineThink = true
                            val afterOpen = rawContent.trimStart().removePrefix("<think>")
                            if (afterOpen.contains("</think>")) {
                                val currentThought = afterOpen.substringBefore("</think>").trim()
                                onThought(currentThought)
                                text = afterOpen.substringAfter("</think>").trimStart()
                                onText(text)
                            } else {
                                onThought(afterOpen.trimStart())
                            }
                        } else {
                            val trimmed = rawContent.trimStart()
                            if (thought.isEmpty() && !hasInlineThink && trimmed.length < 7 && "<think>".startsWith(trimmed)) {
                                // Buffer potential <think> tag prefix until confirmed or diverged
                            } else {
                                text =
                                    if (hasInlineThink && rawContent.contains("</think>")) {
                                        rawContent.substringAfter("</think>").trimStart()
                                    } else {
                                        text + chunk
                                    }
                                onText(text)
                            }
                        }
                    }
                }
                delta.optJSONArray("tool_calls")?.let { toolCalls.absorb(it) }
            }

            if (hasInlineThink && thought.isEmpty()) {
                val afterOpen = rawContent.trimStart().removePrefix("<think>")
                thought =
                    if (afterOpen.contains("</think>")) {
                        afterOpen.substringBefore("</think>").trim()
                    } else {
                        afterOpen.trim()
                    }
                text =
                    if (afterOpen.contains("</think>")) {
                        afterOpen.substringAfter("</think>").trimStart()
                    } else {
                        ""
                    }
            }

            return assemble(text, usage, toolCalls, thought)
        }
    }

    // Request builders ------------------------------------------------------

    private fun request(): Request = request(payload)

    private fun request(body: JSONObject): Request {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(JSON_TYPE))
        if (credential.isNotBlank()) builder.header("Authorization", "Bearer $credential")
        return builder.build()
    }

    // Streaming response parsing -------------------------------------------

    /** Reassembles the deltas into a non-streaming-shaped response object. */
    private fun assemble(
        text: String,
        usage: JSONObject?,
        toolCalls: ToolCallBuffer,
        thought: String = "",
    ): JSONObject {
        val message =
            JSONObject()
                .put("role", "assistant")
                .put("content", text)
        if (thought.isNotBlank()) {
            message.put("reasoning_content", thought)
        }

        val merged = toolCalls.finish()
        if (merged.length() > 0) message.put("tool_calls", merged)

        val choices = JSONArray().put(JSONObject().put("message", message))
        return JSONObject().put("choices", choices).apply { usage?.let { put("usage", it) } }
    }

    /**
     * True when the server rejected the request specifically because of
     * `stream_options` (the retry is pointless for other 4xx causes).
     */
    private fun rejectedStreamOptions(response: Response): Boolean {
        if (response.code !in RETRYABLE_CODES) return false
        if (provider == Provider.CUSTOM || !payload.has(STREAM_OPTIONS)) return false

        val detail = response.peekBody(PEEK_LIMIT).string()
        return detail.contains(STREAM_OPTIONS, ignoreCase = true) ||
            detail.contains(INCLUDE_USAGE, ignoreCase = true)
    }

    private fun describeFailure(code: Int): String =
        when (code) {
            400, 422 -> "The model rejected this request. Try Default reasoning or choose a compatible model."
            401, 403 -> "Access denied. Check your API key and model permissions."
            404 -> "Model or endpoint not found. Check the connection settings."
            429 -> "Provider limit reached. Wait a moment and try again."
            else -> "Provider returned HTTP $code. Check this model's tool support, reasoning setting, and connection."
        }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val STREAM_OPTIONS = "stream_options"
        const val INCLUDE_USAGE = "include_usage"
        const val TOOLS = "tools"
        const val TOOL_CHOICE = "tool_choice"
        val JSON_TYPE = "application/json".toMediaType()
        const val PEEK_LIMIT = 16384L
        val RETRYABLE_CODES = setOf(400, 422)
    }
}

/**
 * Merges indexed tool-call deltas into complete entries in the OpenAI
 * streaming format: arguments arrive as text fragments, the call `id` and
 * `name` usually only arrive on the first fragment for a given `index`.
 */
private class ToolCallBuffer {
    private val entries = mutableMapOf<Int, JSONObject>()

    fun absorb(deltas: JSONArray) {
        for (i in 0 until deltas.length()) {
            val delta = deltas.optJSONObject(i) ?: continue
            val fragment = delta.optJSONObject("function") ?: continue

            val index = if (delta.has("index")) delta.optInt("index") else entries.size
            val entry = entries.getOrPut(index) { JSONObject().put("type", "function") }
            val function = entry.optJSONObject("function") ?: JSONObject().also { entry.put("function", it) }

            delta.optString("id", "").takeIf { it.isNotEmpty() }?.let { entry.put("id", it) }
            fragment.optString("name", "").takeIf { it.isNotEmpty() }?.let { function.put("name", it) }
            val arguments = function.optString("arguments", "") + fragment.optString("arguments", "")
            if (arguments.isNotEmpty()) function.put("arguments", arguments)
        }
    }

    /** Returns the buffer's calls as a JSON array, filling in missing ids. */
    fun finish(): JSONArray {
        val calls = JSONArray()
        for (index in entries.keys.sorted()) {
            val entry = entries.getValue(index)
            if (!entry.has("id")) entry.put("id", "call_${index + 1}")
            calls.put(entry)
        }
        return calls
    }
}

/** Separates reasoning thought from response text, handling tags and API fields. */
internal fun extractThought(
    rawText: String,
    rawThought: String,
): Pair<String, String> {
    if (rawThought.isNotBlank()) {
        val cleanText =
            if (rawText.trimStart().startsWith("<think>") && rawText.contains("</think>")) {
                rawText.substringAfter("</think>").trimStart()
            } else {
                rawText
            }
        return cleanText to rawThought
    }
    if (rawText.trimStart().startsWith("<think>")) {
        val thought =
            if (rawText.contains("</think>")) {
                rawText.substringAfter("<think>").substringBefore("</think>").trim()
            } else {
                rawText.trimStart().removePrefix("<think>").trim()
            }
        val text = if (rawText.contains("</think>")) rawText.substringAfter("</think>").trimStart() else ""
        return text to thought
    }
    return rawText to ""
}
