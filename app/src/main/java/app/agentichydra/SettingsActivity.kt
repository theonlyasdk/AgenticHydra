package app.agentichydra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LanguageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HydraTheme { SettingsPage("language") { finish() } } }
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page").takeIf { it in setOf("termux", "privacy", "storage", "about", "language") } ?: "about"
        setContent { HydraTheme { SettingsPage(page) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun SettingsPage(page: String, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val title = when (page) {
        "termux" -> "Termux setup"; "privacy" -> "Privacy"; "storage" -> "Storage"; "language" -> "Language"; else -> "About"
    }
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background))
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            var clearAll by remember { mutableStateOf(false) }

            if (page == "termux") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TermuxSetupPage() }
            } else if (page == "about") {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        HydraIllustration(Modifier.size(112.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Agentic Hydra", style = MaterialTheme.typography.hydraTitle)
                        Text("A little intelligence. A human in control.",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(24.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Made with", modifier = Modifier.align(Alignment.CenterVertically))
                            Icon(Icons.Outlined.Favorite, "love", Modifier.size(20.dp).align(Alignment.CenterVertically), tint = cs.primary)
                            Text("by", modifier = Modifier.align(Alignment.CenterVertically))
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/theonlyasdk")))
                            }) {
                                Icon(painterResource(R.drawable.ic_github), "GitHub",
                                    Modifier.size(18.dp).align(Alignment.CenterVertically), tint = LocalContentColor.current)
                                Spacer(Modifier.width(8.dp))
                                Text("theonlyasdk")
                            }
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("© 2026", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Text("Version 1.3.0 · MIT license", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (page == "language") {
                Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LanguageRow("English", selected = true)
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (page) {
                        "privacy" -> {
                            Text("The short version", style = MaterialTheme.typography.titleLarge)
                            Text("Everything you type and every answer you get lives only on this phone. There is no Hydra account, no Hydra server, and no team reading your conversations. To be extra safe, cloud backups and device transfers for this app are switched off.")
                            Text("What actually travels somewhere: when you send a message, it — plus just enough of the recent conversation — is sent to the AI company you picked so it can write a reply. That is how any AI chat app works. If you use a provider you pay for, those messages count toward your usage, and that company’s own privacy policy applies.")
                            Text("When the agent does tasks for you, it may search the web, look up public web pages, and run commands on your phone — but it asks for your permission before every device action, you watch each step in the task chat, and it only touches the project folder you chose for that task.")
                            Text("Your connection settings (like API keys) are encrypted with your phone’s secure hardware and never leave the device. Web searches go to public search services; page requests go to the sites you asked about. Nothing else, and no one else.")
                        }
                        "storage" -> {
                            Text("What’s kept, and where", style = MaterialTheme.typography.titleLarge)
                            Text("Your conversations and task histories are saved on this device only. The app keeps up to 100 chats and 40 task conversations — older ones quietly make room when you go over. Want to keep something for good? Export it from the sidebar first.")
                            Text("Attachments are covered too: text and code files can be up to 128 KiB and images up to 4 MiB, with up to four at a time per message.")
                            Text("Each task remembers its own project folder, and new tasks start in the last folder you used. Deleting a conversation never touches your actual files — those stay right where they are.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { clearAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Clear all chats")
                            }
                        }
                    }
                }
            }

            if (clearAll) AlertDialog(
                onDismissRequest = { clearAll = false },
                title = { Text("Clear all chats?") },
                text = { Text("Every conversation with the agent will be deleted. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        clearAll = false
                        val chat = (context.applicationContext as HydraApplication).models[HydraModel::class.java]
                        chat.clearAllConversations()
                    }) { Text("Delete all") }
                },
                dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable private fun LanguageRow(name: String, selected: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraSmall) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private enum class TermuxSetupPhase { Checking, Install, Permission, Ready, Failed }

internal enum class TermuxIssueAction { OPEN_TERMUX, INSTALL_GUIDE }

internal data class TermuxIssue(val title: String, val description: String, val action: TermuxIssueAction? = null)

internal fun diagnoseTermuxFailure(raw: String): TermuxIssue? {
    val message = raw.lowercase()
    return when {
        "app is in background" in message || "not allowed to start service" in message || "background execution not allowed" in message ->
            TermuxIssue(
                "Termux is offline",
                "Android blocked the command because Termux isn’t running right now. Start Termux and keep it open in the background, then try again.",
                TermuxIssueAction.OPEN_TERMUX
            )
        "allow-external-apps" in message && !message.contains("could not execute this command") ->
            TermuxIssue(
                "External commands are disabled",
                "Termux refuses commands from other apps until allow-external-apps is enabled. Run the commands in the setup guide inside Termux, then try again."
            )
        "did not return a result within" in message || "three minutes" in message ->
            TermuxIssue(
                "Termux never answered",
                "The command ran, but Termux never reported back, so it may have closed mid-run. Open Termux to check its sessions, then try again.",
                TermuxIssueAction.OPEN_TERMUX
            )
        ("does not exist" in message || "no such file" in message || "not found" in message)
            && ("bash" in message || "/data/data/com.termux" in message) ->
            TermuxIssue(
                "Termux tools are missing",
                "Termux is installed, but its command-line environment isn’t. Reinstall Termux from F-Droid or its official GitHub releases, then run the setup-guide commands.",
                TermuxIssueAction.INSTALL_GUIDE
            )
        else -> null
    }
}

private data class TermuxSetupUiState(
    val phase: TermuxSetupPhase = TermuxSetupPhase.Checking,
    val detail: String = "",
    val granted: Boolean = false,
    val issue: TermuxIssue? = null
)

@Composable internal fun TermuxSetupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(TermuxSetupUiState()) }
    var probeJob by remember { mutableStateOf<Job?>(null) }

    fun startProbe() {
        probeJob?.cancel()
        state = TermuxSetupUiState(TermuxSetupPhase.Checking)
        probeJob = scope.launch { state = runTermuxProbe(context) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startProbe() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && probeJob?.isActive != true) startProbe()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.phase == TermuxSetupPhase.Checking) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TermuxCheckingStatus() }
        } else {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                TermuxSetupContent(
                    state,
                    onGrant = { permission.launch("com.termux.permission.RUN_COMMAND") },
                    onInstall = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app#installation")))
                    },
                    onOpenTermux = {
                        val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
                        runCatching {
                            if (launch != null) context.startActivity(launch)
                            else context.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.termux"))
                            )
                        }
                    },
                    onRecheck = { startProbe() }
                )
            }
        }
    }
}

private suspend fun runTermuxProbe(context: Context): TermuxSetupUiState {
    val granted = TermuxBridge.allowed(context)
    if (!TermuxBridge.installed(context)) return TermuxSetupUiState(TermuxSetupPhase.Install, granted = granted)
    if (!granted) return TermuxSetupUiState(TermuxSetupPhase.Permission)
    return try {
        val result = TermuxBridge.execute(context, "", "echo Hydra ready")
        if (result.exitCode == 0 && result.stdout.contains("Hydra")) {
            TermuxSetupUiState(TermuxSetupPhase.Ready, granted = true)
        } else {
            termuxFailure("`echo` exited with code ${result.exitCode}: " + result.stderr.ifBlank { "no output" }, granted)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        termuxFailure(e.message ?: "Termux did not respond.", granted)
    }
}

private fun termuxFailure(raw: String, granted: Boolean) =
    TermuxSetupUiState(TermuxSetupPhase.Failed, raw, granted, diagnoseTermuxFailure(raw))

@Composable private fun TermuxSetupContent(
    state: TermuxSetupUiState,
    onGrant: () -> Unit,
    onInstall: () -> Unit,
    onOpenTermux: () -> Unit,
    onRecheck: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expandedFaq by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(color = cs.surfaceContainer, shape = MaterialTheme.shapes.extraLarge) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state.phase) {
                    TermuxSetupPhase.Ready -> {
                        Surface(shape = CircleShape, color = cs.primaryContainer) {
                            Icon(Icons.Outlined.Terminal, "Termux is ready", Modifier.size(56.dp).padding(14.dp), tint = cs.onPrimaryContainer)
                        }
                        Text("Termux is ready", style = MaterialTheme.typography.headlineSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(16.dp), tint = cs.primary)
                                Text("Termux detected", style = MaterialTheme.typography.labelLarge)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(16.dp), tint = cs.primary)
                                Text("Echo test passed", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        TermuxGrantButton(state.granted, primary = false, onGrant)
                    }
                    TermuxSetupPhase.Install -> {
                        Surface(shape = CircleShape, color = cs.surfaceContainerHigh) {
                            Icon(Icons.Outlined.Terminal, "Termux is missing", Modifier.size(56.dp).padding(14.dp), tint = cs.onSurfaceVariant)
                        }
                        Text("Termux is missing", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Hydra runs bash, Git, and other command-line tools through Termux right on this phone.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Install Termux") }
                        TermuxGrantButton(state.granted, primary = false, onGrant)
                    }
                    TermuxSetupPhase.Permission -> {
                        Surface(shape = CircleShape, color = cs.surfaceContainerHigh) {
                            Icon(Icons.Outlined.Shield, "Run command permission", Modifier.size(56.dp).padding(14.dp), tint = cs.onSurfaceVariant)
                        }
                        Text("One permission away", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Termux is installed. Hydra needs to be allowed to start commands inside it.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                        TermuxGrantButton(state.granted, primary = true, onGrant)
                    }
                    TermuxSetupPhase.Failed -> {
                        val issue = state.issue
                        Surface(shape = CircleShape, color = cs.errorContainer) {
                            Icon(Icons.Outlined.Error, issue?.title ?: "Echo test failed", Modifier.size(56.dp).padding(14.dp), tint = cs.onErrorContainer)
                        }
                        Text(issue?.title ?: "Termux did not respond", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            issue?.description ?: state.detail.ifBlank { "Termux did not respond. Check that it opens normally, then try again." },
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                        when (issue?.action) {
                            TermuxIssueAction.OPEN_TERMUX -> Button(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open Termux")
                            }
                            TermuxIssueAction.INSTALL_GUIDE -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Install Termux")
                            }
                            null -> {}
                        }
                        if (issue?.action == null) {
                            Button(onClick = onRecheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Try again")
                            }
                        } else {
                            OutlinedButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Try again")
                            }
                        }
                        TermuxGrantButton(state.granted, primary = false, onGrant)
                    }
                    TermuxSetupPhase.Checking -> {}
                }
            }
        }

        if (state.phase != TermuxSetupPhase.Ready) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.School, null, tint = cs.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Setup guide", style = MaterialTheme.typography.titleLarge)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Install and open Termux, then run:", style = MaterialTheme.typography.bodyMedium)
                    MarkdownBody("```bash\ntermux-setup-storage\nmkdir -p ~/.termux\necho 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings\n```")
                    Text(
                        "Allow storage access, then install the tools your project needs, such as Git, Python, or Node.js.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Text("FAQ", style = MaterialTheme.typography.titleLarge)
            }
            val faqs = listOf(
                "What needs Termux?" to "Only shell tools: run command, Git status, and Git diff. Chat, project files, web search, device tools, questions, and scheduling all work without Termux.",
                "Why does Hydra need a permission?" to "Android requires an explicit grant before another app can start commands inside Termux. You can revoke it at any time from Android’s app settings.",
                "Where should I install Termux from?" to "F-Droid and Termux’s official GitHub releases both publish the full app. The Install Termux button opens that guide.",
                "The echo test failed. Now what?" to "Open Termux once, run the setup-guide commands, and make sure allow-external-apps is true in termux.properties, then press Try again.",
                "What happens when I stop a task?" to "Hydra stops sending new commands, but a process already started in Termux keeps running until you stop it from a Termux session."
            )
            Column(Modifier.fillMaxWidth()) {
                faqs.forEachIndexed { index, (question, answer) ->
                    TermuxFaqRow(index, faqs.size, question, answer, expandedFaq == index) {
                        expandedFaq = if (expandedFaq == index) null else index
                    }
                }
            }
        }

        Text(
            "Commands inherit Termux’s file and network permissions. Stopping a task prevents further steps; commands already running may need to be stopped in Termux.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
    }
}

@Composable private fun TermuxGrantButton(allowed: Boolean, primary: Boolean, onGrant: () -> Unit) {
    if (allowed) {
        OutlinedButton(onClick = onGrant, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Command permission granted")
        }
    } else if (primary) {
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Grant command permission")
        }
    } else {
        OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Grant command permission")
        }
    }
}

@Composable private fun TermuxFaqRow(index: Int, count: Int, question: String, answer: String, open: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = 380f, dampingRatio = .75f), label = "Faq chevron")
    Surface(onClick = onToggle, color = cs.surfaceContainerHigh, shape = groupedRowShape(index, count)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.KeyboardArrowDown, if (open) "Hide answer" else "Show answer",
                    Modifier.size(20.dp).graphicsLayer { rotationZ = chevron }, tint = cs.onSurfaceVariant)
            }
            AnimatedVisibility(
                open,
                enter = expandVertically(spring(stiffness = 380f, dampingRatio = .85f)) + fadeIn(spring()),
                exit = shrinkVertically(spring(stiffness = 380f, dampingRatio = .85f)) + fadeOut(spring())
            ) {
                Text(answer, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable private fun TermuxCheckingStatus() {
    val messages = remember { listOf("Looking for Termux…", "Running a quick echo test…", "This usually takes a few seconds…") }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2600); index = (index + 1) % messages.size } }
    TermuxCheckingStatusContent(messages[index])
}

@Composable private fun TermuxCheckingStatusContent(message: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        HydraLoadingIndicator(64.dp, cs.primary, cs.background)
        ShimmerStatusText(message, style = MaterialTheme.typography.titleLarge)
    }
}

@Preview(name = "Termux checking light")
@Preview(name = "Termux checking dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Termux checking large text", fontScale = 1.5f)
@Composable private fun TermuxCheckingPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TermuxCheckingStatusContent("Running a quick echo test…")
            }
        }
    }
}

@Preview(name = "Termux ready light")
@Preview(name = "Termux ready dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Termux ready large text", fontScale = 1.5f)
@Composable private fun TermuxReadyPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(vertical = 48.dp)) {
                TermuxSetupContent(TermuxSetupUiState(TermuxSetupPhase.Ready, granted = true), {}, {}, {}, {})
            }
        }
    }
}

@Preview(name = "Termux issue light")
@Preview(name = "Termux issue dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Termux issue large text", fontScale = 1.5f)
@Composable private fun TermuxIssuePreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(vertical = 48.dp)) {
                TermuxSetupContent(
                    TermuxSetupUiState(
                        TermuxSetupPhase.Failed, granted = true,
                        issue = TermuxIssue(
                            "Termux is offline",
                            "Android blocked the command because Termux isn’t running right now. Start Termux and keep it open in the background, then try again.",
                            TermuxIssueAction.OPEN_TERMUX
                        )
                    ), {}, {}, {}, {}
                )
            }
        }
    }
}

@Preview(name = "About light")
@Preview(name = "About dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "About large text", fontScale = 1.5f)
@Composable private fun SettingsPagePreview() {
    HydraTheme { SettingsPage("about", {}) }
}
