package app.agentichydra

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal data class StreamDraft(val text: String, val thought: String, val chunkStarts: List<Int>)

/** Tracks provider chunks before draft updates are batched for the UI. */
internal class StreamChunkTail {
    private var length = 0
    private var starts = emptyList<Int>()

    fun append(text: String) {
        if (text.length < length) {
            length = 0
            starts = emptyList()
        }
        if (text.length > length) {
            starts = (starts + length).takeLast(2)
            length = text.length
        }
    }

    fun snapshot(text: String, thought: String) = StreamDraft(text, thought, starts)
}

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
    /** Pacing and automatic-resume choices from Provider settings. */
    private val rateLimit: RateLimitPolicy = RateLimitPolicy(),
    /** Called before an automatic retry so the caller can surface the wait. */
    private val onRateLimitWait: suspend (waitMillis: Long) -> Unit = {},
    /** Called once a retried turn finally succeeds, so transient notices can be cleared. */
    private val onRateLimitCleared: suspend () -> Unit = {},
    private val onThought: suspend (thought: String) -> Unit = {},
    private val usageTask: UsageTask? = null,
    private val onToolNames: suspend (List<String>) -> Unit = {},
    private val onText: suspend (text: String) -> Unit = {},
) {
    /** Set when this turn answered without tools after the model rejected them. */
    var droppedTools = false
        private set

    /** Runs the turn, pacing it and resuming it automatically across provider rate limits. */
    suspend fun run(): JSONObject {
        var attempts = 0
        var waited = false
        while (true) {
            if (rateLimit.prevent) RequestPacer.acquire(provider, rateLimit.rpm)
            try {
                val result = runOnce()
                UsageLedger.record(usageTask, provider, payload.optString("model"), result)
                if (waited) onRateLimitCleared()
                return result
            } catch (e: ProviderRateLimitException) {
                val wait = (e.retryAfterMillis * (attempts + 1)).coerceAtMost(MAX_RATE_LIMIT_WAIT)
                RequestPacer.noteRateLimited(provider, wait)
                attempts++
                if (!rateLimit.resume || attempts > rateLimit.maxRetries) {
                    throw IOException(rateLimitFailureMessage(e, wait, retriesExhausted = rateLimit.resume))
                }
                waited = true
                onRateLimitWait(wait)
                // With pacing on, the next acquire already waits for the provider window.
                if (!rateLimit.prevent) delay(wait)
            }
        }
    }

    private fun rateLimitFailureMessage(
        error: ProviderRateLimitException,
        waitMillis: Long,
        retriesExhausted: Boolean,
    ): String =
        if (retriesExhausted) {
            "The provider is still rate limiting this key. Wait ${waitLabel(waitMillis)} and retry, " +
                "or lower Requests per minute in Provider settings."
        } else {
            "Provider rate limit reached. Wait ${waitLabel(waitMillis)} and retry, or turn on automatic " +
                "resume in Provider settings."
        }

    private suspend fun runOnce(): JSONObject {
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
            if (!result.isSuccessful) {
                if (result.code == 429) {
                    throw ProviderRateLimitException(
                        "Provider rate limit reached.",
                        result.rateLimitWaitMillis(provider),
                    )
                }
                throw IOException(describeFailure(result.code, runCatching { result.peekBody(PEEK_LIMIT).string() }.getOrNull()))
            }
            if (rateLimit.prevent) {
                RequestPacer.noteResponseHeaders(
                    provider,
                    result.header("x-ratelimit-remaining-requests"),
                    result.header("x-ratelimit-reset-requests"),
                )
            }

            val peek = runCatching { result.peekBody(1024).string() }.getOrDefault("")
            if (peek.trimStart().startsWith("{")) {
                val fullBody = result.body?.string().orEmpty()
                val json = JSONObject(fullBody)
                val firstChoice = json.optJSONArray("choices")?.optJSONObject(0)
                val msg = firstChoice?.optJSONObject("message")
                val content = msg?.optString("content", "") ?: ""
                val thought = msg?.reasoningText().orEmpty()
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
                    val error = event.optJSONObject("error")
                    val code = error?.optInt("code", 0) ?: 0
                    val status = error?.optString("status", "").orEmpty()
                    if (code == 429 || status.equals("RESOURCE_EXHAUSTED", ignoreCase = true)) {
                        val hint = retryHintFromBody(error?.toString().orEmpty()) ?: defaultRateLimitWait(provider)
                        throw ProviderRateLimitException("Provider rate limit reached.", hint.coerceIn(2_000L, 300_000L))
                    }
                    val detail = error?.optString("message")?.takeIf { it.isNotBlank() }
                    throw IOException(
                        if (detail != null) {
                            "Provider streaming error: $detail"
                        } else {
                            "The provider reported a streaming error. Check the model and try again."
                        },
                    )
                }

                event.optJSONObject("usage")?.let { usage = it }

                val delta = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue

                val reasoningChunk = delta.reasoningText()
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
                delta.optJSONArray("tool_calls")?.let { deltas ->
                    if (toolCalls.absorb(deltas)) onToolNames(toolCalls.names())
                }
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
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
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

    private fun describeFailure(
        code: Int,
        body: String? = null,
    ): String {
        val explanation =
            when (code) {
                400, 422 -> "The model rejected this request. Try Default reasoning or choose a compatible model."
                401, 403 -> "Access denied. Check your API key and model permissions."
                404 -> "Model or endpoint not found. Check the connection settings."
                429 -> "Provider limit reached. Wait a moment and try again."
                else -> httpStatusExplanation(code)
            }
        val detail = httpErrorDetail(body)
        return if (detail != null) {
            "Provider returned HTTP $code: $detail. $explanation"
        } else {
            "Provider returned HTTP $code. $explanation"
        }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val STREAM_OPTIONS = "stream_options"
        const val INCLUDE_USAGE = "include_usage"
        const val TOOLS = "tools"
        const val TOOL_CHOICE = "tool_choice"
        const val PEEK_LIMIT = 16384L
        const val MAX_RATE_LIMIT_WAIT = 300_000L
        val RETRYABLE_CODES = setOf(400, 422)
    }
}

/**
 * Merges indexed tool-call deltas into complete entries in the OpenAI
 * streaming format: arguments arrive as text fragments, the call `id` and
 * `name` usually only arrive on the first fragment for a given `index`.
 */
internal class ToolCallBuffer {
    private class Entry {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
    }

    private val entries = mutableMapOf<Int, Entry>()

    fun absorb(deltas: JSONArray): Boolean {
        var namesChanged = false
        for (i in 0 until deltas.length()) {
            val delta = deltas.optJSONObject(i) ?: continue
            val fragment = delta.optJSONObject("function") ?: continue

            val index = if (delta.has("index")) delta.optInt("index") else entries.size
            val entry = entries.getOrPut(index) { Entry() }
            delta.optString("id", "").takeIf { it.isNotEmpty() }?.let { entry.id = it }
            fragment.optString("name", "").takeIf { it.isNotEmpty() && it != entry.name }?.let {
                entry.name = it
                namesChanged = true
            }
            entry.arguments.append(fragment.optString("arguments", ""))
        }
        return namesChanged
    }

    fun names(): List<String> = entries.toSortedMap().values.map { it.name }.filter { it.isNotEmpty() }

    /** Returns the buffer's calls as a JSON array, filling in missing ids. */
    fun finish(): JSONArray {
        val calls = JSONArray()
        for (index in entries.keys.sorted()) {
            val entry = entries.getValue(index)
            val function = JSONObject()
            if (entry.name.isNotEmpty()) function.put("name", entry.name)
            if (entry.arguments.isNotEmpty()) function.put("arguments", entry.arguments.toString())
            calls.put(
                JSONObject().put("type", "function").put("id", entry.id.ifEmpty { "call_${index + 1}" })
                    .put("function", function),
            )
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

/** Caps one tool result so a long listing cannot crowd out the conversation. */
internal const val MAX_TOOL_OUTPUT = 16000

/**
 * Streaming request body shared by the chat, task, and background engines: tools offered
 * optimistically, usage requested wherever the gateway understands it, then the reasoning policy.
 */
internal fun chatPayload(
    model: String,
    wire: JSONArray,
    tools: JSONArray,
    provider: Provider,
    reasoning: ReasoningProfile,
    effort: String,
): JSONObject {
    val payload =
        JSONObject()
            .put("model", model)
            .put("messages", wire)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("stream", true)
    if (provider != Provider.CUSTOM) {
        payload.put("stream_options", JSONObject().put("include_usage", true))
    }
    ReasoningPolicy.apply(payload, reasoning, effort)
    return payload
}

/** A request wire: the system prompt, then as many recent turns as the remaining window allows. */
internal fun chatWire(
    systemPrompt: String,
    history: JSONArray,
    budgetChars: Int = CONTEXT_HISTORY_CHARS,
    toolChars: Long = 0,
): JSONArray {
    val remaining = historyBudget(budgetChars, systemPrompt.length.toLong(), toolChars)
    return systemWire(systemPrompt, pruneHistory(history, remaining))
}

/**
 * Enqueues [request] and resolves with [handler]'s result, closing the response afterwards.
 * Cancelling the awaiting coroutine cancels the HTTP call, and any failure inside [handler]
 * reaches the caller as the exception it threw rather than as a silent default.
 */
internal suspend fun <T> OkHttpClient.awaitResponse(
    request: Request,
    handler: (Response) -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        response.use { continuation.resume(handler(it)) }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            },
        )
    }

/** A JSON POST to a provider endpoint, with the key omitted when the deployment needs none. */
internal fun chatCompletionRequest(
    endpoint: String,
    payload: JSONObject,
    credential: String,
): Request =
    Request
        .Builder()
        .url("${endpoint.trimEnd('/')}/chat/completions")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .apply {
            if (credential.isNotBlank()) header("Authorization", "Bearer ${credential.trim()}")
        }.build()

/** Reasoning text, tolerating whichever field name a gateway uses. */
internal fun JSONObject.reasoningText(): String =
    optString("reasoning_content").ifEmpty { optString("reasoning").ifEmpty { optString("thought") } }

/** The assistant message a completed turn produced. */
internal fun JSONObject.assistantMessage(): JSONObject = choicesAt(0).getJSONObject("message")

private fun JSONObject.choicesAt(index: Int): JSONObject = getJSONArray("choices").getJSONObject(index)

/** Message text paired with its reasoning, with a null content treated as empty. */
internal fun JSONObject.responseTextAndThought(): Pair<String, String> {
    val content = if (isNull("content")) "" else optString("content", "")
    return content to reasoningText()
}

/** One entry of a turn's `tool_calls` array, read once so every engine records it alike. */
internal data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

internal fun JSONArray.toolCallAt(index: Int): ToolCall {
    val call = getJSONObject(index)
    val function = call.getJSONObject("function")
    return ToolCall(call.getString("id"), function.getString("name"), function.get("arguments").toString())
}

/** The tool message that pairs a call with its result. */
internal fun toolResultMessage(
    callId: String,
    output: String,
): JSONObject =
    JSONObject()
        .put("role", "tool")
        .put("tool_call_id", callId)
        .put("content", output.take(MAX_TOOL_OUTPUT))

/** First non-blank line of a response, shown while a task is still running. */
internal fun progressHeadline(text: String): String =
    text
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(PROGRESS_HEADLINE_LIMIT)
        .orEmpty()

private const val PROGRESS_HEADLINE_LIMIT = 240
