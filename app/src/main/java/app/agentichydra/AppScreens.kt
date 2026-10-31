package app.agentichydra

import android.content.Intent
import android.os.Build
import app.agentichydra.mcp.McpStatus
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun HydraApp() {
    val context = LocalContext.current
    val application = context.applicationContext as HydraApplication
    val chat = application.models[HydraModel::class.java]
    val work = application.models[WorkModel::class.java]
    var destination by rememberSaveable { mutableStateOf("Chat") }
    val linkSheet = rememberLinkSheet()
    val externalPrompt = rememberExternalIntentPrompt()
    val openLink: (String) -> Unit = remember(context, linkSheet) {
        { url -> openInLinkSheet(context, url, linkSheet, externalPrompt) }
    }
    CompositionLocalProvider(
        LocalLinkOpener provides openLink,
        LocalExternalIntentPrompt provides externalPrompt,
    ) {
        // Preserve the bar in composition so Material animates each selection change.
        Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
            HydraNavigation(destination) { destination = it }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (destination) {
                    "Work" -> WorkScreen(chat, work)
                    "User" -> UserScreen(chat, work)
                    else -> ChatScreen(chat)
                }
            }
        }
        LinkSheet(linkSheet)
        ExternalIntentHost(externalPrompt)
    }
}

@Composable
private fun HydraNavigation(
    selected: String,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        listOf(
            "Chat" to Icons.Outlined.ChatBubbleOutline,
            "Work" to Icons.Outlined.Terminal,
            "User" to Icons.Outlined.PersonOutline,
        ).forEach { (label, icon) ->
            NavigationBarItem(selected = selected == label, onClick = {
                onNavigate(label)
            }, icon = {
                Icon(icon, null)
            }, label = {
                Text(
                    label,
                )
            }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
        }
    }
}

@Composable
fun ApprovalSheet(
    work: WorkModel,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf<ApprovalMode?>(null) }
    HydraBottomSheet(onDismiss = onDismiss, containerColor = cs.surfaceContainer) { dismiss ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(Icons.Outlined.Shield, "Tool approvals")
            ToolApprovalOptions(ToolApprovalsUiState(work.mode, !work.running)) { mode ->
                if (mode in setOf(ApprovalMode.SESSION, ApprovalMode.FULL)) {
                    confirm = mode
                } else {
                    work.mode = mode
                    dismiss()
                }
            }
        }
        confirm?.let { mode ->
            val isFullAccess = mode == ApprovalMode.FULL
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = {
                    Text(
                        if (isFullAccess) {
                            "Enable full access?"
                        } else {
                            "Allow tools for the next task?"
                        },
                    )
                },
                text = {
                    Text(
                        "The agent can write files and run commands without further approval. " +
                            "Shell commands inherit Termux’s file and network access. " +
                            if (isFullAccess) {
                                "This choice stays enabled until you change it."
                            } else {
                                "Approval resets after the task."
                            },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        work.mode = mode
                        confirm = null
                        dismiss()
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirm = null
                    }) { Text("Cancel") }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    chat: HydraModel,
    work: WorkModel,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val application = context.applicationContext as HydraApplication
    val mcpStates by application.mcpRegistry.states.collectAsState()
    val mcpSummary =
        when {
            mcpStates.isEmpty() -> "Set up"
            else -> {
                val connected = mcpStates.values.count { it.status == McpStatus.CONNECTED }
                if (connected > 0) {
                    "$connected connected"
                } else {
                    "${mcpStates.size} server${if (mcpStates.size == 1) "" else "s"}"
                }
            }
        }
    val appearanceState = rememberAppearanceState()
    val confirmExternal = rememberConfirmExternalIntents()
    var appearance by rememberSaveable { mutableStateOf(false) }
    var connections by rememberSaveable { mutableStateOf(false) }
    var approvals by rememberSaveable { mutableStateOf(false) }
    var externalLinks by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(TermuxBridge.allowed(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    allowed = TermuxBridge.allowed(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun page(name: String) {
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("page", name))
    }
    Scaffold(containerColor = cs.background, topBar = {
        TopAppBar(title = {
            Text("User", style = MaterialTheme.typography.titleLarge)
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background))
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    HydraIllustration(Modifier.size(88.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Agentic Hydra", style = MaterialTheme.typography.hydraTitle)
                }
            }
            item {
                SettingsGroup {
                    SettingsEntry(
                        Icons.Outlined.Hub,
                        "Connections",
                        Provider.entries
                            .filter {
                                chat.savedKey(it).isNotBlank()
                            }.map { it.label }
                            .ifEmpty { listOf("None") }
                            .joinToString(", "),
                    ) {
                        connections = true
                    }
                    GroupDivider()
                    val advanced = rememberAdvancedModelSettings()
                    SettingsEntry(
                        Icons.Outlined.Tune,
                        "Provider settings",
                        when {
                            advanced.preventRateLimits && advanced.resumeOnRateLimit -> "Auto"
                            advanced.preventRateLimits -> "Paced"
                            advanced.resumeOnRateLimit -> "Resume"
                            else -> "Off"
                        },
                    ) {
                        context.startActivity(Intent(context, AdvancedModelActivity::class.java))
                    }
                    GroupDivider()
                    SettingsEntry(
                        Icons.Outlined.Terminal,
                        "Termux",
                        if (allowed) "Connected" else "Set up",
                    ) { page("termux") }
                    GroupDivider()
                    SettingsEntry(
                        Icons.Outlined.Hub,
                        "MCP servers",
                        mcpSummary,
                    ) { page("mcp") }
                    GroupDivider()
                    SettingsEntry(Icons.Outlined.Shield, "Tool approvals", work.mode.title) { approvals = true }
                    GroupDivider()
                    SettingsEntry(
                        Icons.Outlined.Build,
                        "Tool settings",
                        if (confirmExternal) "Ask first" else "Open directly",
                    ) { externalLinks = true }
                }
            }
            item {
                SettingsGroup {
                    SettingsEntry(Icons.Outlined.Contrast, "Appearance", appearanceState.themeMode.label) {
                        appearance = true
                    }
                    GroupDivider()
                    SettingsEntry(Icons.Outlined.Language, "Language", "English") { page("language") }
                    GroupDivider()
                    SettingsEntry(Icons.Outlined.PrivacyTip, "Privacy", null) { page("privacy") }
                    GroupDivider()
                    SettingsEntry(Icons.Outlined.Storage, "Storage", null) { page("storage") }
                    GroupDivider()
                    SettingsEntry(Icons.Outlined.Info, "Version", "1.3.0") { page("about") }
                }
            }
        }
    }
    if (appearance) {
        HydraBottomSheet(onDismiss = { appearance = false }, containerColor = cs.surfaceContainerLow) { _ ->
            val prefs = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SheetHeader(Icons.Outlined.Contrast, "Appearance")
                AppearanceOptions(
                    appearanceState,
                    Build.VERSION.SDK_INT >= 31,
                    onThemeChange = { prefs.edit().putString("mode", it.name).apply() },
                    onDynamicColorChange = { prefs.edit().putBoolean("dynamic", it).apply() },
                    onMarqueeChange = { prefs.edit().putBoolean("marquee", it).apply() },
                    onHydraTitleChange = { prefs.edit().putBoolean("show_hydra_title", it).apply() },
                    onRelativeTimeChange = { prefs.edit().putBoolean("relative_time", it).apply() },
                    onThinkingChange = { prefs.edit().putString("thinking_animation", it.name).apply() },
                    onResponseFadeChange = { prefs.edit().putBoolean("response_fade", it).apply() },
                    onFadeMillisChange = { prefs.edit().putInt("fade_millis", it).apply() },
                )
            }
        }
    }
    if (connections) ConnectionSheet(chat) { connections = false }
    if (approvals) ApprovalSheet(work) { approvals = false }
    if (externalLinks) ToolSettingsSheet { externalLinks = false }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large), content = content)
}

@Composable
private fun GroupDivider() {
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val row: @Composable () -> Unit = {
        ListItem(headlineContent = {
            Text(title)
        }, leadingContent = { Icon(icon, null, tint = cs.primary) }, trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                value?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                if (onClick != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh))
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = cs.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraSmall,
            content = row,
        )
    } else {
        Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.extraSmall, content = row)
    }
}
