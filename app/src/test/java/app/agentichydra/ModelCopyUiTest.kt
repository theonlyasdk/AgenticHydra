package app.agentichydra

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCopyUiTest:AppUiTestBase() {
    @Test fun longPressCopiesEitherNameWithoutSelectingTheModel() {
        val vm=(controller.get().application as HydraApplication).models[HydraModel::class.java]
        val clipboard=controller.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("alpha") and hasText("test/alpha")).performTouchInput{longClick()}
        compose.onNodeWithText("Copy model name").assertIsDisplayed()
        screenshot("model-copy-dialog")
        compose.onNodeWithText("Copy full model name").performClick()
        assertEquals("test/alpha",clipboard.primaryClip?.getItemAt(0)?.text.toString())
        assertEquals("",vm.model)
        compose.onNodeWithTag("models-header").assertIsDisplayed()
        compose.onNode(hasText("alpha") and hasText("test/alpha")).performTouchInput{longClick()}
        compose.onNodeWithText("Copy model name").performClick()
        assertEquals("alpha",clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }
}
