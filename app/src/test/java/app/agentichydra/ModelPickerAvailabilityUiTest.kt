package app.agentichydra

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ModelPickerAvailabilityUiTest : AppUiTestBase() {
    @Test fun emptyCatalogRequiresAManualIdAndMissingCredentialsOfferConnect() {
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody("""{"data":[]}""")
        }
        compose.runOnIdle{(controller.get().application as HydraApplication).models[HydraModel::class.java].refreshModels()}
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("No models loaded").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Search models").assertDoesNotExist()
        compose.onNodeWithText("Done").assertIsNotEnabled()
        compose.onNodeWithText("Enter model ID").performClick()
        compose.onNodeWithText("Model ID",substring=false).performTextInput("custom/manual-id")
        compose.onNodeWithText("Done").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(1000)
        val model=(controller.get().application as HydraApplication).models[HydraModel::class.java]
        assertEquals("custom/manual-id",model.model)
        assertTrue(model.customModelSelected)

        compose.onNodeWithText("manual-id").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("No models loaded").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("No models loaded").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onNodeWithText("Gemini",substring=false).performClick()
        compose.onNodeWithText("Add a Gemini API key").assertIsDisplayed()
        compose.onNodeWithText("No models loaded").assertDoesNotExist()
        compose.onNodeWithText("Search models").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        screenshot("models-connect-required")
        compose.onNodeWithText("Connect",substring=false).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Connections",substring=false).assertIsDisplayed()
    }
}
