package app.agentichydra

import android.content.Intent
import androidx.compose.ui.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicBoolean

class WorkCancellationUiTest : AppUiTestBase() {
    @Test
    fun stoppingARequestShowsOneToastAndCancelledStatusWithoutAnErrorCardOrNotice() {
        val application = controller.get().application as HydraApplication
        val chat = application.models[HydraModel::class.java]
        val work = application.models[WorkModel::class.java]
        chat.chooseModel("test/alpha")
        val requestStarted = AtomicBoolean()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.endsWith("/models") == true) {
                        return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
                    }
                    requestStarted.set(true)
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
        controller.pause().stop()
        val activity =
            Robolectric
                .buildActivity(
                    WorkChatActivity::class.java,
                    Intent(controller.get(), WorkChatActivity::class.java).putExtra("newConversation", true),
                ).setup()
        try {
            compose.onNodeWithText("Reply to Hydra").performTextInput("Start a request")
            compose.onNodeWithContentDescription("Send task message").performClick()
            compose.waitUntil(15000) { requestStarted.get() }
            compose.onNodeWithContentDescription("Stop task").performClick()
            compose.waitUntil(15000) {
                ShadowLooper.idleMainLooper()
                !work.running
            }
            compose.waitForIdle()
            assertEquals("Cancelled", work.status)
            assertNull(work.error)
            assertFalse(work.messages.any { it.role == "notice" && isCancellationNotice(it.text) })
            assertEquals("Task cancelled.", ShadowToast.getTextOfLatestToast())
            compose.onAllNodesWithText("Cancelled", substring = true).assertCountEquals(1)
            compose.onNodeWithText("Performed 0 actions", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Dismiss error").assertDoesNotExist()
            assertEquals("Cancelled", WorkModel(application).records.single().status)
            screenshot("work-cancelled-status")
        } finally {
            activity.pause().stop().destroy()
            controller
                .restart()
                .start()
                .resume()
                .visible()
        }
    }
}
