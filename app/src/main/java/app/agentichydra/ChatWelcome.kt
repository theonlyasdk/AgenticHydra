package app.agentichydra

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.time.LocalDateTime

data class ChatStarter(val icon:ImageVector,val title:String,val prompt:String)
data class ChatWelcomeUiState(val greeting:String,val suggestions:List<ChatStarter>)

private val starterSets=listOf(
    listOf(
        ChatStarter(Icons.Outlined.Code,"Explain code","Explain this function and suggest ways to simplify it."),
        ChatStarter(Icons.Outlined.Lightbulb,"Explore an idea","Help me plan a small Android app. Ask me about the problem first."),
        ChatStarter(Icons.Outlined.AutoAwesome,"Learn something","Explain how AI agents use tools, with a practical example.")),
    listOf(
        ChatStarter(Icons.Outlined.BugReport,"Untangle a bug","Help me debug an issue. Ask me for the error and the relevant code first."),
        ChatStarter(Icons.AutoMirrored.Outlined.CompareArrows,"Compare two approaches","Help me compare two approaches to a problem. Ask about my constraints first."),
        ChatStarter(Icons.Outlined.Edit,"Find the right words","Help me rewrite something so it sounds clear and natural. Ask me for the text.")),
    listOf(
        ChatStarter(Icons.Outlined.Checklist,"Break it into steps","Help me break a complicated task into manageable steps. Ask what I want to achieve."),
        ChatStarter(Icons.Outlined.Code,"Review a change","Review a code change for bugs and readability. Ask me to paste the diff."),
        ChatStarter(Icons.Outlined.Lightbulb,"Try a fresh angle","Help me brainstorm different ways to solve a problem. Ask me what I’m working on.")),
    listOf(
        ChatStarter(Icons.Outlined.Science,"Make a test plan","Help me create a focused test plan. Ask me about the feature and its risks."),
        ChatStarter(Icons.Outlined.AccountTree,"Map a project","Help me understand a codebase. Ask me to share its folder structure first."),
        ChatStarter(Icons.Outlined.Description,"Turn notes into a plan","Turn my rough notes into a clear action plan. Ask me to paste my notes.")),
    listOf(
        ChatStarter(Icons.Outlined.Code,"Simplify a function","Help me simplify code while preserving its behavior. Ask me for the function."),
        ChatStarter(Icons.Outlined.School,"Practice a new skill","Teach me a concept through a short exercise. Ask what I’d like to learn."),
        ChatStarter(Icons.Outlined.Search,"Check my reasoning","Help me check an argument for missing assumptions. Ask me to explain my reasoning."))
)

fun chatWelcomeAt(now:LocalDateTime):ChatWelcomeUiState {
    val greetings=when(now.hour) {
        in 5..11->listOf("Good morning. What’s the plan?","A fresh day. What’s on your mind?","Morning. Where shall we start?","What will you explore this morning?","Good morning. Let’s make something.")
        in 12..16->listOf("Good afternoon. What’s next?","What’s taking shape today?","An idea for this afternoon?","What can we figure out together?","Where shall we go from here?")
        in 17..21->listOf("Good evening. What’s on your mind?","What are you thinking about tonight?","An evening for a new idea?","What would you like to explore?","Good evening. Where shall we start?")
        else->listOf("A late-night idea?","Still thinking? I’m here.","What’s on your mind tonight?","A quiet moment to explore?","What are we working on tonight?")
    }
    val day=now.toLocalDate().toEpochDay()
    return ChatWelcomeUiState(greetings[Math.floorMod(day,greetings.size.toLong()).toInt()],
        starterSets[Math.floorMod(day,starterSets.size.toLong()).toInt()])
}

@Composable fun rememberChatWelcome():ChatWelcomeUiState {
    val activity=LocalContext.current as? ComponentActivity
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(activity) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME)now=LocalDateTime.now() }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(activity) {
        while(true) {
            delay(60_000)
            if(activity==null||activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))now=LocalDateTime.now()
        }
    }
    return chatWelcomeAt(now)
}

@Composable fun ChatWelcome(state:ChatWelcomeUiState,onChoose:(String)->Unit) {
    Column(Modifier.widthIn(max=600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=28.dp,vertical=24.dp).testTag("chat-welcome"),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        HydraIllustration(Modifier.size(112.dp))
        Spacer(Modifier.height(20.dp))
        Text(state.greeting,style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
        state.suggestions.forEach { Starter(it.icon,it.title,it.prompt,onChoose) }
        Spacer(Modifier.height(24.dp))
    }
}

@Preview(name="Welcome light")
@Preview(name="Welcome dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Welcome large text",fontScale=1.5f)
@Composable private fun WelcomePreview(){HydraTheme{ChatWelcome(chatWelcomeAt(LocalDateTime.of(2026,10,26,10,0)),{})}}
