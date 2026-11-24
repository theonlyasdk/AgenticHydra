package app.agentichydra

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Title row shared by every sheet and settings card. */
@Composable
internal fun SheetHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * The single rounded menu used for every option list in the app.
 *
 * Sheets are hosted in their own window, where a popup anchors to the wrong corner, so
 * [inline] opens the identical menu in place instead of floating.
 */
@Composable
fun HydraOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    headerText: String? = null,
    inline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (inline) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = 520f)) + fadeIn(spring(stiffness = 520f)),
            exit = shrinkVertically(spring(stiffness = 420f)) + fadeOut(spring(stiffness = 420f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(color = cs.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                Column {
                    headerText?.let { MenuHeader(it) }
                    content()
                }
            }
        }
        return
    }
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    if (!expandedState.currentState && !expandedState.targetState) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    val shadowPad = 8.dp
    val transformOriginState = remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    val positionProvider =
        remember(density) {
            val marginPx = with(density) { 8.dp.roundToPx() }
            val shadowPadPx = with(density) { shadowPad.roundToPx() }
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ): androidx.compose.ui.unit.IntOffset {
                    val visibleW = (popupContentSize.width - shadowPadPx * 2).coerceAtLeast(0)
                    val visibleH = (popupContentSize.height - shadowPadPx * 2).coerceAtLeast(0)

                    val fitsRight = anchorBounds.left + visibleW <= windowSize.width - marginPx
                    val fitsLeft = anchorBounds.right - visibleW >= marginPx
                    val rawX =
                        if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
                            if (fitsRight || !fitsLeft) anchorBounds.left else anchorBounds.right - visibleW
                        } else {
                            if (fitsLeft || !fitsRight) anchorBounds.right - visibleW else anchorBounds.left
                        }
                    val x = rawX.coerceIn(marginPx, (windowSize.width - visibleW - marginPx).coerceAtLeast(marginPx))

                    val fitsBelow = anchorBounds.bottom + visibleH <= windowSize.height - marginPx
                    val fitsAbove = anchorBounds.top - visibleH >= marginPx
                    val openAbove = !fitsBelow && fitsAbove
                    val rawY = if (openAbove) anchorBounds.top - visibleH else anchorBounds.bottom
                    val y = rawY.coerceIn(marginPx, (windowSize.height - visibleH - marginPx).coerceAtLeast(marginPx))

                    val pivotX = if (x + visibleW / 2 >= anchorBounds.center.x) 0f else 1f
                    val pivotY = if (openAbove) 1f else 0f
                    transformOriginState.value = TransformOrigin(pivotX, pivotY)
                    return androidx.compose.ui.unit.IntOffset(x - shadowPadPx, y - shadowPadPx)
                }
            }
        }

    val transition = rememberTransition(expandedState, label = "HydraOverflowMenu")
    val scale by transition.animateFloat(
        transitionSpec = { spring(stiffness = 1100f, dampingRatio = 0.9f) },
        label = "Menu scale",
    ) { if (it) 1f else 0.92f }
    val alpha by transition.animateFloat(
        transitionSpec = { spring(stiffness = 1200f) },
        label = "Menu alpha",
    ) { if (it) 1f else 0f }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = cs.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = MENU_SHADOW_ELEVATION,
            modifier =
                Modifier
                    .padding(shadowPad)
                    .width(IntrinsicSize.Max)
                    .graphicsLayer {
                        this.transformOrigin = transformOriginState.value
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
        ) {
            Column(Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState())) {
                headerText?.let { MenuHeader(it, Modifier.widthIn(max = 280.dp)) }
                content()
            }
        }
    }
}

private val MENU_SHADOW_ELEVATION = 8.dp

@Composable
private fun MenuHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The icon + label shape every overflow popup in the app uses. */
@Composable
internal fun HydraMenuItem(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color? = null,
    selected: Boolean = false,
    preview: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (preview != null) {
                preview()
            } else {
                Icon(icon, null, tint = tint ?: LocalContentColor.current)
            }
        },
        trailingIcon =
            if (selected) {
                { Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
        enabled = enabled,
        onClick = onClick,
    )
}

/** The agent's busy indicator, following the thinking animation chosen in Appearance. */
@Composable
internal fun ThinkingIndicator(
    animation: ThinkingAnimation = LocalThinkingAnimation.current,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    when (animation) {
        // The morph fills 34dp of its 48dp container, so grow the host until the visible
        // sprite matches the spinner's diameter.
        ThinkingAnimation.BLOB ->
            HydraLoadingIndicator(
                size * (LOADING_CONTAINER_DP / LOADING_SPRITE_DP),
                MaterialTheme.colorScheme.primary,
                Color.Transparent,
                modifier,
            )

        ThinkingAnimation.SPINNER ->
            CircularProgressIndicator(modifier.size(size), strokeWidth = 2.dp)

        ThinkingAnimation.DOTS, ThinkingAnimation.PULSE, ThinkingAnimation.ORBIT ->
            AnimatedThinkingMark(animation, modifier.size(size))
    }
}

@Composable
private fun AnimatedThinkingMark(animation: ThinkingAnimation, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = if (ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "Thinking mark").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "Thinking cycle",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val phase = transition?.value ?: 0f
        val unit = size.minDimension
        when (animation) {
            ThinkingAnimation.DOTS -> repeat(3) { index ->
                val cycle = (phase + 1f - index * 0.16f) % 1f
                val lift = if (transition != null && cycle < 0.5f) sin((cycle * 2 * PI)).toFloat() else 0f
                drawCircle(
                    color,
                    radius = unit * 0.105f,
                    center = center + Offset((index - 1) * unit * 0.31f, -lift * unit * 0.21f),
                )
            }

            ThinkingAnimation.PULSE -> {
                drawCircle(color, radius = unit * 0.14f)
                drawCircle(
                    color,
                    radius = unit * (0.23f + phase * 0.20f),
                    alpha = 1f - phase,
                    style = Stroke(width = unit * 0.085f),
                )
            }

            ThinkingAnimation.ORBIT -> repeat(2) { index ->
                val angle = (phase * 2 + index) * PI
                val offset = Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (unit * 0.31f)
                drawCircle(color, radius = unit * if (index == 0) 0.13f else 0.095f, center = center + offset)
            }

            ThinkingAnimation.BLOB, ThinkingAnimation.SPINNER -> Unit
        }
    }
}

/** Thinking indicator + status message shown while the agent is working. */
@Composable
internal fun BusyStatusRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.then(rememberStatusWipeIn()), verticalAlignment = Alignment.CenterVertically) {
        ThinkingIndicator()
        Spacer(Modifier.width(spacing))
        content()
    }
}

@Composable
internal fun ErrorBanner(
    text: String,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, color = cs.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = cs.onErrorContainer) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss error", tint = cs.onErrorContainer) }
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RenameDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    autofocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autofocus) {
        if (autofocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                onValueChange,
                label = { Text(label) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = if (autofocus) Modifier.focusRequester(focusRequester) else Modifier,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Centered icon + message used when a list or sheet has nothing to show. */
@Composable
internal fun HydraEmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = cs.onSurfaceVariant)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** Turns snake_case identifiers into sentence-case labels. */
internal fun humanizedLabel(name: String): String = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
