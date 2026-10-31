package app.agentichydra

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil

internal const val STATUS_WIPE_MS = 420
private const val STATUS_WIPE_EDGE_RATIO = 0.35f

/** Masks content with a soft alpha edge that sweeps left to right as progress goes 0 → 1. */
internal fun Modifier.softWipe(
    progress: Float,
    conceal: Boolean = false,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f || size.height <= 0f) return@drawWithContent
            val soft = minOf(size.width * STATUS_WIPE_EDGE_RATIO, 48.dp.toPx())
            val edge = -soft + progress.coerceIn(0f, 1f) * (size.width + soft)
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        colors =
                            if (conceal) {
                                listOf(Color.Transparent, Color.White)
                            } else {
                                listOf(Color.White, Color.Transparent)
                            },
                        startX = edge,
                        endX = edge + soft,
                    ),
                blendMode = BlendMode.DstIn,
            )
        }

/** Wipes the busy row in left to right the first time it enters composition. */
@Composable
internal fun rememberStatusWipeIn(): Modifier {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(STATUS_WIPE_MS, easing = FastOutSlowInEasing))
    }
    return Modifier.softWipe(reveal.value)
}

/** Shimmering status text whose updates wipe the old text out and the new text in, left to right. */
@Composable
internal fun WipeStatusText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE,
) {
    var current by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf<String?>(null) }
    val wipe = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == current) return@LaunchedEffect
        previous = current
        current = text
        wipe.snapTo(0f)
        wipe.animateTo(1f, tween(STATUS_WIPE_MS, easing = FastOutSlowInEasing))
        previous = null
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        previous?.let { old ->
            ShimmerStatusText(old, Modifier.softWipe(wipe.value, conceal = true), style, maxLines, softWrap = false)
        }
        ShimmerStatusText(current, Modifier.softWipe(wipe.value), style, maxLines)
    }
}

/**
 * One-line busy status that shimmers and marquees when it is wider than the row,
 * with fades at both edges instead of an ellipsis.
 */
@Composable
internal fun WipeMarqueeStatusText(
    text: String,
    modifier: Modifier = Modifier,
    surface: Color,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    var current by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf<String?>(null) }
    val wipe = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == current) return@LaunchedEffect
        previous = current
        current = text
        wipe.snapTo(0f)
        wipe.animateTo(1f, tween(STATUS_WIPE_MS, easing = FastOutSlowInEasing))
        previous = null
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.CenterStart) {
        previous?.let { old ->
            ShimmerStatusText(old, Modifier.softWipe(wipe.value, conceal = true), style, maxLines = 1, softWrap = false)
        }
        MarqueeStatusText(current, Modifier.softWipe(wipe.value), surface, style)
    }
}

@Composable
private fun MarqueeStatusText(
    text: String,
    modifier: Modifier,
    surface: Color,
    style: TextStyle,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    val direction = LocalLayoutDirection.current
    val distance = scroll.maxValue
    LaunchedEffect(text, distance) {
        scroll.scrollTo(0)
        val motionScale = coroutineContext[MotionDurationScale]?.scaleFactor
        if (distance <= 0 || distance == Int.MAX_VALUE || motionScale == 0f) return@LaunchedEffect
        val duration = ceil(distance / density / 30f * 1000).toInt().coerceAtLeast(1)
        while (true) {
            delay(1200)
            scroll.animateScrollTo(distance, tween(duration, easing = LinearEasing))
            delay(1200)
            scroll.scrollTo(0)
        }
    }
    BoxWithConstraints(modifier) {
        ShimmerStatusText(
            text,
            Modifier
                .fillMaxWidth()
                .horizontalScrollFadingEdges(scroll, surface, direction)
                .horizontalScroll(scroll, enabled = false),
            style = style,
            maxLines = 1,
            softWrap = false,
            brushWidth = constraints.maxWidth.toFloat(),
        )
    }
}
