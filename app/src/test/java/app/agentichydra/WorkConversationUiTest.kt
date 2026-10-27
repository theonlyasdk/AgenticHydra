package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkConversationUiTest:AppUiTestBase() {
    @Test fun approvalIsInlineAndFollowUpRetainsTheSameConversation() {
        val application=controller.get().application as HydraApplication
        val chat=application.models[HydraModel::class.java]
        val work=application.models[WorkModel::class.java]
        chat.chooseModel("test/alpha")
        val count=AtomicInteger()
        var followUpPayload:JSONObject?=null
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path?.endsWith("/models")==true)return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
                val payload=JSONObject(request.body.readUtf8())
                return when(count.incrementAndGet()) {
                    1->MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"I’ll check the project first.","tool_calls":[{"id":"call-one","type":"function","function":{"name":"run_command","arguments":"{\"command\":\"git status\"}"}}]}}]}""")
                    2->MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"I did not run the declined command."}}]}""")
                    else->{followUpPayload=payload;MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"We can continue without running commands."}}]}""")}
                }
            }
        }
        controller.get().setContent {
            var draft by remember{mutableStateOf("")}
            HydraTheme {
                WorkChatScreen(WorkChatUiState(work.task.ifBlank{"New task"},work.projectName,work.messages,work.steps,work.pendingApproval?.id,work.running,work.status,work.error,work.activeId),draft,{draft=it},
                    WorkChatActions(onSend={if(work.followUp(draft,chat))draft=""},onApprove=work::approveStep,onStop=work::stop)){Text("alpha")}
            }
        }
        compose.onNodeWithText("Reply to Hydra").performTextInput("Inspect the project")
        compose.onNodeWithContentDescription("Send task message").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Allow once").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.runOnIdle{work.approveStep("stale-tool-id",true)}
        assertNotNull(work.pendingApproval)
        screenshot("work-inline-approval")
        compose.onNodeWithText("Decline").performClick()
        compose.waitUntil(15000){org.robolectric.shadows.ShadowLooper.idleMainLooper();!work.running}
        assertEquals("Declined",work.steps.single().state)
        compose.onNodeWithText("Arguments",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Check Git status",substring=false).performClick()
        compose.onNodeWithTag("work-tool-${work.steps.single().id}-details").assertIsDisplayed()
        compose.onNodeWithText("Arguments",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Result",substring=false).assertIsDisplayed()
        screenshot("work-expanded-tool-cards")
        compose.onNodeWithContentDescription("Close tool details").performClick()
        compose.mainClock.advanceTimeBy(500)
        val id=work.records.single().id
        val restored=WorkModel(application)
        assertEquals(id,restored.records.single().id)
        assertEquals(1,restored.records.single().messages.count{it.role=="user"})
        assertTrue(restored.viewRecord(restored.records.single()))
        compose.runOnIdle{work.viewRecord(restored.records.single())}
        compose.onNodeWithText("Reply to Hydra").performTextInput("Continue without commands")
        compose.onNodeWithContentDescription("Send task message").performClick()
        compose.waitUntil(15000){org.robolectric.shadows.ShadowLooper.idleMainLooper();!work.running&&work.messages.count{it.role=="user"}==2}
        assertEquals(id,work.records.single().id)
        assertEquals(2,work.records.single().messages.count{it.role=="user"})
        val wire=followUpPayload!!.getJSONArray("messages")
        assertEquals("Continue without commands",wire.getJSONObject(wire.length()-1).getString("content"))
        assertTrue((0 until wire.length()).any{wire.getJSONObject(it).optString("tool_call_id")=="call-one"})
        screenshot("work-follow-up")
    }
}
