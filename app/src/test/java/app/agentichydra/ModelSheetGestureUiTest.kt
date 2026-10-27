package app.agentichydra

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ModelSheetGestureUiTest : AppUiTestBase() {
    @Test fun scrollingAndOverscrollStayInTheListWhileHeaderDragDismisses() {
        val catalog=JSONObject().put("data",JSONArray().apply {
            repeat(60){put(JSONObject().put("id","fixture/model-${it.toString().padStart(2,'0')}").put("name","Model ${it.toString().padStart(2,'0')}"))}
        }).toString()
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(catalog)
        }
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000){!(controller.get().application as HydraApplication).models[HydraModel::class.java].discovering}
        compose.onNodeWithContentDescription("Refresh models").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Model 00").fetchSemanticsNodes().isNotEmpty()}

        // Pulling down at the first row used to dismiss the sheet via nested overscroll.
        compose.onNodeWithTag("model-list").performTouchInput { swipeDown(durationMillis=500) }
        compose.onNodeWithTag("models-header").assertIsDisplayed()
        compose.onNodeWithTag("model-list").performTouchInput { swipeUp(durationMillis=500) }
        compose.onNodeWithText("Model 00").assertIsNotDisplayed()
        repeat(2){
            compose.onNodeWithTag("model-list").performTouchInput { swipeDown(durationMillis=500) }
            compose.onNodeWithTag("models-header").assertIsDisplayed()
        }
        screenshot("models-no-handle")
        compose.onNodeWithTag("models-header").performTouchInput {
            swipe(start=center,end=Offset(center.x,center.y+1200f),durationMillis=250)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("models-header").assertDoesNotExist()
    }
}
