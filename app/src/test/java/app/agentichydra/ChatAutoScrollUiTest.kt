package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test

class ChatAutoScrollUiTest : AppUiTestBase() {
    @Test
    fun aTallStreamingResponseReleasesOnScrollAndFollowsAgainOnlyAtTheBottom() {
        val response =
            Message(
                "assistant",
                (1..65).joinToString("\n\n") {
                    "Paragraph $it. A streaming answer can grow much taller than the screen."
                },
                createdAt = 0,
            )
        var messages by mutableStateOf(listOf(Message("user", "Explain the project", createdAt = 0), response))
        lateinit var scroll: LazyListState
        controller.get().setContent {
            scroll = rememberLazyListState()
            HydraTheme {
                Surface {
                    ChatTimeline(
                        ChatTimelineUiState("scroll-test", messages, response.id),
                        {},
                        {},
                        Modifier.fillMaxSize(),
                        scroll,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        assertFalse("Initial following must reach the bottom", scroll.canScrollForward)
        compose.onNodeWithTag("conversation-end").assertIsDisplayed()

        compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        assertTrue("Scrolling toward older text must release following", scroll.canScrollForward)
        compose.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        val beforeIndex = scroll.firstVisibleItemIndex
        val beforeOffset = scroll.firstVisibleItemScrollOffset
        compose.runOnIdle {
            messages =
                messages.dropLast(
                    1,
                ) +
                messages.last().copy(
                    text =
                        messages.last().text + "\n\nMore streamed content.\n\nAnother paragraph arrives.",
                )
        }
        compose.waitForIdle()
        assertEquals(beforeIndex, scroll.firstVisibleItemIndex)
        assertEquals("New text must not pull the reader away", beforeOffset, scroll.firstVisibleItemScrollOffset)

        repeat(5) {
            if (scroll.canScrollForward) {
                compose
                    .onNodeWithTag(
                        "chat-timeline",
                    ).performTouchInput { swipeUp(durationMillis = 500) }
            }
        }
        compose.waitForIdle()
        assertFalse("Returning to the bottom resumes following", scroll.canScrollForward)
        compose.runOnIdle {
            messages =
                messages.dropLast(
                    1,
                ) +
                messages.last().copy(
                    text =
                        messages.last().text + "\n\nA final paragraph expands the response once more.",
                )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("conversation-end").assertIsDisplayed()
        assertFalse(scroll.canScrollForward)
        compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        assertTrue(scroll.canScrollForward)
        compose.runOnIdle {
            messages =
                messages + Message("user", "A new follow-up", createdAt = 0) +
                Message("assistant", "A new response", createdAt = 0)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        assertFalse("Sending a new message resumes following even after reading older content", scroll.canScrollForward)
    }
}
