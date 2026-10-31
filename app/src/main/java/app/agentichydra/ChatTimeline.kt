package app.agentichydra

import android.content.res.Configuration
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

data class ChatTimelineUiState(
    val conversationId: String,
    val messages: List<Message>,
    val streamingResponseId: String? = null,
)

internal fun chatTimeMarkerIds(messages: List<Message>): Set<String> {
    val markers = mutableSetOf<String>()
    var previousTime: Long? = null
    messages.forEach { message ->
        if (message.createdAt > 0) {
            if (message.role == "user" &&
                (previousTime == null || message.createdAt - previousTime!! >= 30 * 60 * 1000L)
            ) {
                markers.add(
                    message.id,
                )
            }
            previousTime = message.createdAt
        }
    }
    return markers
}

data class MessageLongPressActions(
    val onCopy: (Message) -> Unit,
    val onRevert: (Message) -> Unit,
    val onRegenerate: (Message) -> Unit,
)

private data class TailLayout(
    val following: Boolean,
    val dragging: Boolean,
    val scrolling: Boolean,
    val count: Int,
    val lastIndex: Int,
    val lastBottom: Int,
    val viewportEnd: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatTimeline(
    state: ChatTimelineUiState,
    onCopy: (Message) -> Unit,
    onShare: (Message) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    toolContent: (@Composable (Message, Int, Int) -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    messageActions: MessageLongPressActions? = null,
    searchOpen: Boolean = false,
    onSearchOpenChange: (Boolean) -> Unit = {},
    onViewQuiz: ((Message) -> Unit)? = null,
    showHydraTitle: Boolean = LocalHydraTitleVisible.current,
) {
    var following by rememberSaveable(state.conversationId) { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var typed by rememberSaveable(state.conversationId) { mutableStateOf("") }
    var query by rememberSaveable(state.conversationId) { mutableStateOf("") }
    val matches = remember(state.messages, query) { chatMatches(state.messages, query) }
    val matchGroups = remember(matches) { messageMatchGroups(matches) }
    var cursor by remember(state.conversationId) { mutableStateOf(0) }
    val activeMatch = if (matches.isEmpty()) -1 else cursor.coerceIn(0, matches.lastIndex)
    val searchClearance by animateDpAsState(
        targetValue = if (searchOpen) 84.dp else 16.dp,
        animationSpec = spring(stiffness = 520f),
        label = "Search clearance",
    )
    // While tokens stream in, keep the growing tail a couple of lines clear of the composer.
    val tailClearance by animateDpAsState(
        targetValue = if (state.streamingResponseId != null) 64.dp else 16.dp,
        animationSpec = spring(stiffness = 520f),
        label = "Streaming tail clearance",
    )

    suspend fun revealMatch(match: ChatMatch) {
        val text = state.messages.getOrNull(match.itemIndex)?.text.orEmpty()
        var layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == match.itemIndex }
        if (layout == null) {
            listState.scrollToItem(match.itemIndex)
            // Let the row measure first, so a jump is never followed by a correction.
            withFrameNanos { }
            layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == match.itemIndex } ?: return
        }
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        if (text.isEmpty() || layout.size <= viewport) return
        // A character ratio is the only position estimate available for a message taller than the screen.
        val ratio = (match.offset.toFloat() / text.length).coerceIn(0f, 1f)
        val inside = (ratio * layout.size).roundToInt()
        val top = layout.offset + inside
        val viewportStart = listState.layoutInfo.viewportStartOffset
        // A match already in the middle band stays put, so stepping through a long message
        // never scrolls away and back again.
        if (top in (viewportStart + viewport / 4)..(viewportStart + viewport * 3 / 4)) return
        val target = (inside - viewport / 3).coerceIn(0, layout.size - viewport)
        listState.scrollToItem(match.itemIndex, target)
    }

    fun goToMatch(target: Int) {
        if (matches.isEmpty()) return
        val index = ((target % matches.size) + matches.size) % matches.size
        cursor = index
        // Searching is deliberate navigation; stop sticking to the streaming tail.
        following = false
        scope.launch { revealMatch(matches[index]) }
    }

    fun closeSearch() {
        typed = ""
        query = ""
        onSearchOpenChange(false)
        focusManager.clearFocus()
    }

    LaunchedEffect(typed) {
        delay(220)
        query = typed
    }
    LaunchedEffect(query, state.conversationId) {
        cursor = 0
        if (query.isNotBlank() && matches.isNotEmpty()) goToMatch(0)
    }

    BackHandler(enabled = searchOpen) { closeSearch() }
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val atTop by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    val timeMarkers = remember(state.messages) { chatTimeMarkerIds(state.messages) }
    val latestUserId = state.messages.lastOrNull { it.role == "user" }?.id
    var observedUserId by remember(state.conversationId) { mutableStateOf(latestUserId) }
    LaunchedEffect(state.conversationId, latestUserId) {
        if (latestUserId != observedUserId) {
            observedUserId = latestUserId
            following = true
        }
    }
    val userScroll =
        remember(listState, state.conversationId) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Release synchronously on the first movement toward older content.
                    if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y < 0f) {
                        following = !listState.canScrollForward
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
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
            TailLayout(
                following,
                dragging,
                listState.isScrollInProgress,
                layout.totalItemsCount,
                last?.index ?: -1,
                (last?.offset ?: 0) + (last?.size ?: 0),
                layout.viewportEndOffset,
            )
        }.collect { tail ->
            if (tail.following && !tail.dragging && !tail.scrolling && tail.count > 0 && listState.canScrollForward) {
                if (state.streamingResponseId == null) {
                    withFrameNanos { }
                }
                if (following && !dragging && !listState.isScrollInProgress && listState.canScrollForward) {
                    launch { listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, 100_000) }.join()
                }
            }
        }
    }

    var menuById by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll).testTag("chat-timeline"),
            contentPadding = PaddingValues(start = 24.dp, top = searchClearance, end = 24.dp, bottom = tailClearance),
        ) {
            itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                val adjacentTool = message.role == "tool" && state.messages.getOrNull(index + 1)?.role == "tool"
                val followedByTool = message.role == "assistant" && state.messages.getOrNull(index + 1)?.role == "tool"
                val precededByTool = message.role == "assistant" && state.messages.getOrNull(index - 1)?.role == "tool"
                val next = state.messages.getOrNull(index + 1)
                val group = matchGroups[message.id]
                val highlight =
                    group?.let {
                        ChatHighlight(
                            query,
                            if (activeMatch >= 0 && matches[activeMatch].messageId == message.id) {
                                activeMatch - it.first
                            } else {
                                -1
                            },
                        )
                    }
                val invisibleAssistant =
                    message.role == "assistant" &&
                        message.text.isBlank() &&
                        message.thought.isBlank() &&
                        message.id != state.streamingResponseId
                val hiddenAssistantBeforeTool =
                    message.role == "tool" &&
                        next?.role == "assistant" &&
                        next.text.isBlank() &&
                        next.thought.isBlank() &&
                        next.id != state.streamingResponseId &&
                        state.messages.getOrNull(index + 2)?.role == "tool"
                Column(
                    Modifier.fillMaxWidth().padding(
                        bottom =
                            when {
                                invisibleAssistant -> 0.dp
                                message.role == "tool" && (adjacentTool || hiddenAssistantBeforeTool) -> 0.dp
                                message.role == "tool" -> if (index < state.messages.lastIndex) 4.dp else 0.dp
                                followedByTool -> 4.dp
                                index < state.messages.lastIndex -> 24.dp
                                else -> 0.dp
                            },
                    ),
                ) {
                    if (message.id in timeMarkers) {
                        ChatTimeDivider(message.createdAt)
                        Spacer(Modifier.height(16.dp))
                    }
                    when (message.role) {
                        "model" -> {
                            ModelChangeMarker(
                                message,
                                state.messages.subList(0, index).lastOrNull { it.modelId.isNotBlank() },
                            )
                        }

                        "tool" -> {
                            var start = index
                            var end = index
                            while (start > 0 && state.messages[start - 1].role == "tool")start--
                            while (end < state.messages.lastIndex && state.messages[end + 1].role == "tool")end++
                            toolContent?.invoke(message, index - start, end - start + 1)
                        }

                        "notice" -> {
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            Box(Modifier.fillMaxWidth()) {
                                val prompt =
                                    remember(state.messages, message.id) {
                                        val idx = state.messages.indexOfFirst { it.id == message.id }
                                        if (idx > 0) state.messages[idx - 1].text else ""
                                    }
                                Box(Modifier.combinedClickable(onClick = {}, onLongClick = { menuById = message.id })) {
                                    ChatMessageContent(
                                        message,
                                        message.id == state.streamingResponseId,
                                        onCopy,
                                        onShare,
                                        prompt,
                                        showHydraTitle,
                                        followedByTool,
                                        precededByTool,
                                        highlight,
                                        onViewQuiz = onViewQuiz?.let { open -> { open(message) } },
                                    )
                                }
                                MessageActionMenu(
                                    message,
                                    menuById == message.id,
                                    onCopy,
                                    messageActions,
                                    enabled = state.streamingResponseId != message.id,
                                ) { menuById = null }
                            }
                        }
                    }
                }
            }
            if (trailingContent != null) item(key = "timeline-status") { trailingContent() }
            item(key = "conversation-end") { Spacer(Modifier.fillMaxWidth().height(4.dp).testTag("conversation-end")) }
        }
        val background = MaterialTheme.colorScheme.background
        // Draw above the scrolling content, including hosted Markdown views, below the controls.
        AnimatedVisibility(
            visible = !atTop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Brush.verticalGradient(listOf(background, Color.Transparent))),
            )
        }
        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
            )
        }
        AnimatedVisibility(
            visible = !following && !atBottom,
            enter = scaleIn(spring(stiffness = 650f), initialScale = .65f) + fadeIn(spring(stiffness = 650f)),
            exit = scaleOut(spring(stiffness = 850f), targetScale = .65f) + fadeOut(spring(stiffness = 850f)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                        following = true
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                containerColor = background,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
            ) {
                Icon(Icons.Outlined.ArrowDownward, "Jump to latest")
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            enter = slideInVertically(spring(stiffness = 520f)) { -it } + fadeIn(spring(stiffness = 520f)),
            exit = slideOutVertically(spring(stiffness = 520f)) { -it } + fadeOut(spring(stiffness = 520f)),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ChatFindBar(
                query = typed,
                onQuery = { typed = it },
                matchCount = matches.size,
                activeIndex = activeMatch,
                onPrevious = { goToMatch(cursor - 1) },
                onNext = { goToMatch(cursor + 1) },
                onClose = { closeSearch() },
            )
        }
    }
}

@Composable
private fun ChatTimeDivider(time: Long) {
    val context = LocalContext.current
    val date = Date(time)
    val label =
        (if (DateUtils.isToday(time)) "Today" else DateFormat.getMediumDateFormat(context).format(date)) +
            " · " + DateFormat.getTimeFormat(context).format(date)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ModelChangeMarker(
    message: Message,
    previous: Message?,
) {
    val cs = MaterialTheme.colorScheme
    val oldId = message.previousModelId.ifBlank { previous?.modelId.orEmpty() }
    val oldName =
        message.previousModelName
            .ifBlank { previous?.modelName.orEmpty() }
            .ifBlank { oldId }
            .ifBlank { "Previous model" }
    val oldProviderName = message.previousProviderName.ifBlank { previous?.providerName.orEmpty() }
    val oldProvider = Provider.entries.firstOrNull { it.label == oldProviderName } ?: Provider.CUSTOM
    val newProvider = Provider.entries.firstOrNull { it.label == message.providerName } ?: Provider.CUSTOM
    val newName = message.modelName.ifBlank { message.text }.ifBlank { message.modelId }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
            Text("Model changed", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
        }
        Row(
            Modifier.fillMaxWidth().semantics {
                contentDescription = "$oldProviderName $oldName changed to ${message.providerName} $newName"
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderIcon(oldProvider, Modifier.size(20.dp))
                MarqueeTitle(
                    oldName,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    surface = cs.background,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = cs.primary)
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderIcon(newProvider, Modifier.size(20.dp))
                MarqueeTitle(
                    newName,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    surface = cs.background,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (message.createdAt > 0) {
            Text(
                DateFormat.getTimeFormat(LocalContext.current).format(Date(message.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatMessageContent(
    message: Message,
    streaming: Boolean,
    onCopy: (Message) -> Unit,
    onShare: (Message) -> Unit,
    prompt: String = "",
    showHydraTitle: Boolean = LocalHydraTitleVisible.current,
    followedByTool: Boolean = false,
    precededByTool: Boolean = false,
    highlight: ChatHighlight? = null,
    onViewQuiz: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    if (message.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = cs.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                searchHighlighted(message.text, highlight),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    message.attachments.forEach { attachment ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.AttachFile,
                                null,
                                Modifier.size(18.dp),
                            )
                            MarqueeTitle(
                                attachment.name,
                                surface = cs.surfaceContainerHigh,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    } else {
        val isFinalSummary = message.finalResponse && !followedByTool && !streaming
        Column {
            if (showHydraTitle && isFinalSummary) {
                Row(
                    modifier = Modifier.padding(top = if (precededByTool) 20.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HydraMark(Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("HYDRA", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
                Spacer(Modifier.height(6.dp))
            }
            if (message.text.isBlank() && message.thought.isBlank() && streaming) {
                BusyStatusRow {
                    val initialThinking =
                        remember(prompt) {
                            if (prompt.isNotBlank()) ThinkingMessages.initialDescription(prompt) else "Thinking…"
                        }
                    val thinkingText by produceState(initialValue = initialThinking, key1 = prompt) {
                        val iterator = ThinkingMessages.shuffledSequence().iterator()
                        while (true) {
                            delay(ThinkingMessages.CYCLE_INTERVAL_MS)
                            value = iterator.next()
                        }
                    }
                    WipeStatusText(thinkingText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (message.thought.isNotBlank()) {
                val isThinking = streaming && message.text.isBlank()
                val elapsed =
                    message.metrics?.elapsedMillis
                        ?: if (message.createdAt > 0L) {
                            (System.currentTimeMillis() - message.createdAt).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                ReasoningBlock(
                    thought = message.thought,
                    isThinking = isThinking,
                    elapsedMillis = elapsed,
                )
            }
            if (message.text.isNotBlank()) {
                if (message.thought.isNotBlank()) Spacer(Modifier.height(4.dp))
                MarkdownBody(message.text, highlight = highlight, streaming = streaming)
                if (isFinalSummary) {
                    ResponseActions(
                        message,
                        onCopy = { onCopy(message) },
                        onShare = { onShare(message) },
                        quiz = message.quiz,
                        onViewQuiz = onViewQuiz,
                    )
                }
            }
        }
    }
}

@Composable
fun ReasoningBlock(
    thought: String,
    isThinking: Boolean,
    elapsedMillis: Long = 0L,
    modifier: Modifier = Modifier,
) {
    if (thought.isBlank() && !isThinking) return
    val cs = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(stiffness = 500f),
        label = "Thought chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .semantics {
                        contentDescription = if (expanded) "Collapse thought" else "Expand thought"
                    },
        ) {
            if (isThinking) {
                ShimmerStatusText("Thinking", style = MaterialTheme.typography.labelLarge)
            } else {
                val sec = (elapsedMillis / 1000L).toInt()
                val label = if (sec > 0) "Thought for ${sec}s" else "Thought"
                Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = angle },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(spring(stiffness = 500f)) + expandVertically(spring(stiffness = 500f)),
            exit = fadeOut(spring(stiffness = 500f)) + shrinkVertically(spring(stiffness = 500f)),
        ) {
            val outline = cs.outlineVariant
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 2.dp, bottom = 8.dp)
                        .drawBehind {
                            drawLine(
                                color = outline,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }.padding(start = 12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = thought.ifBlank { "Thinking in progress…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionMenu(
    message: Message,
    expanded: Boolean,
    onCopy: (Message) -> Unit,
    actions: MessageLongPressActions? = null,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
) {
    HydraOverflowMenu(expanded = expanded, onDismiss = onDismiss) {
        HydraMenuItem("Copy", Icons.Outlined.ContentCopy) {
            onDismiss()
            onCopy(message)
        }
        if (message.role == "assistant" && actions != null) {
            HydraMenuItem("Regenerate", Icons.Outlined.Refresh, enabled = enabled) {
                onDismiss()
                actions.onRegenerate(message)
            }
            HydraMenuItem("Revert", Icons.AutoMirrored.Outlined.Undo, enabled = enabled) {
                onDismiss()
                actions.onRevert(message)
            }
        }
    }
}

@Composable
private fun ResponseActions(
    message: Message,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    quiz: QuizAttempt? = null,
    onViewQuiz: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val time = message.metrics?.completedAt ?: message.createdAt
    val clock = if (time > 0) DateFormat.getTimeFormat(LocalContext.current).format(Date(time)) else ""
    val parts =
        buildList {
            message.metrics?.let { metrics ->
                add(
                    "${if (metrics.estimated) "≈" else ""}${String.format(
                        Locale.getDefault(),
                        "%.1f",
                        metrics.tokensPerSecond,
                    )} tok/s",
                )
                add("${String.format(Locale.getDefault(),"%.1f",metrics.elapsedMillis / 1000.0)}s")
            }
            if (clock.isNotBlank()) add(clock)
        }
    if (quiz != null && onViewQuiz != null) {
        FilledTonalButton(
            onClick = onViewQuiz,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.Quiz, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("View quiz results")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ContentCopy, "Copy response", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Share, "Share response", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString("\u2002•\u2002"),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier =
                    Modifier.weight(1f).padding(start = 8.dp).semantics {
                        contentDescription =
                            parts
                                .joinToString(
                                    ". ",
                                ).let { if (message.metrics?.estimated == true) "Estimated token rate. $it" else it }
                    },
            )
        }
    }
}

@Preview(name = "Chat timeline light")
@Preview(name = "Chat timeline dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Chat timeline large text", fontScale = 1.5f)
@Composable
private fun ChatTimelinePreview() {
    val time = 1793035800000L
    HydraTheme {
        Surface {
            ChatTimeline(
                ChatTimelineUiState(
                    "preview",
                    listOf(
                        Message("user", "Explain this project’s architecture.", createdAt = time),
                        Message(
                            "assistant",
                            "The app separates Chat, Work, and User settings. Provider access is shared across Chat and Work.",
                            createdAt = time,
                            metrics = ResponseMetrics(32, 1600, false, time + 1600),
                            thought =
                                "The user is asking about the project architecture. I should break it down into " +
                                    "Chat, Work, and User settings, and clarify how provider access is shared.",
                        ),
                        Message(
                            "model",
                            "Project assistant",
                            createdAt = time + 60000,
                            modelName = "Project assistant",
                            providerName = "NVIDIA NIM",
                            previousModelName = "Code assistant",
                            previousProviderName = "Gemini",
                        ),
                    ),
                ),
                {},
                {},
                Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "Reasoning block light")
@Preview(name = "Reasoning block dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Reasoning block large text", fontScale = 1.5f)
@Composable
private fun ReasoningBlockPreview() {
    HydraTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReasoningBlock(thought = "Active thought stream...", isThinking = true)
                ReasoningBlock(
                    thought = "Analyzed user query, retrieved project history, determined next step.",
                    isThinking = false,
                    elapsedMillis = 3500L,
                )
            }
        }
    }
}
