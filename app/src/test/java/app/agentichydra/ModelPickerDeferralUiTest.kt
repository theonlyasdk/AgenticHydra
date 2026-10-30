package app.agentichydra

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Draft selections must never touch the live connection before Done. */
class ModelPickerDeferralUiTest : AppUiTestBase() {
    @Test
    fun tappingAModelOnlyDraftsTheSelectionUntilDone() {
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty() }
        val vm = (controller.get().application as HydraApplication).models[HydraModel::class.java]
        compose.onNodeWithText("alpha").performClick()
        compose.waitForIdle()
        assertEquals("", vm.model)
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("test/alpha", vm.model)
    }

    @Test
    fun providerSwitchWaitsForDoneAndDismissalDiscardsIt() {
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty() }
        val vm = (controller.get().application as HydraApplication).models[HydraModel::class.java]
        compose.onNodeWithText("Gemini", substring = false).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a Gemini API key").assertIsDisplayed()
        assertEquals(Provider.CUSTOM, vm.provider)
        compose.onNodeWithTag("models-header").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 1200f), durationMillis = 250)
        }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(Provider.CUSTOM, vm.provider)
    }
}

class ModelChangeMarkerUiTest : AppUiTestBase() {
    @Test
    fun pickerTapsDoNotDropModelChangeMarkersUntilDone() {
        val activity = controller.get()
        activity
            .getSharedPreferences("hydra", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "history",
                """[{"id":"marker-chat","title":"Marker fixture","messages":[{"role":"user","text":"Hello Hydra"}]}]""",
            ).commit()
        val model = HydraModel(activity.application)
        activity.setContent { HydraTheme { ChatScreen(model) } }
        compose.onNodeWithContentDescription("Open chat history").performClick()
        compose.onNodeWithText("Marker fixture").performClick()
        compose.waitForIdle()
        assertEquals(1, model.messages.size)
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("alpha").performClick()
        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()
        assertEquals(1, model.messages.size)
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("test/beta", model.model)
        assertEquals(2, model.messages.size)
        assertEquals("model", model.messages.last().role)
    }
}
