package app.agentichydra

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class ChatNamingTest {
    @Test fun firstCompletedReplyGetsAnAiTitleThatSurvivesCheckpoints() {
        val titles=AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.path?.endsWith("/models")==true)return MockResponse().setBody("""{"data":[{"id":"test/model"}]}""")
                    val payload=JSONObject(request.body.readUtf8())
                    if(payload.optBoolean("stream"))return MockResponse().setHeader("Content-Type","text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Use a counter and retain state.\"}}]}\n\ndata: [DONE]\n\n")
                    titles.incrementAndGet()
                    assertEquals("test/model",payload.getString("model"))
                    return MockResponse().setBody("""{"choices":[{"message":{"content":"\"Compose state counter\""}}]}""")
                }
            }
            server.start()
            val app=ApplicationProvider.getApplicationContext<Application>()
            app.getSharedPreferences("hydra",Context.MODE_PRIVATE).edit().clear().putString("provider","GEMINI").commit()
            val vm=HydraModel(app)
            vm.configure(Provider.CUSTOM,server.url("/v1").toString().trimEnd('/'),"test/model","")
            assertTrue(vm.send("How do I make a Compose counter?"))
            val deadline=System.nanoTime()+15_000_000_000L
            while(vm.conversations.firstOrNull()?.titleSource!="ai"&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();Thread.sleep(10)}
            assertEquals("Compose state counter",vm.conversations.single().title)
            assertEquals(1,titles.get())
            vm.renameConversation(vm.activeId,"My counter")
            vm.stop()
            val restored=HydraModel(app).conversations.single()
            assertEquals("My counter",restored.title)
            assertEquals("manual",restored.titleSource)
        }
    }

    @Test fun dailySuggestionsStayTheSameAcrossTimeOfDayWhileGreetingsChange() {
        val morning=LocalDateTime.of(2026,10,26,9,0)
        val today=chatWelcomeAt(morning)
        assertNotEquals(today.greeting,chatWelcomeAt(morning.withHour(19)).greeting)
        assertEquals(today.suggestions,chatWelcomeAt(morning.withHour(19)).suggestions)
        val cycle=(0L..4L).map{chatWelcomeAt(morning.plusDays(it)).suggestions.map{starter->starter.title}}
        assertEquals(5,cycle.distinct().size)
        assertTrue(cycle.all{it.size==3})
        assertNotEquals(today.greeting,chatWelcomeAt(morning.plusDays(1)).greeting)
    }
}
