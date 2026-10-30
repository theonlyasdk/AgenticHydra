package app.agentichydra

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class HydraTitleAppearanceTest : AppUiTestBase() {
    @Test
    fun appearancePreferencesPersistDefaultAndAlternativeState() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)

        try {
            // Default state
            prefs.edit().clear().commit()
            compose.waitForIdle()
            val defaultState =
                AppearanceUiState(
                    showHydraTitle = prefs.getBoolean("show_hydra_title", true),
                )
            assertTrue("Default state must have showHydraTitle = true", defaultState.showHydraTitle)

            // Alternative state (hidden completely)
            prefs.edit().putBoolean("show_hydra_title", false).commit()
            compose.waitForIdle()
            val updatedState =
                AppearanceUiState(
                    showHydraTitle = prefs.getBoolean("show_hydra_title", true),
                )
            assertFalse("Toggled state must have showHydraTitle = false", updatedState.showHydraTitle)

            // Restored back to default
            prefs.edit().putBoolean("show_hydra_title", true).commit()
            compose.waitForIdle()
            val restoredState =
                AppearanceUiState(
                    showHydraTitle = prefs.getBoolean("show_hydra_title", true),
                )
            assertTrue("Restored state must have showHydraTitle = true", restoredState.showHydraTitle)
        } finally {
            prefs.edit().clear().commit()
            compose.waitForIdle()
        }
    }

    @Test
    fun appearanceMenuToggleRendersAndFiresCallback() {
        var titleState by mutableStateOf(true)
        controller.get().setContent {
            HydraTheme {
                AppearanceOptions(
                    state = AppearanceUiState(showHydraTitle = titleState),
                    supportsDynamicColor = false,
                    onThemeChange = {},
                    onDynamicColorChange = {},
                    onMarqueeChange = {},
                    onHydraTitleChange = { titleState = it },
                )
            }
        }

        // Verify label and default supporting text
        compose.onNodeWithText("Hydra title").assertIsDisplayed()
        compose.onNodeWithText("Show on final summary message").assertIsDisplayed()

        // Toggle to alternative state by clicking the item
        compose.onNodeWithText("Hydra title").performClick()
        compose.waitForIdle()
        assertFalse("Callback should have fired with false", titleState)

        // Recomposing with false shows alternative supporting text
        compose.onNodeWithText("Hidden on all messages").assertIsDisplayed()
    }

    @Test
    fun hydraTitleHiddenDuringThinkingAndStreaming() {
        val thinkingMessage =
            Message(
                role = "assistant",
                text = "",
                thought = "Thinking step by step...",
                finalResponse = false,
                id = "msg-thinking",
            )
        val streamingMessage =
            Message(
                role = "assistant",
                text = "Streaming in progress...",
                thought = "Thinking step by step...",
                finalResponse = false,
                id = "msg-streaming",
            )

        compose.mainClock.autoAdvance = false
        try {
            controller.get().setContent {
                HydraTheme {
                    ChatTimeline(
                        state =
                            ChatTimelineUiState(
                                conversationId = "conv-stream",
                                messages =
                                    listOf(
                                        Message("user", "Hello"),
                                        thinkingMessage,
                                        streamingMessage,
                                    ),
                                streamingResponseId = "msg-streaming",
                            ),
                        onCopy = {},
                        onShare = {},
                    )
                }
            }
            compose.mainClock.advanceTimeBy(100)
            // While streaming / thinking, "HYDRA" title must not be shown
            compose.onNodeWithText("HYDRA").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun hydraTitleHiddenForIntermediaryClarifyingLinesWhileTasksExecute() {
        val intermediary1 =
            Message(
                role = "assistant",
                text = "I'll inspect the project files.",
                finalResponse = false,
                id = "msg-inter-1",
            )
        val tool1 = Message(role = "tool", text = "file1.kt", id = "tool-1")
        val intermediary2 =
            Message(
                role = "assistant",
                text = "Now compiling the project.",
                finalResponse = false,
                id = "msg-inter-2",
            )
        val tool2 = Message(role = "tool", text = "build success", id = "tool-2")

        controller.get().setContent {
            HydraTheme {
                ChatTimeline(
                    state =
                        ChatTimelineUiState(
                            conversationId = "conv-tasks",
                            messages =
                                listOf(
                                    Message("user", "Build and fix"),
                                    intermediary1,
                                    tool1,
                                    intermediary2,
                                    tool2,
                                ),
                            streamingResponseId = null,
                        ),
                    onCopy = {},
                    onShare = {},
                )
            }
        }

        // Intermediary clarifying lines must NOT display "HYDRA"
        compose.onNodeWithText("HYDRA").assertDoesNotExist()
    }

    @Test
    fun hydraTitleAppearsOnFinalMessageOnceTasksComplete() {
        val intermediary =
            Message(
                role = "assistant",
                text = "Checking git status.",
                finalResponse = false,
                id = "msg-inter",
            )
        val tool = Message(role = "tool", text = "clean working tree", id = "tool-1")
        val finalMessage =
            Message(
                role = "assistant",
                text = "All tasks have finished successfully. The repository is up to date.",
                finalResponse = true,
                id = "msg-final",
            )

        controller.get().setContent {
            HydraTheme {
                ChatTimeline(
                    state =
                        ChatTimelineUiState(
                            conversationId = "conv-final",
                            messages =
                                listOf(
                                    Message("user", "Check repo"),
                                    intermediary,
                                    tool,
                                    finalMessage,
                                ),
                            streamingResponseId = null,
                        ),
                    onCopy = {},
                    onShare = {},
                )
            }
        }

        // "HYDRA" title appears exactly once on the final summary message
        compose.onAllNodesWithText("HYDRA").assertCountEquals(1)
        compose.onNodeWithText("HYDRA").assertIsDisplayed()
    }

    @Test
    fun hydraTitleCompletelyHiddenWhenToggleIsOff() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_hydra_title", false).commit()

        try {
            val intermediary =
                Message(
                    role = "assistant",
                    text = "Checking git status.",
                    finalResponse = false,
                    id = "msg-inter",
                )
            val tool = Message(role = "tool", text = "clean working tree", id = "tool-1")
            val finalMessage =
                Message(
                    role = "assistant",
                    text = "All tasks have finished successfully. The repository is up to date.",
                    finalResponse = true,
                    id = "msg-final",
                )

            controller.get().setContent {
                HydraTheme {
                    ChatTimeline(
                        state =
                            ChatTimelineUiState(
                                conversationId = "conv-hidden",
                                messages =
                                    listOf(
                                        Message("user", "Check repo"),
                                        intermediary,
                                        tool,
                                        finalMessage,
                                    ),
                                streamingResponseId = null,
                            ),
                        onCopy = {},
                        onShare = {},
                    )
                }
            }

            // When toggled to alternative state, "HYDRA" title is hidden on ALL messages
            compose.onNodeWithText("HYDRA").assertDoesNotExist()
        } finally {
            prefs.edit().putBoolean("show_hydra_title", true).commit()
            compose.waitForIdle()
        }
    }

    @Test
    fun workChatTimelineShowsHydraTitleOnFinalMessage() {
        val finalTaskMessage =
            Message(
                role = "assistant",
                text = "Task completed: Created the requested report.",
                finalResponse = true,
                id = "work-final",
            )

        controller.get().setContent {
            HydraTheme(appearance = AppearanceUiState(showHydraTitle = true)) {
                ChatTimeline(
                    state =
                        ChatTimelineUiState(
                            conversationId = "work-conv-final",
                            messages =
                                listOf(
                                    Message("user", "Write report"),
                                    finalTaskMessage,
                                ),
                            streamingResponseId = null,
                        ),
                    onCopy = {},
                    onShare = {},
                )
            }
        }

        // In default state: HYDRA appears on final message
        compose.onNodeWithText("HYDRA").assertIsDisplayed()
    }

    @Test
    fun workChatTimelineFollowsAppearanceSetting() {
        val finalTaskMessage =
            Message(
                role = "assistant",
                text = "Task completed: Created the requested report.",
                finalResponse = true,
                id = "work-final",
            )

        controller.get().setContent {
            HydraTheme(appearance = AppearanceUiState(showHydraTitle = false)) {
                ChatTimeline(
                    state =
                        ChatTimelineUiState(
                            conversationId = "work-conv-hidden",
                            messages =
                                listOf(
                                    Message("user", "Write report"),
                                    finalTaskMessage,
                                ),
                            streamingResponseId = null,
                        ),
                    onCopy = {},
                    onShare = {},
                )
            }
        }

        // When toggled off: HYDRA does not appear on any message
        compose.onNodeWithText("HYDRA").assertDoesNotExist()
    }
}
