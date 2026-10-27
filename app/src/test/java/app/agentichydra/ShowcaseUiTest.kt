package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Public documentation images use only deterministic demo content and a local fixture server. */
abstract class ShowcaseUiTestBase:AppUiTestBase() {
    protected fun configureDemoModels():HydraModel {
        val models=listOf(
            ModelSpec("qwen3:8b","Qwen 3 · 8B","Alibaba",thinking=true),
            ModelSpec("gpt-oss:20b","GPT-OSS · 20B","OpenAI",thinking=true),
            ModelSpec("gemma3:12b","Gemma 3 · 12B","Google"),
            ModelSpec("llama3.3:70b","Llama 3.3 · 70B","Meta"),
            ModelSpec("deepseek-r1:8b","DeepSeek R1 · 8B","DeepSeek",thinking=true))
        val body=JSONObject().put("data",JSONArray().apply{models.forEach{put(it.json())}}).toString()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody(body)}
        val chat=(controller.get().application as HydraApplication).models[HydraModel::class.java]
        compose.runOnIdle{chat.configure(Provider.OLLAMA,server.url("/v1").toString().trimEnd('/'),"qwen3:8b","")}
        compose.waitUntil(15000){compose.waitForIdle();chat.models.any{it.id=="qwen3:8b"}}
        return chat
    }
}

class ShowcaseHomeUiTest:ShowcaseUiTestBase() {
    @Test fun captureHome(){
        configureDemoModels()
        controller.get().setContent{HydraTheme(darkTheme=true,dynamicColor=false){HydraApp()}}
        compose.onNodeWithTag("chat-welcome").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        screenshot("showcase-home")
    }
}

class ShowcaseChatUiTest:ShowcaseUiTestBase() {
    @Test fun captureChat(){
        val chat=configureDemoModels()
        val now=System.currentTimeMillis()
        val messages=listOf(Message("user","Help me make a focused plan for my Android project.",createdAt=now-30_000),
            Message("assistant","## A clear place to start\n\nKeep the next iteration small and useful.\n\n1. **Pick one screen.** Write down the interaction you want to improve.\n2. **Make a focused change.** Reuse the existing theme and components.\n3. **Check the result.** Build the app and try the flow on your phone.\n\n### A useful first task\n\nReview the composer’s spacing, then check it in light and dark mode. Keep `48.dp` touch targets for every action.\n\nTell me which screen you’d like to start with.",
                createdAt=now-25_000,modelId=chat.model,modelName=chat.selectedModel.name,providerName=chat.provider.label,
                metrics=ResponseMetrics(184,4600,false,now-20_000)))
        compose.runOnIdle{chat.open(Conversation("showcase-chat","A focused Android iteration",messages))}
        controller.get().setContent{HydraTheme(darkTheme=true,dynamicColor=false){ChatScreen(chat)}}
        compose.mainClock.advanceTimeBy(500)
        screenshot("showcase-chat")
    }
}

class ShowcaseWorkUiTest:ShowcaseUiTestBase() {
    @Test fun captureWork(){
        val chat=configureDemoModels()
        val now=System.currentTimeMillis()
        val read=WorkStep("read_file","{\"path\":\"app/src/main/java/app/agentichydra/ChatWelcome.kt\"}","Done","Read the welcome layout.",id="showcase-read",createdAt=now-20_000)
        val check=WorkStep("run_command","{\"command\":\"git diff --check\"}",id="showcase-check",createdAt=now-10_000)
        val messages=listOf(Message("user","Review this project and check the changes.",createdAt=now-40_000),
            Message("assistant","I’ll inspect the layout and ask before running a command.",createdAt=now-30_000),
            Message("tool",read.tool,id=read.id,createdAt=read.createdAt),Message("tool",check.tool,id=check.id,createdAt=check.createdAt))
        controller.get().setContent{HydraTheme(darkTheme=false,dynamicColor=false){
            WorkChatScreen(WorkChatUiState("Review the chat layout","Agentic Hydra",messages,listOf(read,check),check.id,true,"Needs approval",conversationId="showcase-work"),"",{},WorkChatActions()){
                ComposerModelControl(chat,onOpen={})
            }
        }}
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        screenshot("showcase-work")
    }
}

class ShowcaseModelsUiTest:ShowcaseUiTestBase() {
    @Test fun captureModels(){
        val chat=configureDemoModels()
        controller.get().setContent{HydraTheme(darkTheme=false,dynamicColor=false){HydraApp()}}
        compose.onNodeWithText(chat.selectedModel.name).performClick()
        compose.onNodeWithTag("models-header").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsEnabled()
        screenshot("showcase-models")
    }
}
