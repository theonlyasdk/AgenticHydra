package app.agentichydra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun sse(vararg events:JSONObject):MockResponse {
    val body=buildString{
        events.forEach{append("data: ").append(it.toString()).append("\n\n")}
        append("data: [DONE]\n\n")
    }
    return MockResponse().setHeader("Content-Type","text/event-stream").setBody(body)
}

private fun textEvent(text:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",text))))

private fun toolEvent(id:String,name:String,arguments:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",
    JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("index",0).put("id",id).put("type","function")
        .put("function",JSONObject().put("name",name).put("arguments",arguments)))))))

/**
 * Chat-mode read-only tools at the model level: the tool loop replays streamed
 * tool calls, surfaces deterministic tool errors, pauses for ask_user answers,
 * and resumes with a plain answer when a model rejects the tool schema entirely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class ChatToolsTest {
    private lateinit var server:MockWebServer
    private lateinit var chat:HydraModel
    private lateinit var context:Context
    private val captured=mutableListOf<String>()

    @Before fun setUp() {
        server=MockWebServer();server.start()
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse=MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
        }
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("hydra",Context.MODE_PRIVATE).edit().clear().commit()
        val application=context as HydraApplication
        chat=application.models[HydraModel::class.java]
        chat.configure(Provider.CUSTOM,server.url("/v1").toString().trimEnd('/'),"","")
        chat.chooseModel("test/alpha")
        pump{!chat.discovering}
    }

    @After fun tearDown() {
        chat.stop()
        pump{!chat.busy}
        (context as HydraApplication).viewModelStore.clear()
        server.shutdown()
    }

    private fun pump(timeoutMs:Long=15000,until:()->Boolean) {
        val start=System.currentTimeMillis()
        while(!until()&&System.currentTimeMillis()-start<timeoutMs){ShadowLooper.idleMainLooper();Thread.sleep(10)}
        ShadowLooper.idleMainLooper()
        assertTrue(until())
    }

    /** Classifies a request: namer calls are recorded nowhere and fail silently. */
    private fun agentBody(request:RecordedRequest):String? {
        val body=request.body.readUtf8()
        if(body.contains("Name this conversation"))return null
        captured.add(body)
        return body
    }

    private fun agentTurnDispatcher(turn:AtomicInteger,responses:(Int)->MockResponse)=object:Dispatcher(){
        override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path?.endsWith("/models")==true)return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
            if(agentBody(request)==null)return MockResponse().setResponseCode(500).setBody("{}")
            return responses(turn.incrementAndGet())
        }
    }

    private val questionArgs=JSONObject().put("questions",JSONArray().put(JSONObject()
        .put("id","depth").put("title","Report depth").put("question","How deep should the research go?")
        .put("type","single_choice").put("options",JSONArray().put("Quick").put("Deep")))).toString()

    @Test fun chatToolLoopRunsSearchToolsAndReportsDeterministicErrors() {
        val turn=AtomicInteger()
        server.dispatcher=agentTurnDispatcher(turn){when(it) {
            1->sse(toolEvent("call-fetch","fetch_url",JSONObject().put("url","http://127.0.0.1/notes").toString()))
            2->sse(textEvent("Search was blocked, so here is what I know already."))
            else->MockResponse().setResponseCode(500).setBody("{}")
        }}
        assertTrue(chat.send("What do the Kotlin docs say about Flow?"))
        pump{!chat.busy}
        assertNull(chat.error)
        assertEquals(1,chat.steps.size)
        val step=chat.steps.first()
        assertEquals("fetch_url",step.tool)
        assertEquals("Failed",step.state)
        assertTrue(step.output.contains("Tool error"))
        assertTrue(step.output.contains("Use a public HTTPS URL"))
        // The tool row and the final answer are both in the transcript.
        assertTrue(chat.messages.any{it.role=="tool"&&it.id==step.id})
        assertTrue(chat.messages.any{it.role=="assistant"&&it.text.contains("Search was blocked")})
        // The agent turn carried the chat tool schema and the follow-up replayed the result.
        val turns=captured.filter{it.contains("tool_choice")}
        assertEquals(2,turns.size)
        val first=JSONObject(turns[0])
        assertEquals("auto",first.getString("tool_choice"))
        val toolNames=(0 until first.getJSONArray("tools").length())
            .map{first.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name")}
        assertTrue(toolNames.containsAll(listOf("web_search","fetch_url","get_time","ask_user")))
        val second=JSONObject(turns[1])
        val toolMessages=(0 until second.getJSONArray("messages").length())
            .map{second.getJSONArray("messages").getJSONObject(it)}.filter{it.optString("role")=="tool"}
        assertEquals(1,toolMessages.size)
        assertEquals("call-fetch",toolMessages.first().getString("tool_call_id"))
        assertTrue(toolMessages.first().getString("content").contains("Use a public HTTPS URL"))
    }

    @Test fun modelsWithoutToolSupportAnswerAfterDroppingTheSchema() {
        val turn=AtomicInteger()
        server.dispatcher=agentTurnDispatcher(turn){when(it) {
            1->MockResponse().setResponseCode(400).setHeader("Content-Type","application/json")
                .setBody("""{"error":{"message":"tools is not supported by this model"}}""")
            2->sse(textEvent("Plain answer without tools."))
            else->MockResponse().setResponseCode(500).setBody("{}")
        }}
        assertTrue(chat.send("Summarize the plan."))
        pump{!chat.busy}
        assertNull(chat.error)
        assertTrue(chat.messages.any{it.role=="notice"&&it.text.contains("doesn't support tools")})
        assertTrue(chat.messages.any{it.role=="assistant"&&it.text=="Plain answer without tools."})
        assertEquals(emptyList<WorkStep>(),chat.steps)
        // The retry removed the tool schema but kept streaming.
        val turns=captured
        assertEquals(2,turns.size)
        val fallback=JSONObject(turns[1])
        assertFalse(fallback.has("tools"))
        assertFalse(fallback.has("tool_choice"))
        assertTrue(fallback.getBoolean("stream"))
    }

    @Test fun askUserPausesForAnswersAndResumesTheTurn() {
        val turn=AtomicInteger()
        server.dispatcher=agentTurnDispatcher(turn){when(it) {
            1->sse(toolEvent("call-ask","ask_user",questionArgs))
            2->sse(textEvent("Understood, going deep."))
            else->MockResponse().setResponseCode(500).setBody("{}")
        }}
        assertTrue(chat.send("Research for me."))
        pump{chat.pendingQuestions!=null}
        val request=chat.pendingQuestions!!
        assertEquals("depth",request.questions.first().id)
        assertEquals(QuestionType.SINGLE,request.questions.first().type)
        chat.answerQuestions(request.id,QuestionTool.result(request,"""{"depth":{"selected":["Deep"]}}"""))
        pump{!chat.busy}
        assertNull(chat.pendingQuestions)
        assertTrue(chat.messages.any{it.role=="assistant"&&it.text=="Understood, going deep."})
        assertEquals("Done",chat.steps.single().state)
        // The model received the structured answer for its own call id.
        val agentBodies=captured.filter{it.contains("tool_choice")}
        val second=JSONObject(agentBodies[1])
        val tool=(0 until second.getJSONArray("messages").length())
            .map{second.getJSONArray("messages").getJSONObject(it)}.first{it.optString("role")=="tool"}
        assertEquals("call-ask",tool.getString("tool_call_id"))
        assertTrue(tool.getString("content").contains("\"Deep\""))
    }
}
