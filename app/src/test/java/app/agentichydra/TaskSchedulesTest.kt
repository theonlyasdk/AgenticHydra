package app.agentichydra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskSchedulesTest {
    private lateinit var context: Context
    private lateinit var schedules: TaskSchedules
    private val connection =
        WorkConnection(
            Provider.OLLAMA,
            "http://localhost:11434/v1",
            "test",
            "private-test-key",
            ModelSpec("test"),
            "auto",
        )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        schedules = TaskSchedules(context)
    }

    private fun input() = JSONObject().put("title", "Check again").put("prompt", "Review the current result.").put("delay_seconds", 60)

    @Test
    fun schedulesPersistWithoutCredentialsAndCanBeUpdatedAndCancelled() {
        val original = schedules.create(input(), "conversation", connection)
        assertFalse(
            context
                .getSharedPreferences(
                    "schedules",
                    Context.MODE_PRIVATE,
                ).getString("records", "")!!
                .contains("private-test-key"),
        )
        assertEquals(original.id, TaskSchedules(context).records.single().id)
        val updated = schedules.update(JSONObject().put("id", original.id).put("delay_seconds", 120), connection)
        assertEquals(1, updated.revision)
        assertTrue(updated.runAt > original.runAt)
        assertFalse(updated.json(public = true).has("connection"))
        schedules.cancel(original.id)
        assertEquals("Cancelled", schedules.records.single().state)
        assertTrue(
            WorkManager.getInstance(context).getWorkInfosByTag("schedule:${original.id}").get().all {
                it.state ==
                    WorkInfo.State.CANCELLED
            },
        )
    }

    @Test
    fun repeatsAreBoundedAndStaleCompletionsCannotResurrectCancelledSchedules() {
        val original =
            schedules.create(
                input().put("repeat_interval_seconds", 900).put("max_runs", 3),
                "conversation",
                connection,
            )
        schedules.mark(original, "Running")
        schedules.finish(original, "Complete")
        val next = schedules.records.single()
        assertEquals("Scheduled", next.state)
        assertEquals(2, next.remaining)
        assertEquals(1, next.completed)
        assertEquals(1, next.revision)
        assertTrue(next.runAt > original.runAt)
        schedules.cancel(next.id)
        schedules.finish(next, "Complete")
        assertEquals("Cancelled", schedules.records.single().state)
        assertTrue(
            runCatching {
                schedules.create(input().put("repeat_interval_seconds", 30), "conversation", connection)
            }.isFailure,
        )
    }
}
