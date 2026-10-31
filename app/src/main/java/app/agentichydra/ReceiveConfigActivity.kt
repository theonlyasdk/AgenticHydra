package app.agentichydra

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/** Imports the settings bundle another phone is showing as a loop of QR frames. */
class ReceiveConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HydraTheme { ReceiveConfigScreen { finish() } } }
    }

    companion object {
        fun open(context: Context) {
            val intent = Intent(context, ReceiveConfigActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

private enum class ScanPhase { Permission, Denied, Scanning, Review, Applying, Applied, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiveConfigScreen(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(ScanPhase.Permission) }
    var collected by remember { mutableStateOf(0 to 0) }
    var summary by remember { mutableStateOf<ConfigSummary?>(null) }
    var bundle by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val collector = remember(attempt) { ConfigFrameCollector() }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            phase = if (granted) ScanPhase.Scanning else ScanPhase.Denied
        }
    LaunchedEffect(Unit) { permission.launch(Manifest.permission.CAMERA) }

    fun collect(code: String) {
        val progress = collector.accept(code) ?: return
        val finished = progress.bundle
        if (finished == null) {
            if (progress.frames < progress.total) {
                collected = progress.frames to progress.total
            } else {
                failure = "Those codes stopped making sense. Scan again, holding both screens steady."
                phase = ScanPhase.Failed
            }
            return
        }
        runCatching { readConfigSummary(finished) }
            .onSuccess {
                collected = progress.total to progress.total
                summary = it
                bundle = finished
                phase = ScanPhase.Review
            }.onFailure {
                failure = it.message ?: "That bundle could not be understood."
                phase = ScanPhase.Failed
            }
    }

    fun apply() {
        val received = bundle ?: return
        phase = ScanPhase.Applying
        scope.launch {
            runCatching { applyConfig(context.applicationContext, received) }
                .onSuccess { phase = ScanPhase.Applied }
                .onFailure {
                    failure = it.message ?: "Those settings could not be applied."
                    phase = ScanPhase.Failed
                }
        }
    }

    fun scanAgain() {
        attempt++
        collected = 0 to 0
        summary = null
        bundle = null
        failure = null
        phase = ScanPhase.Scanning
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Receive settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onDone,
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            SettingsPageColumn(spacing = 16.dp) {
                when (phase) {
                    ScanPhase.Permission -> CameraWaiting("Waiting for the camera permission…")

                    ScanPhase.Denied -> CameraWaiting(
                        "Hydra needs the camera to read a settings code. Allow it in Android’s app settings, " +
                            "then try again.",
                        action = { permission.launch(Manifest.permission.CAMERA) },
                    )

                    ScanPhase.Scanning -> {
                        Text(
                            "Point the camera at the other phone’s Share settings screen. Scanning starts on its " +
                                "own when a Hydra code appears and stops when the last frame lands — the codes " +
                                "keep repeating, so a missed frame is caught on the next pass.",
                        )
                        QrCamera { code -> scope.launch { collect(code) } }
                        ScanProgress(collected.first, collected.second)
                    }

                    ScanPhase.Review, ScanPhase.Applying -> {
                        ImportedPreview(summary)
                        if (phase == ScanPhase.Applying) {
                            BusyStatusRow { ShimmerStatusText("Applying the settings…") }
                        }
                    }

                    ScanPhase.Applied -> {
                        Surface(
                            color = cs.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircleOutline,
                                    null,
                                    Modifier.size(48.dp),
                                    tint = cs.primary,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("Settings imported", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Connections, models, keys and appearance now match the other phone.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                            }
                        }
                    }

                    ScanPhase.Failed -> {
                        ErrorBanner(failure ?: "Those settings could not be read.")
                        OutlinedButton(onClick = ::scanAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Scan again")
                        }
                    }
                }
            }
        }
    }
    if (phase == ScanPhase.Review) {
        ConfirmDialog(
            title = "Import these settings?",
            body = summary?.body ?: "The other phone sent a settings bundle.",
            confirmLabel = "Import",
            onConfirm = { apply() },
            onDismiss = { scanAgain() },
        )
    }
}

@Composable
private fun ScanProgress(
    frames: Int,
    total: Int,
) {
    val cs = MaterialTheme.colorScheme
    val label =
        when {
            total == 0 -> "Waiting for the other phone’s settings code…"
            total == 1 -> "Reading the code…"
            else -> "Collected $frames of $total frames"
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShimmerStatusText(label, style = MaterialTheme.typography.labelLarge)
        if (total > 1) {
            LinearProgressIndicator(
                progress = { frames.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun ImportedPreview(summary: ConfigSummary?) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All frames received", style = MaterialTheme.typography.titleMedium)
            Text(
                summary?.body ?: "The bundle was read, but could not be understood.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraWaiting(
    message: String,
    action: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.CameraAlt, null, Modifier.size(40.dp), tint = cs.onSurfaceVariant)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            OutlinedButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
        }
    }
}

/** Back-camera preview that decodes every frame it receives and reports QR payloads. */
@Composable
private fun QrCamera(onFrame: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener(
            {
                val instance = runCatching { provider.get() }.getOrNull() ?: return@addListener
                val preview =
                    // CameraX's Preview is aliased because the Compose @Preview import claims that name.
                    CameraPreview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                analysis.setAnalyzer(executor) { frame ->
                    val code = runCatching { decodeQrText(frame.toBitmap()) }.getOrNull()
                    frame.close()
                    if (code != null) onFrame(code)
                }
                instance.unbindAll()
                camera =
                    runCatching {
                        instance.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.getOrNull()
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // Unbinding is what actually releases the camera; the preview keeps it otherwise.
            runCatching { provider.get().unbindAll() }
            executor.shutdown()
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val target = camera?.cameraControl ?: return@awaitEachGesture
                        val point =
                            previewView
                                .meteringPointFactory
                                .createPoint(down.position.x, down.position.y)
                        runCatching {
                            target.startFocusAndMetering(
                                FocusMeteringAction
                                    .Builder(
                                        point,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                    ).build(),
                            )
                        }
                    }
                },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Text(
                "Tap the view to focus on the screen you are scanning",
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(name = "Receive settings light")
@Preview(name = "Receive settings dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Receive settings large text", fontScale = 1.5f)
@Composable
private fun ReceiveConfigPreview() {
    HydraTheme { CameraWaiting("Hydra needs the camera to read a settings code.", action = {}) }
}
