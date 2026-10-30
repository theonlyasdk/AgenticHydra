package app.agentichydra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.runtime.*
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object ScheduleTools {
    val names =
        listOf(
            "get_time",
            "wait_for",
            "wait_until",
            "schedule_task",
            "list_scheduled_tasks",
            "update_scheduled_task",
            "cancel_scheduled_task",
        )

    fun currentTime() =
        JSONObject()
            .put(
                "utc",
                Instant.now().toString(),
            ).put("local", OffsetDateTime.now().toString())
            .put("time_zone", ZoneId.systemDefault().id)
            .toString()

    fun parseTime(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    fun waitMillis(
        name: String,
        args: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val duration =
            if (name == "wait_for") {
                args.getLong("seconds").also {
                    require(it in 1..300) { "Wait for 1–300 seconds. Use schedule_task for longer delays." }
                } *
                    1000
            } else {
                (parseTime(args.getString("until")) - now).coerceAtLeast(0)
            }
        require(
            duration <= 300_000,
        ) { "wait_until supports the next five minutes. Use schedule_task for longer delays." }
        return duration
    }

    fun due(
        args: JSONObject,
        now: Long = System.currentTimeMillis(),
        fallback: Long? = null,
    ): Long {
        require(!(args.has("run_at") && args.has("delay_seconds"))) { "Use run_at or delay_seconds, not both." }
        val due =
            when {
                args.has("run_at") -> {
                    parseTime(args.getString("run_at"))
                }

                args.has("delay_seconds") -> {
                    now + args.getLong("delay_seconds").also { require(it in 1..31_536_000) } * 1000
                }

                else -> {
                    fallback ?: error("Provide run_at or delay_seconds.")
                }
            }

        require(due > now && due - now <= 31_536_000_000L) { "Schedule a future time within one year." }
        return due
    }

    fun schema(): List<JSONObject> {
        val timing =
            arrayOf(
                "run_at" to stringSchema("ISO 8601 time with explicit offset; alternative to delay_seconds."),
                "delay_seconds" to integerSchema("Delay before running; alternative to run_at.", 1, 31536000),
                "repeat_interval_seconds" to
                    integerSchema(
                        "Optional fixed repeat interval, minimum 15 minutes. Zero disables repeats.",
                        0,
                        2592000,
                    ),
                "max_runs" to
                    integerSchema("Total future runs, default one; recurring defaults to 10. Maximum 100.", 1, 100),
            )
        return listOf(
            toolSchema(
                "get_time",
                "Read the device's current UTC/local time and time zone before interpreting dates.",
                emptyList(),
            ),
            toolSchema(
                "wait_for",
                "Pause the current agent turn without blocking UI, then continue. Stop cancels the wait. At most five minutes; use schedule_task for longer delays. An active wait cannot survive process termination.",
                listOf("seconds"),
                "seconds" to integerSchema("Seconds to wait.", 1, 300),
            ),
            toolSchema(
                "wait_until",
                "Pause this turn until an offset-qualified timestamp within the next five minutes. Past times return immediately. Stop cancels the wait; use schedule_task for longer delays.",
                listOf("until"),
                "until" to stringSchema("ISO 8601 timestamp with offset."),
            ),
            toolSchema(
                "schedule_task",
                "Schedule a durable future follow-up in this task conversation, using its project and the current model/provider. Requires run_at OR delay_seconds. Optional bounded repeats. Android may delay execution; never promise exact timing. Current approval rules still apply when it runs.",
                listOf("title", "prompt"),
                "title" to stringSchema("Short schedule label, maximum 80 characters."),
                "prompt" to stringSchema("Follow-up instruction for the agent, maximum 2,000 characters."),
                *timing,
            ),
            toolSchema(
                "list_scheduled_tasks",
                "List saved schedules and outcomes, 20 at a time. Does not expose API keys.",
                emptyList(),
                "offset" to integerSchema("Pagination offset; default zero.", 0, 100),
            ),
            toolSchema(
                "update_scheduled_task",
                "Edit or reschedule a non-running schedule by ID. Uses the current connection. Completed, blocked, or interrupted schedules need a new future time.",
                listOf("id"),
                "id" to stringSchema("Schedule ID."),
                "title" to stringSchema("Optional replacement label."),
                "prompt" to stringSchema("Optional replacement instruction."),
                *timing,
            ),
            toolSchema(
                "cancel_scheduled_task",
                "Cancel future runs by ID. Cancelling a running schedule also stops that agent turn; already-started Termux processes may continue.",
                listOf("id"),
                "id" to stringSchema("Schedule ID."),
            ),
        )
    }
}

internal data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val conversationId: String,
    val runAt: Long,
    val connection: String,
    val intervalSeconds: Long = 0,
    val remaining: Int = 1,
    val completed: Int = 0,
    val revision: Int = 0,
    val state: String = "Scheduled",
    val error: String = "",
) {
    fun json(public: Boolean = false) =
        JSONObject()
            .put(
                "id",
                id,
            ).put("title", title)
            .put(if (public) "instruction_preview" else "prompt", if (public) prompt.take(160) else prompt)
            .put(
                "conversation_id",
                conversationId,
            ).put("run_at", Instant.ofEpochMilli(runAt).toString())
            .put("interval_seconds", intervalSeconds)
            .put(
                "remaining_runs",
                remaining,
            ).put("completed_runs", completed)
            .put("revision", revision)
            .put("state", state)
            .put("error", error.take(300))
            .apply { if (!public) put("connection", JSONObject(connection)) }

    companion object {
        fun fromJson(o: JSONObject) =
            ScheduledTask(
                o.getString("id"),
                o.getString("title"),
                o.getString("prompt"),
                o.getString("conversation_id"),
                Instant.parse(o.getString("run_at")).toEpochMilli(),
                o.getJSONObject("connection").toString(),
                o.optLong("interval_seconds"),
                o.optInt("remaining_runs", 1),
                o.optInt("completed_runs"),
                o.optInt("revision"),
                o.optString("state", "Scheduled"),
                o.optString("error"),
            )
    }
}

/** Main-thread store. WorkManager owns durable timing; the store guards stale/replayed jobs. */
internal class TaskSchedules(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
    var records by mutableStateOf(
        runCatching {
            val a = JSONArray(prefs.getString("records", "[]"))
            (0 until a.length()).map { ScheduledTask.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()),
    )
        private set
    private val manager get() = WorkManager.getInstance(context)

    private fun save(record: ScheduledTask): ScheduledTask {
        records =
            (listOf(record) + records.filterNot { it.id == record.id }).let { all ->
                all.filter { it.state in setOf("Scheduled", "Running") } +
                    all.filterNot { it.state in setOf("Scheduled", "Running") }.take(32)
            }
        check(
            prefs
                .edit()
                .putString(
                    "records",
                    JSONArray()
                        .apply {
                            records.forEach { put(it.json()) }
                        }.toString(),
                ).commit(),
        ) { "Could not save the schedule." }
        return record
    }

    private fun enqueue(record: ScheduledTask) {
        val request =
            OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
                .setInputData(
                    workDataOf(
                        "schedule_id" to record.id,
                        "revision" to record.revision,
                    ),
                ).setInitialDelay((record.runAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag("schedule:${record.id}")
                .build()
        manager.enqueueUniqueWork("schedule:${record.id}:${record.revision}", ExistingWorkPolicy.KEEP, request)
    }

    private fun validated(record: ScheduledTask): ScheduledTask {
        require(
            record.title.isNotBlank() && record.title.length <= 80 && record.prompt.isNotBlank() && record.prompt.length <= 2000,
        ) {
            "Use a label up to 80 characters and an instruction up to 2,000 characters."
        }
        require(record.intervalSeconds == 0L || record.intervalSeconds in 900..2592000) {
            "Repeats must be 15 minutes to 30 days apart."
        }
        require(record.remaining in 1..100 && (record.intervalSeconds > 0 || record.remaining == 1)) {
            "Use 1–100 runs; multiple runs require a repeat interval."
        }
        return record
    }

    fun create(
        args: JSONObject,
        conversationId: String,
        connection: WorkConnection,
    ): ScheduledTask {
        require(
            records.count {
                it.state in setOf("Scheduled", "Running")
            } < 24,
        ) { "Cancel a schedule first; at most 24 can be active." }
        val interval = args.optLong("repeat_interval_seconds", 0)
        val record =
            validated(
                ScheduledTask(
                    UUID.randomUUID().toString(),
                    args.getString("title"),
                    args.getString("prompt"),
                    conversationId,
                    ScheduleTools.due(args),
                    connection.saved().toString(),
                    interval,
                    args.optInt("max_runs", if (interval > 0) 10 else 1),
                ),
            )
        save(record)
        enqueue(record)
        return record
    }

    fun update(
        args: JSONObject,
        connection: WorkConnection,
    ): ScheduledTask {
        val old = records.firstOrNull { it.id == args.getString("id") } ?: error("Schedule not found.")
        require(old.state != "Running") { "Cancel a running schedule before changing it." }
        val interval = args.optLong("repeat_interval_seconds", old.intervalSeconds)
        val updated =
            validated(
                old.copy(
                    title = args.optString("title", old.title),
                    prompt = args.optString("prompt", old.prompt),
                    runAt = ScheduleTools.due(args, fallback = old.runAt),
                    intervalSeconds = interval,
                    remaining =
                        args.optInt(
                            "max_runs",
                            if (interval == 0L) {
                                1
                            } else {
                                old.remaining.coerceAtLeast(1)
                            },
                        ),
                    connection = connection.saved().toString(),
                    revision = old.revision + 1,
                    state = "Scheduled",
                    error = "",
                ),
            )
        require(
            old.state == "Scheduled" || records.count {
                it.state in setOf("Scheduled", "Running")
            } < 24,
        ) { "At most 24 schedules can be active." }
        save(updated)
        manager.cancelAllWorkByTag("schedule:${old.id}")
        enqueue(updated)
        return updated
    }

    fun cancel(id: String): ScheduledTask {
        val old = records.firstOrNull { it.id == id } ?: error("Schedule not found.")
        val result = save(old.copy(state = "Cancelled", revision = old.revision + 1))
        manager.cancelAllWorkByTag("schedule:$id")
        return result
    }

    fun delete(id: String) {
        val old = records.firstOrNull { it.id == id } ?: error("Schedule not found.")
        records = records.filterNot { it.id == id }
        prefs
            .edit()
            .putString(
                "records",
                org.json
                    .JSONArray()
                    .apply { records.forEach { put(it.json()) } }
                    .toString(),
            ).commit()
        manager.cancelAllWorkByTag("schedule:$id")
    }

    fun edit(
        id: String,
        title: String,
        prompt: String,
        runAt: Long,
    ): ScheduledTask {
        val old = records.firstOrNull { it.id == id } ?: error("Schedule not found.")
        require(old.state != "Running") { "Cancel a running schedule before changing it." }
        require(runAt > System.currentTimeMillis()) { "Schedule a future time." }
        val connection = old.connection
        val updated =
            validated(
                old.copy(
                    title = title,
                    prompt = prompt,
                    runAt = runAt,
                    connection = connection,
                    revision = old.revision + 1,
                    state = "Scheduled",
                    error = "",
                ),
            )
        require(
            old.state == "Scheduled" || records.count {
                it.state in setOf("Scheduled", "Running")
            } < 24,
        ) { "At most 24 schedules can be active." }
        save(updated)
        manager.cancelAllWorkByTag("schedule:${old.id}")
        enqueue(updated)
        return updated
    }

    fun mark(
        record: ScheduledTask,
        state: String,
        error: String = "",
    ) = save(record.copy(state = state, error = error))

    fun finish(
        record: ScheduledTask,
        outcome: String,
        error: String = "",
    ) {
        val current = records.firstOrNull { it.id == record.id } ?: return
        if (current.revision != record.revision || current.state != "Running") return
        if (outcome != "Complete") {
            save(
                current.copy(
                    state =
                        if (outcome == "Cancelled") {
                            "Cancelled"
                        } else {
                            "Needs attention"
                        },
                    error = error.ifBlank { outcome },
                ),
            )
            return
        }
        val left = current.remaining - 1
        if (left > 0 && current.intervalSeconds > 0) {
            val interval = current.intervalSeconds * 1000
            val next =
                current.runAt +
                    ((System.currentTimeMillis() - current.runAt).coerceAtLeast(0) / interval + 1) * interval
            val repeat =
                current.copy(
                    runAt = next,
                    remaining = left,
                    completed = current.completed + 1,
                    revision = current.revision + 1,
                    state = "Scheduled",
                    error = "",
                )
            save(repeat)
            enqueue(repeat)
        } else {
            save(current.copy(state = "Complete", remaining = 0, completed = current.completed + 1, error = ""))
        }
    }
}

class ScheduledAgentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HydraApplication
        val scheduleId = inputData.getString("schedule_id") ?: return Result.failure()
        val revision = inputData.getInt("revision", -1)
        var ownsRun = false
        var task: ScheduledTask? = null
        var work: WorkModel? = null
        var completion: Deferred<WorkRunOutcome>? = null
        try {
            val ready =
                withContext(Dispatchers.Main.immediate) {
                    val record =
                        app.schedules.records.firstOrNull {
                            it.id == scheduleId && it.revision == revision
                        } ?: return@withContext false
                    task = record
                    if (record.state == "Running") {
                        app.schedules.mark(
                            record,
                            "Interrupted",
                            "The previous run was interrupted. Review its conversation before rescheduling.",
                        )
                        return@withContext false
                    }
                    record.state == "Scheduled"
                }
            if (!ready) return Result.success()
            val record = task!!
            if (record.runAt > System.currentTimeMillis()) return Result.retry()
            setForeground(foreground(record))
            val started =
                withContext(Dispatchers.Main.immediate) {
                    if (app.schedules.records.none {
                            it.id == record.id && it.revision == record.revision && it.state == "Scheduled"
                        }
                    ) {
                        return@withContext false
                    }
                    val model = app.models[WorkModel::class.java]
                    work = model
                    if (model.running || model.attaching || model.draftAttachments.isNotEmpty()) {
                        return@withContext false
                    }
                    val conversation =
                        model.records.firstOrNull { it.id == record.conversationId } ?: error(
                            "The task conversation was deleted.",
                        )
                    val connection = WorkConnection.restore(app, JSONObject(record.connection))
                    check(model.viewRecord(conversation)) { "Could not open this task conversation." }
                    // Claim before executing: a restarted worker must not replay tools already performed.
                    app.schedules.mark(record, "Running")
                    ownsRun = true
                    check(
                        model.followUp(record.prompt, connection, managedForeground = true),
                    ) { model.error ?: "Could not start the scheduled task." }
                    completion = model.runCompletion
                    true
                }
            if (!started) return Result.retry()
            val outcome = withTimeout(15 * 60 * 1000L) { completion!!.await() }
            withContext(Dispatchers.Main.immediate) { app.schedules.finish(record, outcome.status, outcome.error) }
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (ownsRun && work?.running == true && work?.runCompletion === completion) work?.stop()
                task?.let { record ->
                    app.schedules.records
                        .firstOrNull {
                            it.id == record.id && it.revision == record.revision && it.state == "Running"
                        }?.let {
                            app.schedules.mark(
                                it,
                                "Interrupted",
                                "Run interrupted or exceeded 15 minutes. Review the conversation before rescheduling.",
                            )
                        }
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (ownsRun && work?.running == true && work?.runCompletion === completion) work?.stop()
                task?.let { record ->
                    app.schedules.records
                        .firstOrNull { it.id == record.id && it.revision == record.revision && it.state != "Cancelled" }
                        ?.let {
                            app.schedules.mark(
                                it,
                                "Needs attention",
                                e.message ?: "Scheduled task failed.",
                            )
                        }
                }
            }
            return Result.success()
        }
    }

    private fun foreground(record: ScheduledTask): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("scheduled-work", "Scheduled tasks", NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                applicationContext,
                record.id.hashCode(),
                Intent(
                    applicationContext,
                    WorkChatActivity::class.java,
                ).putExtra("conversationId", record.conversationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification
                .Builder(
                    applicationContext,
                    "scheduled-work",
                ).setSmallIcon(R.drawable.hydra_monochrome)
                .setContentTitle(record.title)
                .setContentText(
                    "Hydra is running a scheduled task. Open to review, approve, or stop.",
                ).setContentIntent(open)
                .setOngoing(true)
                .build()
        val notificationId = 10000 + (record.id.hashCode() and 0x7fff)
        return if (android.os.Build.VERSION.SDK_INT >=
            29
        ) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
