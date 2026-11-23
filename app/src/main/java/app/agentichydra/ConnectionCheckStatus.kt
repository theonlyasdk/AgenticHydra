package app.agentichydra

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.loadingindicator.LoadingIndicator
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ConnectionCheckStatus() {
    val messages =
        remember {
            listOf(
                "Checking your connection…",
                "Waiting for the provider…",
                "Verifying model access…",
                "This can take a moment…",
            )
        }
    var messageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }
    ConnectionCheckStatusContent(messages[messageIndex])
}

@Composable
private fun ConnectionCheckStatusContent(message: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp).clearAndSetSemantics {
            contentDescription = "Checking connection"
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HydraLoadingIndicator(56.dp, cs.primary, cs.surface)
        Box(
            Modifier.fillMaxWidth().clipToBounds().verticalFadingEdges(cs.surface, 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(targetState = message, label = "Connection status", transitionSpec = {
                (
                    (
                        slideInVertically(
                            spring(dampingRatio = .9f, stiffness = 350f),
                        ) { it } + fadeIn(spring())
                    ) togetherWith
                        (slideOutVertically(spring(dampingRatio = .9f, stiffness = 350f)) { -it } + fadeOut(spring()))
                ).using(null)
            }) { text ->
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun ShimmerStatusText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    brushWidth: Float? = null,
) {
    val cs = MaterialTheme.colorScheme
    var textWidth by remember { mutableFloatStateOf(1f) }
    val shine by rememberInfiniteTransition(label = "Status shine").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "Shine position",
    )
    val highlight = if (cs.surface.luminance() < .5f) cs.inverseSurface else cs.surfaceContainerLowest
    val width = brushWidth ?: textWidth
    Text(
        text,
        modifier.onSizeChanged { textWidth = it.width.coerceAtLeast(1).toFloat() },
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style =
            style.copy(
                brush =
                    Brush.linearGradient(
                        0f to cs.onSurfaceVariant,
                        .3f to cs.onSurfaceVariant,
                        .5f to highlight,
                        .7f to cs.onSurfaceVariant,
                        1f to cs.onSurfaceVariant,
                        start = Offset((shine - .3f) * width, 0f),
                        end = Offset((shine + .3f) * width, 0f),
                    ),
            ),
    )
}

/** Material's defaults: a 34dp sprite morphing inside a 48dp container. */
internal const val LOADING_CONTAINER_DP = 48f
internal const val LOADING_SPRITE_DP = 34f

@Composable
fun HydraLoadingIndicator(
    size: Dp,
    indicator: Color,
    container: Color,
    modifier: Modifier = Modifier,
) {
    // The widget clips to its own bounds instead of scaling down, so a small host crops the morph.
    // Ask the view for the container and sprite sizes directly and it lays itself out at that size.
    val containerPx = with(LocalDensity.current) { size.roundToPx() }
    val spritePx = (containerPx * LOADING_SPRITE_DP / LOADING_CONTAINER_DP).roundToInt()
    AndroidView(factory = { context ->
        LoadingIndicator(
            ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar),
        )
    }, update = { view ->
        view.setContainerWidth(containerPx)
        view.setContainerHeight(containerPx)
        view.setIndicatorSize(spritePx)
        view.setIndicatorColor(indicator.toArgb())
        view.setContainerColor(container.toArgb())
    }, modifier = modifier.size(size))
}

@Preview(name = "Connection check light")
@Preview(name = "Connection check dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Connection check large text", fontScale = 1.5f)
@Composable
private fun ConnectionCheckPreview() {
    HydraTheme {
        Surface(
            color = MaterialTheme.colorScheme.surface,
        ) { ConnectionCheckStatusContent("Checking your connection…") }
    }
}
