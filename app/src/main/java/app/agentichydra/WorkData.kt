package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WorkToast(
    val conversationId: String,
    val message: String,
)

internal fun isCancellationNotice(text: String): Boolean {
    val clean = text.trim().trimEnd('.').lowercase()
    return clean in setOf("canceled", "cancelled", "stopped") ||
        clean.startsWith("stopped. commands already launched in termux")
}

/** A step the engine queued or started but never reported an outcome for. */
internal fun WorkStep.isPending(): Boolean = state == "Waiting" || state == "Running"

/** Gives every unfinished step an honest end state when a run stops, fails, or is interrupted. */
internal fun List<WorkStep>.markPendingStepsAs(state: String): List<WorkStep> = map { if (it.isPending()) it.copy(state = state) else it }

data class GroupedToolMessages(
    val messages: List<Message>,
    val reads: Map<String, List<WorkStep>>,
)

/**
 * Collapses consecutive reads of the same file into one timeline row.
 * Line ranges and other arguments may differ; only the path decides the group.
 */
internal fun groupReadFileReads(
    messages: List<Message>,
    steps: List<WorkStep>,
): GroupedToolMessages {
    val stepById = steps.associateBy { it.id }
    val reads = mutableMapOf<String, List<WorkStep>>()
    val display = mutableListOf<Message>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        val step = stepById[message.id]
        val path = step?.readFilePath()
        if (message.role != "tool" || path == null) {
            display += message
            index++
            continue
        }
        val group = mutableListOf(step)
        var end = index
        while (end + 1 < messages.size) {
            val next = messages[end + 1]
            val nextStep = stepById[next.id]
            if (next.role != "tool" || nextStep == null || nextStep.readFilePath() != path) break
            group += nextStep
            end++
        }
        display += message
        if (group.size > 1) reads[message.id] = group
        index = end + 1
    }
    return GroupedToolMessages(display, reads)
}

private fun WorkStep.readFilePath(): String? =
    runCatching { JSONObject(arguments).optString("path").trim() }.getOrNull()?.takeIf {
        tool == "read_file" && it.isNotEmpty()
    }

/** Newest-first conversation history, replacing any earlier copy of the same record. */
internal fun List<WorkRecord>.upserted(record: WorkRecord): List<WorkRecord> =
    (listOf(record) + filterNot { it.id == record.id }).take(MAX_SAVED_WORK_RECORDS)

internal const val MAX_SAVED_WORK_RECORDS = 40

enum class ApprovalMode(
    val title: String,
    val detail: String,
) {
    EVERY("Ask every time", "Approve file reads, changes, commands, and web access."),
    CHANGES("Ask for changes", "Read and search freely; approve changes and every shell command."),
    SESSION("Allow next task", "Allow all tools for the next turn; reset afterward."),
    FULL("Full access", "Allow tools, file changes, and commands without asking. Stays enabled until changed."),
}

object ToolPolicy {
    private val readTools =
        setOf(
            "list_files",
            "list_files_recursive",
            "read_file",
            "search_files",
            "find_files",
            "file_info",
            "web_search",
            "web_search_batch",
            "fetch_url",
            "find_in_page",
            "get_time",
            "wait_for",
            "wait_until",
            "list_scheduled_tasks",
            "read_task_output",
            "read_image",
        ) +
            DeviceTools.readOnly

    fun parts(path: String): List<String> {
        require(!path.startsWith('/') && !path.contains('\\')) { "Use a relative project path." }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." || it.contains('\u0000') }) { "Paths must stay inside the selected project." }
        return parts
    }

    fun isRead(tool: String): Boolean =
        tool in readTools || (app.agentichydra.mcp.McpToolRouter.registry?.readOnlyToolNames()?.contains(tool) == true)

    fun approval(
        mode: ApprovalMode,
        tool: String,
    ) = tool !in setOf("ask_user", "quiz_user") &&
        (mode == ApprovalMode.EVERY || (mode == ApprovalMode.CHANGES && !isRead(tool)))
}

/** Live byte counters for a native file download. */
data class DownloadProgress(
    val path: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val elapsedMs: Long = 0,
    val bytesPerSecond: Long = 0,
) {
    fun json() =
        JSONObject()
            .put("path", path)
            .put("downloaded", downloaded)
            .put("total", total)
            .put("elapsedMs", elapsedMs)
            .put("bytesPerSecond", bytesPerSecond)

    companion object {
        fun fromJson(o: JSONObject) =
            DownloadProgress(
                o.optString("path"),
                o.optLong("downloaded"),
                o.optLong("total"),
                o.optLong("elapsedMs"),
                o.optLong("bytesPerSecond"),
            )
    }
}

data class WorkStep(
    val tool: String,
    val arguments: String,
    val state: String = "Waiting",
    val output: String = "",
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val download: DownloadProgress? = null,
) {
    fun json() =
        JSONObject()
            .put(
                "tool",
                tool,
            ).put("args", arguments)
            .put("state", state)
            .put("output", output.take(MAX_TOOL_OUTPUT))
            .put("id", id)
            .put("createdAt", createdAt)
            .apply { download?.let { put("download", it.json()) } }

    companion object {
        fun fromJson(o: JSONObject) =
            WorkStep(
                o.getString("tool"),
                o.getString("args"),
                o.optString("state", "Done"),
                o.optString("output"),
                o.optString("id").ifBlank { UUID.randomUUID().toString() },
                o.optLong("createdAt", 0),
                o.optJSONObject("download")?.let(DownloadProgress::fromJson),
            )
    }
}

data class FileChange(
    val path: String,
    val before: String,
    val after: String,
) {
    fun json() = JSONObject().put("path", path).put("before", before).put("after", after)
}

data class WorkRecord(
    val title: String,
    val status: String,
    val summary: String,
    val steps: List<WorkStep>,
    val changes: List<FileChange>,
    val id: String = UUID.randomUUID().toString(),
    val messages: List<Message> = emptyList(),
    val apiHistory: String = "[]",
    val projectUri: String? = null,
    val projectName: String = "No project selected",
    val directory: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** "prompt" until the model or the user names the conversation, then "ai" or "manual". */
    val titleSource: String = "prompt",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("titleSource", titleSource)
            .put("status", status)
            .put("summary", summary)
            .put("steps", JSONArray().apply { steps.forEach { put(it.json()) } })
            .put("changes", JSONArray().apply { changes.forEach { put(it.json()) } })
            .put("messages", JSONArray().apply { messages.forEach { put(it.json()) } })
            .put("apiHistory", apiHistory)
            .put(
                "projectUri",
                projectUri ?: JSONObject.NULL,
            ).put("projectName", projectName)
            .put("directory", directory)
            .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(
            o: JSONObject,
            fallbackProject: String? = null,
            fallbackName: String = "No project selected",
            fallbackDirectory: String = "",
        ): WorkRecord {
            val summary = o.optString("summary")
            val stepsJson = o.optJSONArray("steps") ?: JSONArray()
            val changesJson = o.optJSONArray("changes") ?: JSONArray()
            val messagesJson = o.optJSONArray("messages") ?: JSONArray()
            val steps = (0 until stepsJson.length()).map { WorkStep.fromJson(stepsJson.getJSONObject(it)) }
            val changes =
                (0 until changesJson.length()).map {
                    val change = changesJson.getJSONObject(it)
                    FileChange(change.getString("path"), change.getString("before"), change.getString("after"))
                }
            val messages =
                if (messagesJson.length() > 0) {
                    (0 until messagesJson.length()).map { Message.fromJson(messagesJson.getJSONObject(it)) }
                } else {
                    buildList {
                        add(Message("user", o.optString("title"), createdAt = 0))
                        steps.forEach { add(Message("tool", it.tool, id = it.id, createdAt = it.createdAt)) }
                        if (summary.isNotBlank()) {
                            add(Message("assistant", summary, createdAt = 0))
                        }
                    }
                }
            val status = o.optString("status", "Interrupted")
            val endedWithCancellation =
                messages.lastOrNull()?.let { it.role == "notice" && isCancellationNotice(it.text) } == true ||
                    isCancellationNotice(summary)
            val cancelled =
                status in setOf("Stopped", "Canceled", "Cancelled") ||
                    (status == "Failed" && endedWithCancellation)
            val visibleMessages =
                if (cancelled) {
                    messages.filterNot {
                        it.role in setOf("notice", "assistant") && isCancellationNotice(it.text)
                    }
                } else {
                    messages
                }
            val visibleSteps =
                if (cancelled) {
                    steps.map {
                        if (it.state in setOf("Waiting", "Running", "Interrupted")) {
                            it.copy(state = "Cancelled")
                        } else {
                            it
                        }
                    }
                } else {
                    steps
                }
            return WorkRecord(
                o.getString("title"),
                if (cancelled) "Cancelled" else status,
                if (cancelled && isCancellationNotice(summary)) {
                    ""
                } else {
                    summary
                },
                visibleSteps,
                changes,
                o.optString("id").ifBlank { UUID.randomUUID().toString() },
                visibleMessages,
                o.optString("apiHistory", "[]"),
                if (o.has("projectUri")) {
                    if (o.isNull("projectUri")) null else o.getString("projectUri")
                } else {
                    fallbackProject
                },
                o.optString("projectName", fallbackName),
                o.optString("directory", fallbackDirectory),
                o.optLong("updatedAt", 0),
                o.optString("titleSource", "prompt"),
            )
        }
    }
}

/** Keep every assistant tool call paired with a result before accepting a follow-up. */
internal fun repairedWorkHistory(raw: String): JSONArray {
    val history = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    val repaired = JSONArray()
    var pending = emptySet<String>()

    fun finishPending() {
        pending.forEach {
            repaired.put(
                JSONObject()
                    .put(
                        "role",
                        "tool",
                    ).put(
                        "tool_call_id",
                        it,
                    ).put(
                        "content",
                        "Execution was interrupted before this tool finished. Check the current project state before retrying.",
                    ),
            )
        }
        pending =
            emptySet()
    }
    for (i in 0 until history.length()) {
        val message = history.getJSONObject(i)
        if (message.optString("role") == "tool") {
            val id = message.optString("tool_call_id")
            if (id in pending) {
                repaired.put(message)
                pending = pending - id
            }
        } else {
            finishPending()
            repaired.put(message)
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            pending = (0 until calls.length()).map { calls.getJSONObject(it).getString("id") }.toSet()
        }
    }
    finishPending()
    return repaired
}
