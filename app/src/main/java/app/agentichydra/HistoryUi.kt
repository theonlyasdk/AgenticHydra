package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlin.math.ceil

// Blend scroll edges into their actual semantic surface, without an extra offscreen layer.
fun Modifier.horizontalFadingEdges(surface: Color) =
    drawWithContent {
        drawContent()
        val edge = 12.dp.toPx().coerceAtMost(size.width / 2)
        drawRect(Brush.horizontalGradient(listOf(surface, Color.Transparent), endX = edge))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, surface), startX = size.width - edge))
    }

fun Modifier.horizontalScrollFadingEdges(
    state: ScrollState,
    surface: Color,
    direction: LayoutDirection,
) = drawWithContent {
    drawContent()
    if (state.maxValue != Int.MAX_VALUE) {
        val edge = 12.dp.toPx().coerceAtMost(size.width / 2)
        val start = state.value.toFloat().coerceIn(0f, edge)
        val end = (state.maxValue - state.value).toFloat().coerceIn(0f, edge)
        val left = if (direction == LayoutDirection.Ltr) start else end
        val right = if (direction == LayoutDirection.Ltr) end else start
        if (left > 0f) drawRect(Brush.horizontalGradient(listOf(surface, Color.Transparent), endX = left))
        if (right > 0f) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, surface), startX = size.width - right))
        }
    }
}

fun Modifier.bottomFadingEdge(surface: Color) =
    drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, surface),
                startY = (size.height - 24.dp.toPx()).coerceAtLeast(0f),
            ),
        )
    }

fun Modifier.verticalFadingEdges(
    surface: Color,
    edgeLength: Dp = 16.dp,
) = drawWithContent {
    drawContent()
    val edge = edgeLength.toPx().coerceAtMost(size.height / 2)
    drawRect(Brush.verticalGradient(listOf(surface, Color.Transparent), endY = edge))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, surface), startY = size.height - edge))
}

data class ConversationRowUiState(
    val title: String,
    val selected: Boolean,
)

/** Icon-only button that reveals a short hint when long-pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HintIconButton(
    hint: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = hint,
    onClickLabel: String = hint,
) {
    val cs = MaterialTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            delay(1600)
            show = false
        }
    }
    Box(modifier) {
        Box(
            Modifier.size(48.dp).combinedClickable(onClick = onClick, onClickLabel = onClickLabel, onLongClick = {
                show = true
            }),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description) }
        if (show) {
            val density = LocalDensity.current
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -with(density) { 8.dp.roundToPx() })) {
                Surface(
                    color = cs.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The standard 3-dot overflow popup used across sidebar, task chat and schedule sheets. */
@Composable
fun HydraOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    headerText: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = cs.surfaceContainerHigh,
    ) {
        headerText?.let { header ->
            Text(
                header,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        content()
    }
}

@Composable
fun MarqueeTitle(
    title: String,
    modifier: Modifier = Modifier,
    surface: Color = MaterialTheme.colorScheme.surface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = LocalContentColor.current,
    textAlign: TextAlign = TextAlign.Start,
) {
    val scroll = rememberScrollState()
    val marquee = LocalMarqueeEnabled.current
    val density = LocalDensity.current.density
    val direction = LocalLayoutDirection.current
    val distance = scroll.maxValue
    LaunchedEffect(title, style, distance, density, marquee) {
        scroll.scrollTo(0)
        if (!marquee) return@LaunchedEffect
        if (distance <= 0 || distance == Int.MAX_VALUE ||
            coroutineContext[MotionDurationScale]?.scaleFactor == 0f
        ) {
            return@LaunchedEffect
        }
        val duration = ceil(distance / density / 30f * 1000).toInt().coerceAtLeast(1)
        repeat(3) {
            delay(1200)
            scroll.animateScrollTo(distance, tween(duration, easing = LinearEasing))
            delay(1200)
            scroll.scrollTo(0)
        }
    }
    // A real scroll offset makes fades conditional on clipping, with no resting inset.
    Text(
        title,
        maxLines = 1,
        softWrap = false,
        style = style,
        color = color,
        textAlign = textAlign,
        modifier =
            modifier
                .horizontalScrollFadingEdges(
                    scroll,
                    surface,
                    direction,
                ).horizontalScroll(scroll, enabled = false),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationHistoryRow(
    state: ConversationRowUiState,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val color = if (state.selected) cs.secondaryContainer else cs.surface
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(color = color, shape = MaterialTheme.shapes.extraLarge) {
            ListItem(
                headlineContent = { MarqueeTitle(state.title, surface = color) },
                leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                trailingContent = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Conversation options") }
                    HydraOverflowMenu(expanded = menu, onDismiss = { menu = false }, headerText = state.title) {
                        DropdownMenuItem(text = {
                            Text(
                                "Rename",
                            )
                        }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(text = {
                            Text(
                                "Export",
                            )
                        }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = {
                            menu = false
                            onExport()
                        })
                        DropdownMenuItem(text = {
                            Text(
                                "Delete",
                            )
                        }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                },
                colors =
                    ListItemDefaults.colors(
                        containerColor = color,
                        headlineColor = if (state.selected) cs.onSecondaryContainer else cs.onSurface,
                    ),
                modifier =
                    Modifier
                        .semantics {
                            selected = state.selected
                        }.combinedClickable(onClick = onOpen, onLongClickLabel = "Conversation options", onLongClick = {
                            menu = true
                        }),
            )
        }
    }
}

fun Conversation.exportMarkdown(): String =
    buildString {
        appendLine("# $title")
        messages.forEach { message ->
            if (message.role == "model") {
                appendLine()
                appendLine("---")
                appendLine("Model changed: ${message.providerName} · ${message.modelName.ifBlank{message.text}}")
            } else {
                appendLine()
                appendLine("## ${if (message.role == "user") "You" else "Hydra"}")
                appendLine()
                appendLine(message.text)
            }
        }
    }

@Preview(name = "History light")
@Preview(name = "History dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "History large text", fontScale = 1.5f)
@Composable
private fun HistoryPreview() {
    HydraTheme {
        ConversationHistoryRow(
            ConversationRowUiState(
                "Inspect this Android project and explain its architecture",
                true,
            ),
            {
            },
            {},
            {},
            {},
        )
    }
}
