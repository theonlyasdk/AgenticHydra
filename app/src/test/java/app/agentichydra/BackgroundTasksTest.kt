package app.agentichydra

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun backgroundSse(vararg events: JSONObject): MockResponse {
    val body =
        buildString {
            events.forEach { append("data: ").append(it.toString()).append("\n\n") }
            append("data: [DONE]\n\n")
        }
    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}

private fun backgroundText(text: String) =
    JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))

private fun backgroundTool(
    id: String,
    name: String,
    arguments: String,
) = JSONObject().put(
    "choices",
    JSONArray().put(
        JSONObject().put(
            "delta",
            JSONObject().put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("id", id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", arguments)),
                ),
            ),
        ),
    ),
)

/**
 * Background read-only tasks: the engine records steps and status, mirrors each
 * run into a WorkRecord, caps concurrency, supports stopping, and recovers
 * stale Running records as Interrupted after process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundTasksTest {
    private lateinit var server: MockWebServer
    private lateinit var work: WorkModel
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("work", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        work = (context as HydraApplication).models[WorkModel::class.java]
    }

    @After
    fun tearDown() {
        work.engine.active.forEach { work.engine.stop(it.id) }
        pump { work.engine.active.isEmpty() }
        (context as HydraApplication).viewModelStore.clear()
        // Responses use multi-second body delays; after the clients disconnect, MockWebServer's
        // queue may still hold their scheduled tasks, so a clean shutdown is not guaranteed.
        runCatching { server.shutdown() }
    }

    private fun pump(
        timeoutMs: Long = 15000,
        until: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!until() && System.currentTimeMillis() - start < timeoutMs) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue(until())
    }

    private fun connection(): WorkConnection {
        val chat = (context as HydraApplication).models[HydraModel::class.java]
        chat.configure(Provider.CUSTOM, server.url("/v1").toString().trimEnd('/'), "", "")
        chat.chooseModel("test/alpha")
        return WorkConnection.from(chat)
    }

    @Test
    fun backgroundTaskRunsReadOnlyToolsAndMirrorsItsRecord() {
        val calls = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.endsWith("/models") == true) {
                        return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
                    }
                    return when (calls.incrementAndGet()) {
                        1 -> {
                            backgroundSse(
                                backgroundTool(
                                    "call-read",
                                    "read_file",
                                    JSONObject().put("path", "docs/plan.md").toString(),
                                ),
                            )
                        }

                        2 -> {
                            backgroundSse(backgroundText("No project is selected, so I cannot read files."))
                        }

                        else -> {
                            MockResponse().setResponseCode(500).setBody("{}")
                        }
                    }
                }
            }
        val id =
            work.engine.start(
                "Docs research",
                "Read the project plan.",
                connection(),
                null,
                "No project selected",
                "",
            )
        pump { !work.engine.isRunning(id) }
        // The engine kept a finished status report for read_task_output.
        val report = JSONObject(work.engine.readOutput(id).output)
        assertEquals("Complete", report.getString("status"))
        assertEquals("No project is selected, so I cannot read files.", report.getString("summary"))
        val steps = report.getJSONArray("steps")
        assertEquals(1, steps.length())
        assertEquals("read_file", steps.getJSONObject(0).getString("tool"))
        assertEquals("Failed", steps.getJSONObject(0).getString("state"))
        assertTrue(steps.getJSONObject(0).getString("output").contains("Select a project folder"))
        // The run is mirrored into task history and can be opened like any conversation.
        val record = work.records.first { it.id == id }
        assertEquals("Complete", record.status)
        assertTrue(record.messages.any { it.role == "user" && it.text.contains("Read the project plan") })
        assertTrue(record.messages.any { it.role == "tool" })
        assertTrue(record.messages.any { it.role == "assistant" && it.text.contains("cannot read files") })
        // Agent turns carried the read-only tool schema with tool_choice auto.
        val chatRequests =
            (0 until server.requestCount)
                .mapNotNull { server.takeRequest(10, TimeUnit.MILLISECONDS) }
        val agentRequests = chatRequests.filter { it.path?.endsWith("/chat/completions") == true }
        assertEquals(2, agentRequests.size)
        val firstBody = agentRequests.first().body.readUtf8()
        assertTrue(firstBody.contains("tool_choice"))
        val toolNames =
            JSONObject(firstBody).getJSONArray("tools").let { schema ->
                (0 until schema.length()).map { schema.getJSONObject(it).getJSONObject("function").getString("name") }
            }
        assertFalse(toolNames.contains("write_file"))
        assertFalse(toolNames.contains("apply_diff"))
        assertFalse(toolNames.contains("read_image"))
        assertFalse(toolNames.contains("run_command"))
        assertFalse(toolNames.contains("ask_user"))
        assertTrue(toolNames.containsAll(listOf("web_search", "read_file", "get_time")))
        val secondBody = agentRequests.last().body.readUtf8()
        val toolMessage =
            (0 until JSONObject(secondBody).getJSONArray("messages").length())
                .map { JSONObject(secondBody).getJSONArray("messages").getJSONObject(it) }
                .first { it.optString("role") == "tool" }
        assertEquals("call-read", toolMessage.getString("tool_call_id"))
    }

    @Test
    fun startTaskCapsConcurrencyAndStopRecordsTheOutcome() {
        // The answer is delayed, so every started task stays Running while queued.
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.endsWith("/models") == true) {
                        return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
                    }
                    return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: [DONE]\n\n")
                        .setBodyDelay(30, TimeUnit.SECONDS)
                }
            }
        val ids =
            (1..3).map {
                work.engine.start("Task $it", "Research topic $it.", connection(), null, "No project selected", "")
            }
        pump { work.engine.active.size == 3 }
        assertTrue(runCatching { work.engine.start("Fourth task", "Overflow.", connection(), null, "", "") }.isFailure)
        assertEquals("Running", JSONObject(work.engine.readOutput(ids[0]).output).getString("status"))
        work.engine.stop(ids[1])
        pump { JSONObject(work.engine.readOutput(ids[1]).output).getString("status") == "Stopped" }
        assertEquals("Stopped", work.records.first { it.id == ids[1] }.status)
        assertEquals("Running", work.records.first { it.id == ids[0] }.status)
        work.engine.stop(ids[0])
        work.engine.stop(ids[2])
        pump { work.engine.active.isEmpty() }
    }

    @Test
    fun staleRunningRecordsBecomeInterruptedOnStartup() {
        val seed =
            WorkRecord(
                "Long research",
                "Running",
                "partial",
                listOf(WorkStep("web_search", "{}", state = "Running")),
                emptyList(),
                messages = listOf(Message("user", "hi")),
            )
        context
            .getSharedPreferences("work", Context.MODE_PRIVATE)
            .edit()
            .putString("records", JSONArray().put(seed.json()).toString())
            .commit()
        val recovered = WorkModel(context as Application)
        val record = recovered.records.first { it.id == seed.id }
        assertEquals("Interrupted", record.status)
        assertEquals("Interrupted", record.steps.first().state)
    }
}
