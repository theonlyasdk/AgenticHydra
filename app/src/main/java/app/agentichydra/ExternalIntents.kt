package app.agentichydra

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val TOOL_PREFS = "external_links"
private const val CONFIRM_KEY = "confirm"
private const val ALLOW_HTTP_KEY = "allow_http"
private const val BUILTIN_BROWSER_KEY = "builtin_browser"

private fun toolPrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)

/** Hands escapes from Hydra to other apps only after the user says yes. */
internal fun confirmsExternalIntents(context: Context): Boolean = toolPrefs(context).getBoolean(CONFIRM_KEY, true)

internal fun setConfirmExternalIntents(
    context: Context,
    confirm: Boolean,
) {
    toolPrefs(context).edit().putBoolean(CONFIRM_KEY, confirm).apply()
}

/** Web tools may fetch plain-http pages when enabled; HTTPS-only otherwise. */
internal fun allowsHttpWebTools(context: Context): Boolean = toolPrefs(context).getBoolean(ALLOW_HTTP_KEY, false)

internal fun setAllowHttpWebTools(
    context: Context,
    allow: Boolean,
) {
    toolPrefs(context).edit().putBoolean(ALLOW_HTTP_KEY, allow).apply()
}

/** Web links open in Hydra's own browser unless the user prefers the system browser. */
internal fun usesBuiltInBrowser(context: Context): Boolean = toolPrefs(context).getBoolean(BUILTIN_BROWSER_KEY, true)

internal fun setBuiltInBrowser(
    context: Context,
    builtIn: Boolean,
) {
    toolPrefs(context).edit().putBoolean(BUILTIN_BROWSER_KEY, builtIn).apply()
}

@Composable
internal fun rememberConfirmExternalIntents(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE) }
    var confirm by remember(prefs) { mutableStateOf(prefs.getBoolean(CONFIRM_KEY, true)) }
    DisposableEffect(prefs) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == CONFIRM_KEY) confirm = prefs.getBoolean(CONFIRM_KEY, true)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return confirm
}

internal data class ToolSettingsUiState(
    val confirmExternalIntents: Boolean = true,
    val allowHttp: Boolean = false,
    val builtInBrowser: Boolean = true,
)

@Composable
internal fun rememberToolSettings(): ToolSettingsUiState {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE) }

    fun read() =
        ToolSettingsUiState(
            prefs.getBoolean(CONFIRM_KEY, true),
            prefs.getBoolean(ALLOW_HTTP_KEY, false),
            prefs.getBoolean(BUILTIN_BROWSER_KEY, true),
        )

    var state by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

internal data class ExternalIntentRequest(
    val icon: ImageVector,
    val message: String,
    val detail: String?,
    val question: String,
    val isUrl: Boolean = false,
    val action: () -> Unit,
)

/** One pending confirmation; hosted once per screen so any call site can ask. */
@Stable
internal class ExternalIntentPrompt {
    internal var pending by mutableStateOf<ExternalIntentRequest?>(null)
        private set

    internal fun ask(request: ExternalIntentRequest) {
        pending = request
    }

    internal fun cancel() {
        pending = null
    }
}

@Composable
internal fun rememberExternalIntentPrompt(): ExternalIntentPrompt = remember { ExternalIntentPrompt() }

internal val LocalExternalIntentPrompt = staticCompositionLocalOf<ExternalIntentPrompt?> { null }

/** Runs [action] now, or parks it behind the confirmation sheet. */
internal fun openExternalIntent(
    context: Context,
    prompt: ExternalIntentPrompt?,
    icon: ImageVector,
    message: String,
    detail: String?,
    question: String,
    isUrl: Boolean = false,
    action: () -> Unit,
) {
    if (prompt == null || !confirmsExternalIntents(context)) {
        action()
    } else {
        prompt.ask(ExternalIntentRequest(icon, message, detail, question, isUrl, action))
    }
}

internal fun shareText(
    context: Context,
    prompt: ExternalIntentPrompt?,
    title: String,
    text: String,
    mime: String = "text/plain",
    detail: String = title,
) {
    openExternalIntent(
        context,
        prompt,
        Icons.Outlined.Share,
        "This will be shared with another app.",
        detail,
        "Share it?",
    ) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, title)) }
            .onFailure { Toast.makeText(context, "No app can share this.", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
internal fun ExternalIntentHost(prompt: ExternalIntentPrompt) {
    val request = prompt.pending ?: return
    HydraBottomSheet(
        onDismiss = prompt::cancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { dismiss ->
        ExternalIntentConfirmation(request) { dismiss() }
    }
}

@Composable
private fun ExternalIntentConfirmation(
    request: ExternalIntentRequest,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = cs.secondaryContainer) {
            Icon(
                request.icon,
                null,
                Modifier.size(56.dp).padding(14.dp),
                tint = cs.onSecondaryContainer,
            )
        }
        Text(
            request.message,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        request.detail?.let { detail ->
            if (request.isUrl) {
                ExternalUrlDetail(detail)
            } else {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(request.question, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("No") }
            Button(
                onClick = {
                    onDismiss()
                    request.action()
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Yes") }
        }
    }
}

@Composable
private fun ExternalUrlDetail(url: String) {
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val available = with(density) { maxWidth.roundToPx() }
        val label = remember(url, style, measurer, available) {
            fun fits(value: String): Boolean =
                measurer.measure(value, style, maxLines = 1, softWrap = false).size.width <= available

            if (fits(url)) {
                url
            } else {
                var low = 0
                var high = url.codePointCount(0, url.length) - 1
                while (low < high) {
                    val keep = (low + high + 1) / 2
                    val start = url.offsetByCodePoints(0, (keep + 1) / 2)
                    val end = url.offsetByCodePoints(url.length, -(keep / 2))
                    if (fits(url.take(start) + "?" + url.substring(end))) low = keep else high = keep - 1
                }
                val start = url.offsetByCodePoints(0, (low + 1) / 2)
                val end = url.offsetByCodePoints(url.length, -(low / 2))
                url.take(start) + "?" + url.substring(end)
            }
        }
        Text(
            label,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(url) },
        )
    }
}

@Composable
internal fun ToolSettingsOptions(
    state: ToolSettingsUiState,
    onConfirmChange: (Boolean) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onBuiltInBrowserChange: (Boolean) -> Unit,
    keepTasksRunning: Boolean,
    onKeepTasksRunningChange: (Boolean) -> Unit,
    onNotificationSummaries: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToolToggleRow(
            shape = groupedRowShape(0, 5),
            icon = Icons.Outlined.Shield,
            title = "Ask before opening",
            detail =
                if (state.confirmExternalIntents) {
                    "Confirm links, shares, and downloads that leave Hydra."
                } else {
                    "Hand links, shares, and downloads straight to other apps."
                },
            checked = state.confirmExternalIntents,
            onChange = onConfirmChange,
        )
        ToolToggleRow(
            shape = groupedRowShape(1, 5),
            icon = Icons.Outlined.Http,
            title = "Allow HTTP websites",
            detail =
                if (state.allowHttp) {
                    "Web tools may read plain-http pages."
                } else {
                    "Web tools read only public HTTPS pages."
                },
            checked = state.allowHttp,
            onChange = onAllowHttpChange,
        )
        ToolToggleRow(
            shape = groupedRowShape(2, 5),
            icon = Icons.Outlined.Language,
            title = "Built-in browser",
            detail =
                if (state.builtInBrowser) {
                    "Open links inside Hydra."
                } else {
                    "Open links in your default browser."
                },
            checked = state.builtInBrowser,
            onChange = onBuiltInBrowserChange,
        )
        ToolToggleRow(
            shape = groupedRowShape(3, 5),
            icon = Icons.Outlined.PlayCircleOutline,
            title = "Keep tasks running in background",
            detail = "Show progress and a Stop button in an ongoing notification.",
            checked = keepTasksRunning,
            onChange = onKeepTasksRunningChange,
        )
        Surface(
            onClick = onNotificationSummaries,
            modifier = Modifier.fillMaxWidth(),
            shape = groupedRowShape(4, 5),
            color = cs.surfaceContainerHigh,
        ) {
            ListItem(
                headlineContent = { Text("Notification summaries") },
                supportingContent = { Text("Choose which apps Hydra summarizes.") },
                leadingContent = { Icon(Icons.Outlined.Notifications, null, tint = cs.primary) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Web tools still block private and local network addresses.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolToggleRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = shape,
        modifier =
            Modifier.fillMaxWidth().toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            ),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(detail) },
            leadingContent = { Icon(icon, null, tint = cs.primary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
        )
    }
}

/** Settings sheet for external escapes, web-tool transport, and the in-app browser. */
@Composable
internal fun ToolSettingsSheet(
    keepTasksRunning: Boolean,
    onKeepTasksRunningChange: (Boolean) -> Unit,
    onNotificationSummaries: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberToolSettings()
    HydraBottomSheet(onDismiss = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) { _ ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            SheetHeader(Icons.Outlined.Build, "Tool settings")
            Spacer(Modifier.height(16.dp))
            ToolSettingsOptions(
                state,
                onConfirmChange = { setConfirmExternalIntents(context, it) },
                onAllowHttpChange = { setAllowHttpWebTools(context, it) },
                onBuiltInBrowserChange = { setBuiltInBrowser(context, it) },
                keepTasksRunning = keepTasksRunning,
                onKeepTasksRunningChange = onKeepTasksRunningChange,
                onNotificationSummaries = onNotificationSummaries,
            )
        }
    }
}

@Preview(name = "External intent light")
@Preview(name = "External intent dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "External intent large text", fontScale = 1.5f)
@Composable
private fun ExternalIntentConfirmationPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            ExternalIntentConfirmation(
                ExternalIntentRequest(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    "This link opens in another app.",
                    "https://developer.android.com/develop/ui/compose",
                    "Open it?",
                    isUrl = true,
                ) {},
            ) {}
        }
    }
}

@Preview(name = "Tool settings light")
@Preview(name = "Tool settings dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Tool settings large text", fontScale = 1.5f)
@Composable
private fun ToolSettingsPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(16.dp)) {
                ToolSettingsOptions(ToolSettingsUiState(), {}, {}, {}, true, {}, {})
            }
        }
    }
}
