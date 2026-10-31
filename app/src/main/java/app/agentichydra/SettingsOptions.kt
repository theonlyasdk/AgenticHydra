package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AppearanceOptions(
    state: AppearanceUiState,
    supportsDynamicColor: Boolean,
    onThemeChange: (AppThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onMarqueeChange: (Boolean) -> Unit,
    onHydraTitleChange: (Boolean) -> Unit = {},
    onRelativeTimeChange: (Boolean) -> Unit = {},
    onThinkingChange: (ThinkingAnimation) -> Unit = {},
    onResponseFadeChange: (Boolean) -> Unit = {},
    onFadeMillisChange: (Int) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val count = AppThemeMode.entries.size + 7
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppThemeMode.entries.forEachIndexed { index, mode ->
            val selected = state.themeMode == mode
            val color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh
            Surface(
                color = color,
                shape = groupedRowShape(index, count),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onThemeChange(mode) }),
            ) {
                ListItem(
                    headlineContent = { Text(mode.label) },
                    supportingContent =
                        if (mode == AppThemeMode.SYSTEM) {
                            { Text("Follow your device’s appearance") }
                        } else {
                            null
                        },
                    leadingContent = {
                        Icon(
                            when (mode) {
                                AppThemeMode.SYSTEM -> Icons.Outlined.Contrast
                                AppThemeMode.LIGHT -> Icons.Outlined.LightMode
                                AppThemeMode.DARK -> Icons.Outlined.DarkMode
                            },
                            null,
                            tint = cs.primary,
                        )
                    },
                    trailingContent = { RadioButton(selected = selected, onClick = null) },
                    colors =
                        ListItemDefaults.colors(
                            containerColor = color,
                            headlineColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
                            supportingColor = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        ),
                )
            }
        }
        Surface(color = cs.surfaceContainerHigh, shape = groupedRowShape(count - 7, count)) {
            ListItem(
                headlineContent = { Text("Device colors") },
                supportingContent = { Text("Use Android’s wallpaper palette") },
                leadingContent = { Icon(Icons.Outlined.Palette, null, tint = cs.primary) },
                trailingContent = {
                    Switch(
                        checked = state.dynamicColor,
                        onCheckedChange = onDynamicColorChange,
                        enabled = supportsDynamicColor,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
        Surface(color = cs.surfaceContainerHigh, shape = groupedRowShape(count - 6, count)) {
            ListItem(
                headlineContent = { Text("Scroll long text") },
                supportingContent = { Text("Animate titles that overflow") },
                leadingContent = { Icon(Icons.Outlined.SwapHoriz, null, tint = cs.primary) },
                trailingContent = { Switch(checked = state.marquee, onCheckedChange = onMarqueeChange) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
        Surface(
            color = cs.surfaceContainerHigh,
            shape = groupedRowShape(count - 5, count),
            modifier =
                Modifier.fillMaxWidth().toggleable(
                    value = state.relativeTime,
                    role = Role.Switch,
                    onValueChange = onRelativeTimeChange,
                ),
        ) {
            ListItem(
                headlineContent = { Text("Relative time") },
                supportingContent = {
                    Text(
                        if (state.relativeTime) "“5m ago” in task lists" else "Exact clock times and dates",
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Schedule, null, tint = cs.primary) },
                trailingContent = { Switch(checked = state.relativeTime, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
        var thinkingMenu by remember { mutableStateOf(false) }
        val thinkingArrowRotation by animateFloatAsState(
            targetValue = if (thinkingMenu) 180f else 0f,
            animationSpec = spring(stiffness = 900f),
            label = "Thinking menu arrow",
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                onClick = { thinkingMenu = !thinkingMenu },
                color = cs.surfaceContainerHigh,
                shape = groupedRowShape(count - 4, count),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text("Thinking animation") },
                    supportingContent = { Text(state.thinking.detail) },
                    leadingContent = { Icon(Icons.Outlined.Animation, null, tint = cs.primary) },
                    trailingContent = {
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            null,
                            modifier = Modifier.graphicsLayer { rotationZ = thinkingArrowRotation },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
                )
            }
            // A popup cannot anchor inside the sheet's own window, so the options open right below the row.
            HydraOverflowMenu(
                thinkingMenu,
                { thinkingMenu = false },
                headerText = "Thinking animation",
                inline = true,
            ) {
                ThinkingAnimation.entries.forEach { option ->
                    HydraMenuItem(
                        option.label,
                        Icons.Outlined.Animation,
                        selected = option == state.thinking,
                        preview = {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                ThinkingIndicator(option)
                            }
                        },
                    ) {
                        thinkingMenu = false
                        onThinkingChange(option)
                    }
                }
            }
        }
        Surface(
            color = cs.surfaceContainerHigh,
            shape = groupedRowShape(count - 3, count),
            modifier =
                Modifier.fillMaxWidth().toggleable(
                    value = state.responseFade,
                    role = Role.Switch,
                    onValueChange = onResponseFadeChange,
                ),
        ) {
            ListItem(
                headlineContent = { Text("Response fade") },
                supportingContent = {
                    Text(
                        if (state.responseFade) "New streamed text fades in" else "Streamed text appears instantly",
                    )
                },
                leadingContent = { Icon(Icons.Outlined.BlurOn, null, tint = cs.primary) },
                trailingContent = { Switch(checked = state.responseFade, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
        Surface(color = cs.surfaceContainerHigh, shape = groupedRowShape(count - 2, count)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // The slider tracks locally and commits once, so dragging does not rebuild the theme tree.
                var sliderMillis by remember(state.fadeMillis) { mutableFloatStateOf(state.fadeMillis.toFloat()) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, null, tint = cs.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Fade duration",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.responseFade) cs.onSurface else cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${sliderMillis.roundToInt()} ms",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                    )
                }
                Slider(
                    value = sliderMillis,
                    onValueChange = { sliderMillis = it },
                    onValueChangeFinished = { onFadeMillisChange(sliderMillis.roundToInt()) },
                    valueRange = MIN_FADE_MILLIS.toFloat()..MAX_FADE_MILLIS.toFloat(),
                    enabled = state.responseFade,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Fade duration" },
                )
            }
        }
        Surface(
            color = cs.surfaceContainerHigh,
            shape = groupedRowShape(count - 1, count),
            modifier =
                Modifier.fillMaxWidth().toggleable(
                    value = state.showHydraTitle,
                    role = Role.Switch,
                    onValueChange = onHydraTitleChange,
                ),
        ) {
            ListItem(
                headlineContent = { Text("Hydra title") },
                supportingContent = {
                    Text(
                        if (state.showHydraTitle) "Show on final summary message" else "Hidden on all messages",
                    )
                },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Label, null, tint = cs.primary) },
                trailingContent = { Switch(checked = state.showHydraTitle, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
    }
}

data class ToolApprovalsUiState(
    val selected: ApprovalMode,
    val enabled: Boolean = true,
)

@Composable
fun ToolApprovalOptions(
    state: ToolApprovalsUiState,
    onSelect: (ApprovalMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ApprovalMode.entries.forEachIndexed { index, mode ->
            val content: @Composable () -> Unit = {
                val cs = MaterialTheme.colorScheme
                val selected = state.selected == mode
                val warning = mode == ApprovalMode.FULL
                val color =
                    if (warning) {
                        cs.tertiaryContainer
                    } else if (selected) {
                        cs.primaryContainer
                    } else {
                        cs.surfaceContainerHigh
                    }
                val foreground =
                    if (warning) {
                        cs.onTertiaryContainer
                    } else if (selected) {
                        cs.onPrimaryContainer
                    } else {
                        cs.onSurface
                    }
                Surface(
                    onClick = { onSelect(mode) },
                    enabled = state.enabled,
                    color = color,
                    shape = groupedRowShape(index, ApprovalMode.entries.size),
                    modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
                ) {
                    ListItem(
                        headlineContent = { Text(mode.title) },
                        supportingContent = { Text(mode.detail) },
                        leadingContent = {
                            Icon(
                                if (warning) {
                                    Icons.Outlined.WarningAmber
                                } else if (mode == ApprovalMode.SESSION) {
                                    Icons.Outlined.Bolt
                                } else {
                                    Icons.Outlined.Shield
                                },
                                null,
                                tint = if (warning) cs.tertiary else cs.primary,
                            )
                        },
                        trailingContent = { if (selected) Icon(Icons.Outlined.Check, "Selected", tint = foreground) },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = color,
                                headlineColor = foreground,
                                supportingColor =
                                    if (warning || selected) {
                                        foreground
                                    } else {
                                        cs.onSurfaceVariant
                                    },
                            ),
                    )
                }
            }
            if (mode == ApprovalMode.FULL) HydraWarningTheme(content) else content()
        }
    }
}

@Preview(name = "Appearance light")
@Preview(name = "Appearance dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Appearance large text", fontScale = 1.5f)
@Composable
private fun AppearanceOptionsPreview() {
    HydraTheme { AppearanceOptions(AppearanceUiState(AppThemeMode.SYSTEM), true, {}, {}, {}) }
}
@Preview(name = "Approvals light")
@Preview(name = "Approvals dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Approvals large text", fontScale = 1.5f)
@Composable
private fun ToolApprovalOptionsPreview() {
    HydraTheme { ToolApprovalOptions(ToolApprovalsUiState(ApprovalMode.CHANGES), {}) }
}
