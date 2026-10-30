package app.agentichydra

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class ScheduledWorkerUiTest : AppUiTestBase() {
    @Test
    fun dueWorkerContinuesSavedConversationAndCompletesSchedule() {
        val app = controller.get().application as HydraApplication
        val work = app.models[WorkModel::class.java]
        val chat = app.models[HydraModel::class.java]
        chat.chooseModel("test/alpha")
        val calls = AtomicInteger()
        var payload: JSONObject? = null
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.endsWith("/models") == true) {
                        return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
                    }
                    if (calls.incrementAndGet() == 2) payload = JSONObject(request.body.readUtf8())
                    return MockResponse().setBody(
                        """{"choices":[{"message":{"role":"assistant","content":"The check is complete."}}]}""",
                    )
                }
            }
        compose.runOnIdle { assertTrue(work.followUp("Initial context.", chat)) }
        compose.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            !work.running
        }
        val record =
            ScheduledTask(
                "due-test",
                "Follow up",
                "Check the result again.",
                work.activeId,
                System.currentTimeMillis() - 1000,
                WorkConnection.from(chat).saved().toString(),
            )
        app
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit()
            .putString("records", JSONArray().put(record.json()).toString())
            .commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        val worker =
            TestListenableWorkerBuilder<ScheduledAgentWorker>(app)
                .setInputData(
                    workDataOf(
                        "schedule_id" to record.id,
                        "revision" to record.revision,
                    ),
                ).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var complete = false
        try {
            compose.runOnIdle {
                scope.launch {
                    worker.doWork()
                    complete = true
                }
            }
            compose.waitUntil(15000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                complete
            }
            assertEquals(
                "Complete",
                app.schedules.records
                    .single()
                    .state,
            )
            assertEquals(
                1,
                app.schedules.records
                    .single()
                    .completed,
            )
            assertEquals(2, work.messages.count { it.role == "user" })
            val messages = payload!!.getJSONArray("messages")
            assertEquals("Check the result again.", messages.getJSONObject(messages.length() - 1).getString("content"))
            compose.onNodeWithText("Work", substring = false).performClick()
            compose.onNodeWithContentDescription("Scheduled tasks").performClick()
            compose.onNodeWithText("Follow up", substring = false).assertIsDisplayed()
            screenshot("scheduled-tasks")
        } finally {
            scope.cancel()
        }
    }
}
