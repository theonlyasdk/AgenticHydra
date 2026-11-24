package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedWorkUiTest : AppUiTestBase() {
    @Test
    fun interruptedTaskCanContinueAfterClosingSearch() {
        var resumed = 0
        controller.get().setContent {
            HydraTheme {
                WorkChatScreen(
                    WorkChatUiState(
                        title = "Review project",
                        project = "Hydra",
                        messages = listOf(Message("user", "Review the project")),
                        status = "Interrupted",
                        hasProject = true,
                    ),
                    draft = "",
                    onDraft = {},
                    actions = WorkChatActions(onResume = { resumed++ }),
                ) { Text("Project model") }
            }
        }
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search in conversation").performClick()
        compose.onNodeWithTag("chat-find-bar").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNode(
            hasContentDescription("Close search") and hasAnyAncestor(hasTestTag("chat-find-bar")),
        ).performClick()
        compose.onNodeWithTag("chat-find-bar").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, resumed)
    }
}
