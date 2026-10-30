package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [34], qualifiers = "w320dp-h915dp-420dpi")
class AgentQuestionWizardUiTest : AppUiTestBase() {
    @Test
    fun wizardKeepsAnswersWhileMovingBackAndReturnsAllAnswerTypes() {
        val request =
            AgentQuestionRequest(
                listOf(
                    AgentQuestion(
                        "scope",
                        "Choose a scope",
                        "Where should I start?",
                        QuestionType.SINGLE,
                        listOf("Chat", "Work"),
                    ),
                    AgentQuestion(
                        "features",
                        "Choose features",
                        "Which tools matter?",
                        QuestionType.MULTIPLE,
                        listOf("Search", "Files"),
                    ),
                    AgentQuestion("notes", "Add details", "What else should I know?", QuestionType.TEXT, emptyList()),
                    AgentQuestion(
                        "tests",
                        "Run checks",
                        "Should I run the checks?",
                        QuestionType.YES_NO,
                        listOf("Yes", "No"),
                    ),
                ),
            )
        var result by mutableStateOf<String?>(null)
        controller.get().setContent {
            HydraTheme(darkTheme = false, dynamicColor = false) {
                if (result == null) {
                    AgentQuestionSheet(request, { result = it }, { fail("Unexpected dismissal") })
                }
            }
        }
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Work", substring = false).performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Search", substring = false).performClick()
        compose.onNodeWithText("Files", substring = false).performClick()
        screenshot("question-wizard-choices")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Your answer").performTextInput("Keep the changes focused.")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.onNodeWithText("Keep the changes focused.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("No", substring = false).performClick()
        screenshot("question-wizard-final")
        compose.onNodeWithText("Send answers").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil { result != null }
        val answers = JSONObject(result!!).getJSONArray("answers")
        assertEquals(4, answers.length())
        assertEquals(2, answers.getJSONObject(1).getJSONArray("selected").length())
        assertEquals("Keep the changes focused.", answers.getJSONObject(2).getString("text"))
        assertEquals("No", answers.getJSONObject(3).getJSONArray("selected").getString(0))
    }
}
