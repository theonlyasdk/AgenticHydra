package app.agentichydra

import androidx.compose.ui.test.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

class AgentQuestionLoopTest:AppUiTestBase() {
    @Test fun dismissingQuestionsResumesWithCancellationAndStoppingClearsPendingWizard(){
        val app=controller.get().application as HydraApplication
        val work=app.models[WorkModel::class.java];val chat=app.models[HydraModel::class.java]
        chat.chooseModel("test/alpha");work.mode=ApprovalMode.EVERY
        val calls=AtomicInteger();var responsePayload:JSONObject?=null
        val questions=JSONObject().put("questions",JSONArray().put(JSONObject().put("id","proceed").put("title","Proceed?").put("question","Which path should I use?").put("type","yes_no")))
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path?.endsWith("/models")==true)return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
            val message=if(calls.incrementAndGet()==2){responsePayload=JSONObject(request.body.readUtf8());JSONObject().put("role","assistant").put("content","I will wait for your instructions.")}
            else JSONObject().put("role","assistant").put("content","I need your preference.").put("tool_calls",JSONArray().put(JSONObject().put("id","question-call").put("type","function").put("function",JSONObject().put("name","ask_user").put("arguments",questions.toString()))))
            return MockResponse().setBody(JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message))).toString())
        }}
        compose.runOnIdle{assertTrue(work.followUp("Ask me before choosing a path.",chat))}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();work.pendingQuestions!=null}
        assertNull(work.pendingApproval)
        val request=work.pendingQuestions!!
        compose.runOnIdle{work.answerQuestions("stale-question",null)}
        assertNotNull(work.pendingQuestions)
        compose.runOnIdle{work.answerQuestions(request.id,null)}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();!work.running}
        val messages=responsePayload!!.getJSONArray("messages")
        val tool=(0 until messages.length()).map{messages.getJSONObject(it)}.first{it.optString("role")=="tool"}
        assertEquals("cancelled",JSONObject(tool.getString("content")).getString("status"))
        compose.runOnIdle{assertTrue(work.followUp("Ask another question.",chat))}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();work.pendingQuestions!=null}
        compose.runOnIdle{work.stop()}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();!work.running}
        assertNull(work.pendingQuestions);assertNull(work.pendingDeviceAction);assertEquals("Cancelled",work.status)
    }
}
