package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Tokens of request context one conversation shares by default, roughly 16k. */
internal const val DEFAULT_CONTEXT_TOKENS = 16_000
internal const val CONTEXT_HISTORY_CHARS = DEFAULT_CONTEXT_TOKENS * 4

private const val PRUNE_NOTE =
    "Some earlier messages were omitted to fit this model's context window. Continue from the most recent messages and do not ask the user to repeat details that are no longer visible."

internal data class PrunedHistory(
    val messages: JSONArray,
    val sentChars: Long,
    val totalChars: Long,
) {
    val trimmed get() = totalChars > sentChars
}

/** What one request currently carries, shown by the app-bar ring and its sheet. */
data class ContextUsage(
    val sentChars: Long = 0,
    val totalChars: Long = 0,
    val systemChars: Long = 0,
    val toolChars: Long = 0,
    val budgetChars: Int = CONTEXT_HISTORY_CHARS,
    val keptMessages: Int = 0,
    val totalMessages: Int = 0,
) {
    /** Everything the provider receives before its reply: system prompt, tools, and turns. */
    val usedChars: Long get() = sentChars + systemChars + toolChars
    val fraction: Float get() = if (budgetChars <= 0) 0f else (usedChars.toFloat() / budgetChars).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).roundToInt()
    val freeChars: Long get() = (budgetChars - usedChars).coerceAtLeast(0)
    val trimmed get() = totalMessages > keptMessages
    val overBudget get() = usedChars > budgetChars
}

/** Leaves the system prompt and tool schemas their room before replaying turns. */
internal fun historyBudget(
    budgetChars: Int,
    systemChars: Long,
    toolChars: Long,
): Int = (budgetChars - systemChars - toolChars).coerceAtLeast(1).toInt()

internal fun measureContext(
    history: JSONArray,
    budgetChars: Int = CONTEXT_HISTORY_CHARS,
    systemChars: Long = 0,
    toolChars: Long = 0,
): ContextUsage {
    val pruned = pruneHistory(history, historyBudget(budgetChars, systemChars, toolChars))
    return ContextUsage(
        sentChars = pruned.sentChars,
        totalChars = pruned.totalChars,
        systemChars = systemChars,
        toolChars = toolChars,
        budgetChars = budgetChars,
        keptMessages = pruned.messages.length(),
        totalMessages = history.length(),
    )
}

/**
 * Keeps the newest complete turns within [maxChars]. A turn starts at a user message and runs
 * to the next one, so an assistant tool call never arrives without its tool results.
 */
internal fun pruneHistory(
    history: JSONArray,
    maxChars: Int = CONTEXT_HISTORY_CHARS,
): PrunedHistory {
    require(maxChars > 0) { "The context budget must be positive." }
    if (history.length() == 0) return PrunedHistory(JSONArray(), 0, 0)
    val costs = LongArray(history.length()) { history.getJSONObject(it).estimatedChars() }
    val turns = (0 until history.length()).filter { history.getJSONObject(it).optString("role") == "user" }
    if (turns.isEmpty() || turns.first() != 0) return trailingMessages(history, costs, maxChars)

    var keptChars = 0L
    var keepFrom = turns.last()
    for (index in turns.indices.reversed()) {
        val start = turns[index]
        val end = turns.getOrNull(index + 1) ?: history.length()
        val turnChars = (start until end).sumOf { costs[it] }
        if (keptChars > 0 && keptChars + turnChars > maxChars) break
        keptChars += turnChars
        keepFrom = start
    }
    return PrunedHistory(slice(history, keepFrom), keptChars, costs.sum())
}

/** Budget fallback for histories without a leading prompt; never starts on an orphan tool result. */
private fun trailingMessages(
    history: JSONArray,
    costs: LongArray,
    maxChars: Int,
): PrunedHistory {
    var from = history.length() - 1
    var chars = costs[from]
    while (from > 0 && chars + costs[from - 1] <= maxChars) {
        from--
        chars += costs[from]
    }
    if (history.getJSONObject(from).optString("role") == "tool") {
        while (from > 0 && history.getJSONObject(from).optString("role") == "tool") from--
        // A history that starts on a tool result has no call to pair with it, so skip those.
        if (from == 0 && history.getJSONObject(0).optString("role") == "tool") {
            while (from < history.length() && history.getJSONObject(from).optString("role") == "tool") from++
        }
    }
    return PrunedHistory(slice(history, from), (from until history.length()).sumOf { costs[it] }, costs.sum())
}

private fun slice(
    history: JSONArray,
    from: Int,
): JSONArray =
    JSONArray().apply {
        for (index in from until history.length()) put(history.getJSONObject(index))
    }

private fun JSONObject.estimatedChars(): Long = toString().length.toLong()

/** System prompt plus the retained turns, telling the model when older messages were dropped. */
internal fun systemWire(
    systemPrompt: String,
    pruned: PrunedHistory,
): JSONArray =
    JSONArray().apply {
        put(
            JSONObject()
                .put("role", "system")
                .put("content", if (pruned.trimmed) "$systemPrompt\n\n$PRUNE_NOTE" else systemPrompt),
        )
        for (index in 0 until pruned.messages.length()) put(pruned.messages.getJSONObject(index))
    }
