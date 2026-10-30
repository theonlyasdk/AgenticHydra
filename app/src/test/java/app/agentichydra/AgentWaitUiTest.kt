package app.agentichydra

import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

class AgentWaitUiTest : AppUiTestBase() {
    @Test
    fun waitsDoNotBlockTheUiResumeInOrderAndStopCancelsLongWaits() {
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
                    val turn = calls.incrementAndGet()
                    val message =
                        if (turn == 2) {
                            payload = JSONObject(request.body.readUtf8())
                            JSONObject().put("role", "assistant").put("content", "The wait is complete.")
                        } else {
                            JSONObject().put("role", "assistant").put("content", "I’ll wait before continuing.").put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "wait-$turn")
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", "wait_for").put(
                                                "arguments",
                                                JSONObject()
                                                    .put(
                                                        "seconds",
                                                        if (turn == 1) {
                                                            1
                                                        } else {
                                                            300
                                                        },
                                                    ).toString(),
                                            ),
                                        ),
                                ),
                            )
                        }
                    return MockResponse().setBody(
                        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString(),
                    )
                }
            }
        compose.runOnIdle { assertTrue(work.followUp("Wait a second, then continue.", chat)) }
        compose.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            work.status == "Waiting"
        }
        compose.runOnIdle { assertTrue(work.running) }
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(2))
        compose.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            !work.running
        }
        val messages = payload!!.getJSONArray("messages")
        val result =
            JSONObject(
                (0 until messages.length())
                    .map {
                        messages.getJSONObject(
                            it,
                        )
                    }.first { it.optString("role") == "tool" }
                    .getString("content"),
            )
        assertEquals("wait_complete", result.getString("status"))
        assertTrue(result.getLong("elapsed_ms") >= 1000)
        compose.runOnIdle { assertTrue(work.followUp("Wait five minutes.", chat)) }
        compose.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            work.status == "Waiting"
        }
        compose.runOnIdle { work.stop() }
        compose.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            !work.running
        }
        assertEquals("Cancelled", work.status)
        assertEquals(3, calls.get())
    }
}
