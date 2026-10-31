package app.agentichydra

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = cs.surfaceContainerHigh,
        tonalElevation = 0.dp,
        // Softest shadow that still lands inside the popup window: a popup is sized to its content
        // and only reserves shadow room for 8dp of elevation, so anything broader is cut off.
        shadowElevation = MENU_SHADOW_ELEVATION,
    ) {
        headerText?.let { MenuHeader(it, Modifier.widthIn(max = 280.dp)) }
        content()
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
) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, color = cs.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
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
