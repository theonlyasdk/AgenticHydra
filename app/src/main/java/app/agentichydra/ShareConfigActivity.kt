package app.agentichydra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shows the settings bundle as a loop of QR frames a second phone scans. */
class ShareConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HydraTheme { ShareConfigScreen { finish() } } }
    }

    companion object {
        fun open(context: Context) {
            val intent = Intent(context, ShareConfigActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

/** Frames turn often enough for a camera to catch every one, but slowly enough to follow. */
private const val FRAME_INTERVAL_MILLIS = 900L

/** Rendered wide, then scaled into the card, so each module stays crisp on screen. */
private const val QR_RENDER_PIXELS = 900

private const val STEP_CHOOSE = 0
private const val STEP_CODES = 1

/** Above this many frames the dots stop being readable, so a progress bar takes over. */
private const val MAX_FRAME_DOTS = 24

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareConfigScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    var step by rememberSaveable { mutableIntStateOf(STEP_CHOOSE) }
    var selection by remember {
        mutableStateOf(runCatching { ConfigSelectionStore.load(context) }.getOrDefault(ConfigSelection()))
    }
    var frames by remember { mutableStateOf<List<String>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var held by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selection) { runCatching { ConfigSelectionStore.save(context, selection) } }
    // Reading keys and compressing history is real work, so it waits until the codes are asked for.
    LaunchedEffect(step, selection) {
        if (step != STEP_CODES) return@LaunchedEffect
        frames = emptyList()
        failure = null
        building = true
        val result =
            withContext(Dispatchers.Default) {
                runCatching { configQrFrames(configBundle(context, selection)) }
            }
        result
            .onSuccess { frames = it }
            .onFailure { failure = it.message ?: "Your settings could not be read." }
        building = false
    }
    fun chooseAgain() {
        step = STEP_CHOOSE
        held = false
        index = 0
    }
    BackHandler(enabled = step == STEP_CODES) { chooseAgain() }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner, view) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        visible = true
                        view.keepScreenOn = step == STEP_CODES
                    }

                    Lifecycle.Event.ON_STOP -> {
                        visible = false
                        view.keepScreenOn = false
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.keepScreenOn = visible && step == STEP_CODES
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }
    // The loop runs on its own so nobody has to time the two phones against each other.
    LaunchedEffect(visible, held, frames.size, index, step) {
        if (visible && step == STEP_CODES && !held && frames.size > 1) {
            delay(FRAME_INTERVAL_MILLIS)
            index = (index + 1) % frames.size
        }
    }
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Share settings") },
                navigationIcon = {
                    IconButton(
                        onClick = { if (step == STEP_CODES) chooseAgain() else onBack() },
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            SettingsPageColumn(spacing = 16.dp) {
                if (step == STEP_CHOOSE) {
                    Text("What to send", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TransferOptionRow(
                            index = 0,
                            count = 4,
                            icon = Icons.Outlined.Link,
                            title = "Connection",
                            supporting = "Provider, endpoint, model and your saved provider choices",
                            checked = selection.connection,
                            onCheckedChange = { selection = selection.copy(connection = it) },
                        )
                        TransferOptionRow(
                            index = 1,
                            count = 4,
                            icon = Icons.Outlined.Key,
                            title = "API keys",
                            supporting = "The keys stored on this phone, sent in plain text",
                            checked = selection.keys,
                            onCheckedChange = { selection = selection.copy(keys = it) },
                        )
                        TransferOptionRow(
                            index = 2,
                            count = 4,
                            icon = Icons.Outlined.Palette,
                            title = "Appearance and approvals",
                            supporting = "Theme, link handling, and when tasks ask before acting",
                            checked = selection.appearance,
                            onCheckedChange = { selection = selection.copy(appearance = it) },
                        )
                        TransferOptionRow(
                            index = 3,
                            count = 4,
                            icon = Icons.Outlined.Forum,
                            title = "Conversations",
                            supporting = "Every chat and finished task, which can need a lot of codes",
                            checked = selection.conversations,
                            onCheckedChange = { selection = selection.copy(conversations = it) },
                        )
                    }
                    Text(
                        if (selection.any) {
                            "The codes and the exported file carry exactly what is ticked here. Next builds " +
                                "them, which can take a moment."
                        } else {
                            "Tick at least one thing to send. The codes and the exported file carry exactly " +
                                "what is ticked here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Button(
                        onClick = { step = STEP_CODES },
                        enabled = selection.any,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("Next")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                } else {
                    Text("Scan these codes", style = MaterialTheme.typography.titleMedium)
                    when {
                        failure != null -> ErrorBanner(failure.orEmpty())

                        frames.isEmpty() && building ->
                            ShimmerStatusText("Preparing the codes…", style = MaterialTheme.typography.bodyMedium)

                        frames.isEmpty() ->
                            Text(
                                "These settings need more codes than this version shows. Export them to a " +
                                    "file instead — User › Storage › Export writes the same ticks — and open " +
                                    "that file on the other phone.",
                            )

                        else -> {
                            Text(
                                "Hold this screen in front of the other phone, with Hydra open on User › " +
                                    "Storage › Receive settings. The codes repeat on their own, so there is " +
                                    "nothing to time.",
                            )
                            QrCard(
                                field = qrField(),
                                frame = frames[index.coerceIn(frames.indices)],
                                index = index,
                                total = frames.size,
                                held = held,
                                onToggle = { held = !held },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                            Text(
                                when {
                                    frames.size == 1 -> "One code carries the whole setup"
                                    held -> "Holding frame ${index + 1} of ${frames.size} — tap the code to repeat"
                                    else -> "Frame ${index + 1} of ${frames.size} — tap the code to hold it still"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (frames.size > 1) FrameDots(frames.size, index)
                            Text(
                                buildString {
                                    if (selection.keys) append("These codes carry your API keys in plain text. ")
                                    if (selection.conversations) append("They also carry your conversations. ")
                                    append(
                                        "Show them only to a device you trust, and close this screen once " +
                                            "the other phone says the settings are imported.",
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { chooseAgain() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Change what to send") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferOptionRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = groupedRowShape(index, count),
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            leadingContent = { Icon(icon, null, tint = cs.primary) },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
        )
    }
}

/**
 * Scanners read dark modules on a light field. The inverse pair flips with the theme, so pick the
 * combination that keeps the field light in both.
 */
@Composable
internal fun qrField(): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val inverse = cs.surface.luminance() < .5f
    return if (inverse) {
        cs.inverseSurface to cs.inverseOnSurface
    } else {
        cs.surfaceContainerLowest to cs.inverseSurface
    }
}

@Composable
private fun QrCard(
    field: Pair<Color, Color>,
    frame: String,
    index: Int,
    total: Int,
    held: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap =
        remember(frame, field) {
            runCatching { qrBitmap(frame, QR_RENDER_PIXELS, field.first, field.second) }.getOrNull()
        }
    val position = if (total == 1) "Settings QR code" else "Settings QR code, frame ${index + 1} of $total"
    val label = if (held) "$position, held still" else "$position, repeating"
    Surface(
        color = field.first,
        shape = MaterialTheme.shapes.large,
        border = if (held) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 300.dp)
                .aspectRatio(1f)
                .clickable(
                    onClickLabel = if (held) "Repeat the codes" else "Hold this frame still",
                    role = Role.Button,
                    onClick = onToggle,
                ).semantics { contentDescription = label },
    ) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "This frame is too large to draw",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@Composable
private fun FrameDots(
    total: Int,
    active: Int,
) {
    val cs = MaterialTheme.colorScheme
    if (total > MAX_FRAME_DOTS) {
        LinearProgressIndicator(
            progress = { (active + 1f) / total },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { position ->
            Surface(
                color = if (position == active) cs.primary else cs.surfaceContainerHighest,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(if (position == active) 22.dp else 8.dp).height(8.dp),
            ) {}
        }
    }
}

@Preview(name = "Share settings light")
@Preview(name = "Share settings dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Share settings large text", fontScale = 1.5f)
@Composable
private fun ShareConfigPreview() {
    HydraTheme { ShareConfigScreen({}) }
}
