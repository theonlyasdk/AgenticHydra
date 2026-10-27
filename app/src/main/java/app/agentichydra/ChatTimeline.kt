package app.agentichydra

import android.content.res.Configuration
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

data class ChatTimelineUiState(val conversationId: String, val messages: List<Message>, val streamingResponseId: String? = null)

internal fun chatTimeMarkerIds(messages:List<Message>):Set<String> {
    val markers=mutableSetOf<String>()
    var previousTime:Long?=null
    messages.forEach{message->
        if(message.createdAt>0) {
            if(message.role=="user"&&(previousTime==null||message.createdAt-previousTime!!>=30*60*1000L))markers.add(message.id)
            previousTime=message.createdAt
        }
    }
    return markers
}

private data class TailLayout(
    val following: Boolean, val dragging: Boolean, val scrolling: Boolean, val count: Int,
    val lastIndex: Int, val lastBottom: Int, val viewportEnd: Int,
)

@Composable fun ChatTimeline(
    state: ChatTimelineUiState,
    onCopy: (Message) -> Unit,
    onShare: (Message) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    toolContent: (@Composable (Message,Int,Int)->Unit)? = null,
    trailingContent: (@Composable ()->Unit)? = null,
) {
    var following by rememberSaveable(state.conversationId) { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val timeMarkers=remember(state.messages){chatTimeMarkerIds(state.messages)}
    val latestUserId=state.messages.lastOrNull{it.role=="user"}?.id
    var observedUserId by remember(state.conversationId){mutableStateOf(latestUserId)}
    LaunchedEffect(state.conversationId,latestUserId) {
        if(latestUserId!=observedUserId){observedUserId=latestUserId;following=true}
    }
    val userScroll = remember(listState, state.conversationId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Release synchronously on the first movement toward older content.
                if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y < 0f) following = !listState.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                following = !listState.canScrollForward
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(listState, state.conversationId) {
        // Follow measured geometry, not token callbacks. A single message can exceed the viewport.
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            TailLayout(following, dragging, listState.isScrollInProgress, layout.totalItemsCount,
                last?.index ?: -1, (last?.offset ?: 0) + (last?.size ?: 0), layout.viewportEndOffset)
        }.collect { tail ->
            if (tail.following && !tail.dragging && !tail.scrolling && tail.count > 0 && listState.canScrollForward) {
                withFrameNanos { }
                if (following && !dragging && !listState.isScrollInProgress && listState.canScrollForward) {
                    // Default scroll priority yields to a user gesture; no token-triggered restart.
                    // A gesture cancels only this scroll child, leaving the follower alive.
                    launch { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) }.join()
                }
            }
        }
    }

    Box(modifier) {
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll).testTag("chat-timeline"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
            itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                val adjacentTool=message.role=="tool"&&state.messages.getOrNull(index+1)?.role=="tool"
                Column(Modifier.fillMaxWidth().padding(bottom=if(adjacentTool)0.dp else if(index<state.messages.lastIndex)24.dp else 0.dp)) {
                    if (message.id in timeMarkers) {
                        ChatTimeDivider(message.createdAt)
                        Spacer(Modifier.height(16.dp))
                    }
                    when(message.role) {
                        "model"->ModelChangeMarker(message,state.messages.subList(0,index).lastOrNull { it.modelId.isNotBlank() })
                        "tool"->{
                            var start=index;var end=index
                            while(start>0&&state.messages[start-1].role=="tool")start--
                            while(end<state.messages.lastIndex&&state.messages[end+1].role=="tool")end++
                            toolContent?.invoke(message,index-start,end-start+1)
                        }
                        "notice"->Text(message.text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        else->ChatMessageContent(message, message.id == state.streamingResponseId, onCopy, onShare)
                    }
                }
            }
            if(trailingContent!=null)item(key="timeline-status"){trailingContent()}
            item(key = "conversation-end") { Spacer(Modifier.fillMaxWidth().height(4.dp).testTag("conversation-end")) }
        }
        val background=MaterialTheme.colorScheme.background
        // Draw above the scrolling content, including hosted Markdown views, below the controls.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(32.dp)
            .background(Brush.verticalGradient(listOf(background,Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent,background))))
        AnimatedVisibility(visible=!following&&!atBottom,
            enter=scaleIn(spring(stiffness=650f),initialScale=.65f)+fadeIn(spring(stiffness=650f)),
            exit=scaleOut(spring(stiffness=850f),targetScale=.65f)+fadeOut(spring(stiffness=850f)),
            modifier=Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            SmallFloatingActionButton(onClick = {
                scope.launch {
                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    following = true
                }
            }, modifier=Modifier.size(48.dp),shape=MaterialTheme.shapes.large,
                containerColor=background,elevation=FloatingActionButtonDefaults.elevation(defaultElevation=6.dp,pressedElevation=8.dp)) {
                Icon(Icons.Outlined.ArrowDownward, "Jump to latest")
            }
        }
    }
}

@Composable private fun ChatTimeDivider(time: Long) {
    val context = LocalContext.current
    val date = Date(time)
    val label = (if (DateUtils.isToday(time)) "Today" else DateFormat.getMediumDateFormat(context).format(date)) +
        " · " + DateFormat.getTimeFormat(context).format(date)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable private fun ModelChangeMarker(message: Message,previous:Message?) {
    val cs = MaterialTheme.colorScheme
    val oldId=message.previousModelId.ifBlank { previous?.modelId.orEmpty() }
    val oldName=message.previousModelName.ifBlank { previous?.modelName.orEmpty() }.ifBlank { oldId }.ifBlank { "Previous model" }
    val oldProviderName=message.previousProviderName.ifBlank { previous?.providerName.orEmpty() }
    val oldProvider=Provider.entries.firstOrNull { it.label==oldProviderName }?:Provider.CUSTOM
    val newProvider=Provider.entries.firstOrNull { it.label==message.providerName }?:Provider.CUSTOM
    val newName=message.modelName.ifBlank { message.text }.ifBlank { message.modelId }
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f),color=cs.outlineVariant)
            Text("Model changed", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            HorizontalDivider(Modifier.weight(1f),color=cs.outlineVariant)
        }
        Row(Modifier.fillMaxWidth().semantics {contentDescription="$oldProviderName $oldName changed to ${message.providerName} $newName"},
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)) {
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) {
                ProviderIcon(oldProvider,Modifier.size(20.dp))
                MarqueeTitle(oldName,style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,textAlign=TextAlign.End,surface=cs.background,modifier=Modifier.weight(1f,fill=false))
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint=cs.primary)
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                ProviderIcon(newProvider,Modifier.size(20.dp))
                MarqueeTitle(newName,style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,surface=cs.background,modifier=Modifier.weight(1f,fill=false))
            }
        }
        if(message.createdAt>0)Text(DateFormat.getTimeFormat(LocalContext.current).format(Date(message.createdAt)),
            style=MaterialTheme.typography.labelSmall,color=cs.onSurfaceVariant)
    }
}

@Composable private fun ChatMessageContent(message: Message, streaming: Boolean, onCopy: (Message) -> Unit, onShare: (Message) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (message.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 560.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(message.text.isNotBlank())SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    message.attachments.forEach{attachment->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(if(attachment.isImage)Icons.Outlined.Image else Icons.Outlined.AttachFile,null,Modifier.size(18.dp))
                        MarqueeTitle(attachment.name,surface=cs.surfaceContainerHigh,style=MaterialTheme.typography.bodyMedium)
                    }}
                }
            }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HydraMark(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text("HYDRA", style = MaterialTheme.typography.labelSmall, color = cs.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (message.text.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); ShimmerStatusText("Thinking…")
                }
            } else {
                MarkdownBody(message.text)
                if (!streaming) ResponseActions(message, onCopy = { onCopy(message) }, onShare = { onShare(message) })
            }
        }
    }
}

@Composable private fun ResponseActions(message: Message, onCopy: () -> Unit, onShare: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val time = message.metrics?.completedAt ?: message.createdAt
    val clock=if(time>0)DateFormat.getTimeFormat(LocalContext.current).format(Date(time))else ""
    val parts=buildList {
        message.metrics?.let { metrics ->
            add("${if(metrics.estimated)"≈" else ""}${String.format(Locale.getDefault(),"%.1f",metrics.tokensPerSecond)} tok/s")
            add("${String.format(Locale.getDefault(),"%.1f",metrics.elapsedMillis/1000.0)}s")
        }
        if(clock.isNotBlank())add(clock)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy,modifier=Modifier.size(48.dp)) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart){Icon(Icons.Outlined.ContentCopy, "Copy response", Modifier.size(18.dp), tint = cs.onSurfaceVariant)} }
        IconButton(onClick = onShare,modifier=Modifier.size(48.dp)) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart){Icon(Icons.Outlined.Share, "Share response", Modifier.size(18.dp), tint = cs.onSurfaceVariant)} }
        if(parts.isNotEmpty())Text(parts.joinToString("\u2002•\u2002"),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,
            style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,
            modifier=Modifier.weight(1f).padding(start=8.dp).semantics {
                contentDescription=parts.joinToString(". ").let { if(message.metrics?.estimated==true)"Estimated token rate. $it" else it }
            })
    }
}

@Preview(name = "Chat timeline light")
@Preview(name = "Chat timeline dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Chat timeline large text", fontScale = 1.5f)
@Composable private fun ChatTimelinePreview() {
    val time = 1793035800000L
    HydraTheme { Surface { ChatTimeline(ChatTimelineUiState("preview", listOf(
        Message("user", "Explain this project’s architecture.", createdAt = time),
        Message("assistant", "The app separates Chat, Work, and User settings. Provider access is shared across Chat and Work.",
            createdAt = time, metrics = ResponseMetrics(32, 1600, false, time + 1600)),
        Message("model", "Project assistant", createdAt = time + 60000, modelName = "Project assistant", providerName = "NVIDIA NIM",
            previousModelName="Code assistant",previousProviderName="Gemini"),
    )), {}, {}, Modifier.fillMaxSize()) } }
}
