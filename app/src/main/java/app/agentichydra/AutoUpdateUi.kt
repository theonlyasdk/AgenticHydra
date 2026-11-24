package app.agentichydra

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** What the auto-update settings card is doing right now. */
internal sealed interface UpdateStatus {
    /** A release check is in flight. */
    object Checking : UpdateStatus

    /** The release APK is downloading; [fraction] is the fraction of total bytes read so far. */
    data class Downloading(val fraction: Float) : UpdateStatus

    /** A newer release was found and can start downloading. */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateStatus

    /** The update APK is downloaded and ready to install. */
    data class UpdateReady(val info: UpdateInfo) : UpdateStatus

    /** The app is on the latest release. */
    object NoUpdate : UpdateStatus

    /** The last step failed; [message] says why. */
    data class Error(val message: String) : UpdateStatus

    /** Nothing has happened yet. */
    object None : UpdateStatus
}

/** The update offered after a launch-time check: the release, and its downloaded APK. */
internal class UpdateDialogState(val info: UpdateInfo, val apk: File)

/** The "Auto update" row on Settings → About: checks the GitHub release and offers an install. */
@Composable
internal fun AutoUpdateCard() {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(updatesEnabled(context)) }
    var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.None) }
    var showUpdateSheet by remember { mutableStateOf(false) }
    var hasInstallPermission by remember { mutableStateOf(if (inspection) true else canInstallUpdates(context)) }

    if (!inspection) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasInstallPermission = canInstallUpdates(context)
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    fun checkNow() {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        scope.launch(Dispatchers.IO) {
            status = UpdateStatus.Checking
            status =
                when (val result = checkForUpdateResult(context)) {
                    is UpdateCheckResult.Available ->
                        if (stagedUpdateApk(context, result.info) != null) {
                            UpdateStatus.UpdateReady(result.info)
                        } else {
                            UpdateStatus.UpdateAvailable(result.info)
                        }
                    UpdateCheckResult.UpToDate -> UpdateStatus.NoUpdate
                    is UpdateCheckResult.Failed -> UpdateStatus.Error(result.message)
                }
        }
    }

    LaunchedEffect(Unit) {
        if (!inspection && enabled && status == UpdateStatus.None) {
            checkNow()
        }
    }

    fun startDownloadFlow() {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        showUpdateSheet = true
        scope.launch(Dispatchers.IO) {
            val ready = status as? UpdateStatus.UpdateReady
            if (ready != null && stagedUpdateApk(context, ready.info) != null) {
                return@launch
            }
            val availableInfo =
                (status as? UpdateStatus.UpdateAvailable)?.info ?: run {
                    status = UpdateStatus.Checking
                    when (val result = checkForUpdateResult(context)) {
                        is UpdateCheckResult.Available -> result.info
                        UpdateCheckResult.UpToDate -> {
                            status = UpdateStatus.NoUpdate
                            return@launch
                        }
                        is UpdateCheckResult.Failed -> {
                            status = UpdateStatus.Error(result.message)
                            return@launch
                        }
                    }
                }
            val existing = stagedUpdateApk(context, availableInfo)
            if (existing != null) {
                status = UpdateStatus.UpdateReady(availableInfo)
                return@launch
            }
            status = UpdateStatus.Downloading(0f)
            var fraction = 0f
            val apk =
                downloadUpdate(context, availableInfo) { downloaded, total ->
                    val next = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    if (next != fraction) {
                        fraction = next
                        status = UpdateStatus.Downloading(next)
                    }
                }
            if (apk == null) {
                status = UpdateStatus.Error("The update could not be downloaded or verified. Check your connection and try again.")
            } else {
                hasInstallPermission = canInstallUpdates(context)
                status = UpdateStatus.UpdateReady(availableInfo)
            }
        }
    }

    fun installDownloaded() {
        val ready = status as? UpdateStatus.UpdateReady ?: return
        scope.launch(Dispatchers.IO) {
            try {
                installUpdateApk(context, File(context.filesDir, UPDATE_APK_NAME))
                showUpdateSheet = false
            } catch (_: Exception) {
                status = UpdateStatus.Error("The update could not be installed. Check your phone’s install settings and try again.")
            }
        }
    }

    Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListItem(
                headlineContent = { Text("Auto update") },
                supportingContent = { Text("Checks the GitHub release when the app starts.") },
                leadingContent = { Icon(Icons.Outlined.Update, null, tint = cs.primary) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { new ->
                            enabled = new
                            setUpdatesEnabled(context, new)
                            if (new) checkNow()
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
            )
            status.description()?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            when (status) {
                is UpdateStatus.UpdateAvailable ->
                    Button(
                        onClick = { startDownloadFlow() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Download")
                    }
                is UpdateStatus.UpdateReady ->
                    Button(
                        onClick = { showUpdateSheet = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Install")
                    }
                UpdateStatus.Checking, is UpdateStatus.Downloading -> {}
                else ->
                    TextButton(
                        onClick = { checkNow() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Check for updates")
                    }
            }
        }
    }

    if (showUpdateSheet) {
        HydraBottomSheet(
            onDismiss = { showUpdateSheet = false },
            dismissible = false,
        ) { dismiss ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val current = status) {
                    UpdateStatus.Checking -> {
                        Text("Checking for updates", style = MaterialTheme.typography.titleLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is UpdateStatus.Downloading -> {
                        val pct = (current.fraction * 100).roundToInt()
                        Text(
                            if (pct > 0) "Downloading update ($pct%)" else "Downloading update",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (current.fraction > 0f) {
                            LinearProgressIndicator(
                                progress = { current.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateStatus.UpdateReady -> {
                        if (!hasInstallPermission) {
                            Text("Allow installing updates", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Turn on “Install unknown apps” for Hydra in system settings, then return here to install.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                TextButton(
                                    onClick = dismiss,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("Later")
                                }
                                Button(
                                    onClick = {
                                        runCatching { context.startActivity(installPermissionSettingsIntent(context)) }
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("Open settings")
                                }
                            }
                        } else {
                            Text("Ready to install", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Version ${current.info.versionName} is downloaded and ready.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                TextButton(
                                    onClick = dismiss,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("Later")
                                }
                                Button(
                                    onClick = { installDownloaded() },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("Install")
                                }
                            }
                        }
                    }
                    UpdateStatus.NoUpdate -> {
                        Text("Up to date", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "You’re on the latest version.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Button(
                            onClick = dismiss,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Close")
                        }
                    }
                    is UpdateStatus.Error -> {
                        Text("Update failed", style = MaterialTheme.typography.titleLarge)
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Button(
                            onClick = dismiss,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Close")
                        }
                    }
                    else -> {
                        Button(
                            onClick = dismiss,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

private fun UpdateStatus.description(): String? =
    when (this) {
        UpdateStatus.None -> null
        UpdateStatus.Checking -> "Checking for updates…"
        UpdateStatus.NoUpdate -> "You’re on the latest version."
        is UpdateStatus.Downloading ->
            if (fraction > 0f) {
                "Downloading update… (${(fraction * 100).roundToInt()}%)"
            } else {
                "Downloading update…"
            }
        is UpdateStatus.UpdateAvailable -> "Update available: ${info.versionName}"
        is UpdateStatus.UpdateReady -> "Update ready: ${info.versionName}. Tap Install now to update Hydra."
        is UpdateStatus.Error -> message
    }

/** The dialog offered after a launch-time check finds a newer release. */
@Composable
internal fun UpdateDialog(state: UpdateDialogState, onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Text(
                "A new version of Agentic Hydra is available: ${state.info.versionName}. " +
                    "Installing it updates your app with the latest features and fixes.",
            )
        },
        confirmButton = { TextButton(onInstall) { Text("Install now") } },
        dismissButton = { TextButton(onDismiss) { Text("Later") } },
    )
}

/** Prompts the user to allow Hydra to install updates via the system "Install unknown apps" screen. */
@Composable
internal fun InstallPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow installing updates") },
        text = {
            Text(
                "Hydra needs permission to install app updates. Turn on “Install unknown apps” for Hydra " +
                    "in the system settings, then try again.",
            )
        },
        confirmButton = { TextButton(onOpenSettings) { Text("Open settings") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "light", fontScale = 1.5f)
@Preview(name = "dark", fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AutoUpdateCardPreview() {
    HydraTheme { AutoUpdateCard() }
}

@Preview
@Composable
private fun UpdateDialogPreview() {
    HydraTheme {
        UpdateDialog(
            UpdateDialogState(
                UpdateInfo("v1.5.0", "1.5.0", "Agentic-Hydra-release.apk", "https://example.com/hydra.apk"),
                File("/tmp/hydra-update.apk"),
            ),
            onInstall = {},
            onDismiss = {},
        )
    }
}
