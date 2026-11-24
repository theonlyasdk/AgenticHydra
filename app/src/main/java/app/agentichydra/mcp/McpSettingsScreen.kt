package app.agentichydra.mcp

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import app.agentichydra.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.agentichydra.HydraApplication
import app.agentichydra.HydraTheme
import app.agentichydra.HydraMenuItem
import app.agentichydra.HydraOverflowMenu
import app.agentichydra.horizontalFadingEdges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun McpSettingsPage() {
    val context = LocalContext.current
    val app = context.applicationContext as HydraApplication
    val registry = app.mcpRegistry
    val states: Map<String, McpServerState> by registry.states.collectAsState()
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var addSeed by remember { mutableStateOf<SuggestedServer?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    val servers = states.values.sortedBy { it.config.displayName.lowercase() }
    val selected = selectedId?.let { states[it] }
    val removing = removingId?.let { states[it] }

    fun signInAndConnect(state: McpServerState) {
        if (busyId != null) return
        actionError = null
        busyId = state.config.id
        scope.launch {
            try {
                runOAuth(context, registry, state.config.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = e.message ?: "Sign-in did not finish."
            } finally {
                busyId = null
            }
        }
    }

    fun connect(state: McpServerState) {
        actionError = null
        val config = state.config
        val needsSignIn =
            config.authType == McpAuthType.OAUTH &&
                (config.clientId.isNullOrBlank() || state.status == McpStatus.NEEDS_AUTH)
        if (needsSignIn) signInAndConnect(state) else registry.connect(config.id)
    }

    Box(Modifier.fillMaxSize()) {
        McpSettingsContent(
            servers = servers,
            busyId = busyId,
            actionError = actionError,
            onAdd = {
                addSeed = null
                showAdd = true
            },
            onSuggestion = {
                addSeed = it
                showAdd = true
            },
            onDismissError = { actionError = null },
            onSelect = { selectedId = it },
            onConnect = ::connect,
            onDisconnect = { registry.disconnect(it) },
            onRemove = { removingId = it },
        )
    }

    if (showAdd) {
        AddServerDialog(
            existingUrls = servers.map { it.config.url.trim().trimEnd('/') }.toSet(),
            seed = addSeed,
            onDismiss = {
                showAdd = false
                addSeed = null
            },
            onAdd = { config, startSignIn ->
                registry.addOrUpdate(config)
                showAdd = false
                addSeed = null
                if (startSignIn) {
                    scope.launch {
                        val state = registry.get(config.id) ?: return@launch
                        busyId = config.id
                        try {
                            runOAuth(context, registry, config.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            actionError = e.message ?: "Sign-in did not finish."
                        } finally {
                            busyId = null
                        }
                    }
                } else {
                    registry.connect(config.id)
                }
            },
        )
    }

    selected?.let { state ->
        ServerDetailSheet(
            state = state,
            busy = busyId == state.config.id,
            onDismiss = { selectedId = null },
            onConnect = { connect(state) },
            onDisconnect = { registry.disconnect(state.config.id) },
            onRemove = {
                selectedId = null
                removingId = state.config.id
            },
            onToggleTool = { tool, enabled -> registry.toggleTool(state.config.id, tool, enabled) },
            onPreset = { readOnlyOnly -> registry.applyToolPreset(state.config.id, readOnlyOnly) },
            onAllowSelected = { registry.allowSelectedTools(state.config.id) },
        )
    }

    removing?.let { state ->
        RemoveServerDialog(
            state = state,
            onDismiss = { removingId = null },
            onConfirm = { revoke ->
                removingId = null
                actionError = null
                scope.launch {
                    runCatching { removeServer(context, registry, state, revoke) }
                        .onFailure { actionError = it.message ?: "The server could not be removed." }
                }
            },
        )
    }
}

@Composable
private fun McpSettingsContent(
    servers: List<McpServerState>,
    busyId: String?,
    actionError: String?,
    onAdd: () -> Unit,
    onSuggestion: (SuggestedServer) -> Unit,
    onDismissError: () -> Unit,
    onSelect: (String) -> Unit,
    onConnect: (McpServerState) -> Unit,
    onDisconnect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (servers.isEmpty()) {
        EmptyServers(onAdd, onSuggestion)
        return
    }
    val existingUrls = servers.map { it.config.url.trim().trimEnd('/') }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 840.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (servers.size == 1) "1 server" else "${servers.size} servers",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Tools appear in Work after a server connects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add")
                }
            }
        }

        if (actionError != null) {
            item {
                ErrorBanner(actionError, onDismissError)
            }
        }

        items(servers, key = { it.config.id }) { state ->
            McpServerCard(
                state = state,
                busy = busyId == state.config.id,
                onClick = { onSelect(state.config.id) },
                onConnect = { onConnect(state) },
                onDisconnect = { onDisconnect(state.config.id) },
                onRemove = { onRemove(state.config.id) },
            )
        }

        item {
            SuggestedServers(existingUrls, onSuggestion)
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Security,
                    null,
                    Modifier.size(16.dp).padding(top = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Read-only tools run automatically. Other tools ask for approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyServers(
    onAdd: () -> Unit,
    onSuggestion: (SuggestedServer) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedServerIcon()
        Spacer(Modifier.height(20.dp))
        Text("Bring your tools with you", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Connect a server to use its tools in Work.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add MCP server")
        }
        Spacer(Modifier.height(28.dp))
        SuggestedServers(
            existingUrls = emptySet(),
            onPick = onSuggestion,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Sign in on the provider?s own site.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun AnimatedServerIcon() {
    val cs = MaterialTheme.colorScheme
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val intro = remember { Animatable(if (motionEnabled) 0f else 1f) }
    var entered by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) intro.animateTo(1f, tween(durationMillis = 220))
        entered = true
    }
    val rotation = if (motionEnabled && entered) {
        rememberInfiniteTransition(label = "Server icon").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 18_000, easing = LinearEasing)),
            label = "Server icon rotation",
        )
    } else {
        null
    }
    Surface(
        modifier = Modifier.size(64.dp).graphicsLayer { alpha = intro.value },
        shape = CircleShape,
        color = cs.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Hub,
                null,
                Modifier.size(32.dp).graphicsLayer {
                    rotationZ = -180f * (1f - intro.value) + (rotation?.value ?: 0f)
                },
                tint = cs.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SuggestedServers(
    existingUrls: Set<String>,
    onPick: (SuggestedServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = suggestedServers.filter { it.url.trimEnd('/') !in existingUrls }
    if (available.isEmpty()) return
    val repeated = available.size > 1
    val midpoint = Int.MAX_VALUE / 2
    val start = if (repeated) midpoint - midpoint % available.size else 0
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val speed = with(LocalDensity.current) { 24.dp.toPx() }
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(rowState, repeated, speed, motionEnabled) {
        if (!repeated || !motionEnabled) return@LaunchedEffect
        while (true) {
            snapshotFlow { rowState.isScrollInProgress }.first { !it }
            launch {
                rowState.scroll {
                    var previousFrame = withFrameNanos { it }
                    while (true) {
                        val frame = withFrameNanos { it }
                        val seconds = (frame - previousFrame).coerceAtMost(40_000_000L) / 1_000_000_000f
                        scrollBy(speed * seconds)
                        previousFrame = frame
                    }
                }
            }.join()
        }
    }
    val background = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suggested", style = MaterialTheme.typography.labelLarge)
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().then(if (repeated) Modifier.horizontalFadingEdges(background) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = if (repeated) Int.MAX_VALUE else 1) { index ->
                val suggestion = available[index % available.size]
                AssistChip(
                    onClick = { onPick(suggestion) },
                    label = { Text(suggestion.name) },
                    leadingIcon = {
                        if (suggestion.name == "Notion") {
                            Icon(
                                painterResource(R.drawable.ic_notion),
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Icon(
                                suggestion.icon ?: Icons.Outlined.Hub,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun McpServerCard(
    state: McpServerState,
    busy: Boolean,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val config = state.config

    Surface(
        onClick = onClick,
        color = cs.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = when (state.status) {
                        McpStatus.CONNECTED -> cs.primaryContainer
                        McpStatus.NEEDS_AUTH, McpStatus.ERROR -> cs.errorContainer
                        else -> cs.surfaceContainerHigh
                    },
                ) {
                    Icon(
                        if (config.transportType == McpTransportType.STDIO) Icons.Outlined.Terminal else Icons.Outlined.CloudQueue,
                        null,
                        Modifier.size(44.dp).padding(10.dp),
                        tint = when (state.status) {
                            McpStatus.CONNECTED -> cs.onPrimaryContainer
                            McpStatus.NEEDS_AUTH, McpStatus.ERROR -> cs.onErrorContainer
                            else -> cs.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        config.displayName.ifBlank { config.url },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        config.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusPill(if (busy) McpStatus.CONNECTING else state.status)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreHoriz, "Server options", tint = cs.onSurfaceVariant)
                    }
                    HydraOverflowMenu(expanded = menu, onDismiss = { menu = false }) {
                        HydraMenuItem("Reconnect", Icons.Outlined.Refresh) {
                            menu = false
                            onConnect()
                        }
                        HydraMenuItem("Remove", Icons.Outlined.DeleteOutline) {
                            menu = false
                            onRemove()
                        }
                    }
                }
            }

            if (state.status == McpStatus.CONNECTED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Apps, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.tools.size == 1) "1 tool available" else "${state.tools.size} tools available",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            } else {
                state.lastError?.let { error ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = cs.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    when (state.status) {
                        McpStatus.CONNECTING -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting…", style = MaterialTheme.typography.labelLarge)
                        }
                        McpStatus.NEEDS_AUTH -> {
                            Button(onClick = onConnect, enabled = !busy) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (busy) "Signing in…" else "Sign in")
                            }
                        }
                        else -> {
                            FilledTonalButton(onClick = onConnect, enabled = !busy) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (busy) "Connecting…" else "Connect")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: McpStatus) {
    val cs = MaterialTheme.colorScheme
    val (color, label) = when (status) {
        McpStatus.CONNECTED -> cs.primary to "Connected"
        McpStatus.CONNECTING -> cs.tertiary to "Connecting"
        McpStatus.OFFLINE -> cs.tertiary to "Offline"
        McpStatus.NEEDS_AUTH -> cs.error to "Sign in needed"
        McpStatus.ERROR -> cs.error to "Error"
        McpStatus.DISCONNECTED -> cs.onSurfaceVariant to "Not connected"
    }
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = cs.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private data class SuggestedServer(
    val name: String,
    val url: String,
    val description: String,
    val auth: McpAuthType = McpAuthType.OAUTH,
    val icon: ImageVector? = null,
)

private val suggestedServers =
    listOf(
        SuggestedServer("Notion", "https://mcp.notion.com/mcp", "Pages, databases and comments"),
        SuggestedServer("Linear", "https://mcp.linear.app/mcp", "Issues and projects", icon = Icons.Outlined.ViewKanban),
        SuggestedServer("Sentry", "https://mcp.sentry.dev/mcp", "Errors and performance", icon = Icons.Outlined.BugReport),
        SuggestedServer("Figma", "https://mcp.figma.com/mcp", "Design context", icon = Icons.Outlined.Palette),
        SuggestedServer("Stripe", "https://mcp.stripe.com", "Payments and customers", icon = Icons.Outlined.Payments),
        SuggestedServer("GitHub", "https://api.githubcopilot.com/mcp/readonly", "Repositories, issues and pull requests", McpAuthType.BEARER, Icons.Outlined.Code),
        SuggestedServer("Context7", "https://mcp.context7.com/mcp", "Library documentation and examples", McpAuthType.NONE, Icons.AutoMirrored.Outlined.MenuBook),
        SuggestedServer("Cloudflare Docs", "https://docs.mcp.cloudflare.com/mcp", "Cloudflare documentation", McpAuthType.NONE, Icons.Outlined.CloudQueue),
    )

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddServerDialog(
    existingUrls: Set<String>,
    seed: SuggestedServer? = null,
    onDismiss: () -> Unit,
    onAdd: (McpServerConfig, Boolean) -> Unit,
) {
    var name by remember(seed) { mutableStateOf(seed?.name.orEmpty()) }
    var url by remember(seed) { mutableStateOf(seed?.url.orEmpty()) }
    var auth by remember(seed) { mutableStateOf(seed?.auth ?: McpAuthType.OAUTH) }
    var token by remember { mutableStateOf("") }
    var autoConnect by remember { mutableStateOf(true) }
    var allowPrivate by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmedUrl = url.trim().trimEnd('/')
    val valid = name.isNotBlank() && trimmedUrl.startsWith("https://") && trimmedUrl !in existingUrls

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    placeholder = { Text("Notion") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://mcp.notion.com/mcp") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                    isError = url.isNotBlank() && !url.trim().startsWith("https://"),
                    supportingText = {
                        if (url.isNotBlank() && !url.trim().startsWith("https://")) {
                            Text("Use an https:// URL.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = auth == McpAuthType.OAUTH,
                        onClick = { auth = McpAuthType.OAUTH },
                        label = { Text("OAuth") },
                    )
                    FilterChip(
                        selected = auth == McpAuthType.NONE,
                        onClick = { auth = McpAuthType.NONE },
                        label = { Text("None") },
                    )
                    FilterChip(
                        selected = auth == McpAuthType.BEARER,
                        onClick = { auth = McpAuthType.BEARER },
                        label = { Text("Bearer token") },
                    )
                }

                if (auth == McpAuthType.BEARER) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; error = null },
                        label = { Text("Token") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (showToken) "Hide token" else "Show token",
                                )
                            }
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ToggleRow(
                    title = "Connect on launch",
                    subtitle = "Hydra reconnects this server when the app starts.",
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it },
                )
                ToggleRow(
                    title = "Allow private / LAN URL",
                    subtitle = "Only needed for a server on your own network.",
                    checked = allowPrivate,
                    onCheckedChange = { allowPrivate = it },
                )

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && (auth != McpAuthType.BEARER || token.isNotBlank()),
                onClick = {
                    if (trimmedUrl in existingUrls) {
                        error = "That server is already added."
                        return@TextButton
                    }
                    val id = uniqueId(name, trimmedUrl)
                    onAdd(
                        McpServerConfig(
                            id = id,
                            displayName = name.trim(),
                            transportType = McpTransportType.REMOTE_HTTP,
                            url = trimmedUrl,
                            authType = auth,
                            autoConnect = autoConnect,
                            allowPrivateUrl = allowPrivate,
                            bearerToken = token.takeIf { auth == McpAuthType.BEARER },
                        ),
                        auth == McpAuthType.OAUTH,
                    )
                },
            ) { Text(if (auth == McpAuthType.OAUTH) "Add and sign in" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerDetailSheet(
    state: McpServerState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onPreset: (Boolean) -> Unit,
    onAllowSelected: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val config = state.config
    val enabledNames = state.tools.map { it.originalName }.filter(config::permitsTool).toSet()
    val readOnlyCount = state.tools.count { it.readOnlyHint }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = cs.primaryContainer) {
                    Icon(
                        Icons.Outlined.Hub,
                        null,
                        Modifier.size(44.dp).padding(10.dp),
                        tint = cs.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(config.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        config.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(if (busy) McpStatus.CONNECTING else state.status)
            }

            if (state.lastError != null && state.status != McpStatus.CONNECTED) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = cs.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    McpStatus.CONNECTED -> {
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                            Text("Disconnect")
                        }
                    }
                    McpStatus.NEEDS_AUTH -> {
                        Button(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign in again")
                        }
                    }
                    else -> {
                        Button(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f)) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (busy) "Connecting…" else "Connect")
                        }
                    }
                }
                OutlinedButton(onClick = onRemove) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tools", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.tools.isEmpty()) {
                        "Connect to discover this server's tools."
                    } else {
                        "${enabledNames.size} enabled ? $readOnlyCount read-only ? ${state.tools.size} total"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (state.tools.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onPreset(true) }, label = { Text("Read-only") })
                        AssistChip(onClick = { onPreset(false) }, label = { Text("All") })
                        AssistChip(
                            onClick = onAllowSelected,
                            enabled = config.allowedTools == null,
                            label = { Text(if (config.allowedTools == null) "Allow selected" else "Allowlist on") },
                        )
                    }
                    if (config.allowedTools != null) {
                        Text("New tools stay off until you enable them.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.tools.isEmpty()) {
                Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Apps, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.status == McpStatus.CONNECTED) "No tools reported by this server." else "Tools load after connecting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.tools.sortedBy { it.originalName }.forEach { tool ->
                        ToolRow(
                            name = tool.originalName,
                            description = tool.description,
                            readOnly = tool.readOnlyHint,
                            checked = tool.originalName in enabledNames,
                            onCheckedChange = { onToggleTool(tool.originalName, it) },
                        )
                    }
                }
            }

            Text(
                "Hydra sends tool calls to this server. Results are untrusted content and may contain instructions; the agent is told to treat them as data.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolRow(
    name: String,
    description: String,
    readOnly: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (readOnly) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = cs.primary.copy(alpha = 0.10f), shape = CircleShape) {
                        Text(
                            "read-only",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RemoveServerDialog(
    state: McpServerState,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var revoke by remember { mutableStateOf(state.config.authType == McpAuthType.OAUTH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${state.config.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The server and its saved sign-in are deleted from this phone.")
                if (state.config.authType == McpAuthType.OAUTH) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = revoke, onCheckedChange = { revoke = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also revoke access at the provider", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(revoke) }) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun runOAuth(context: Context, registry: McpRegistry, serverId: String) {
    val state = registry.get(serverId) ?: error("That server no longer exists.")
    val config = state.config
    val auth = McpAuth(context)
    val metadata = withContext(Dispatchers.IO) { auth.discover(config.url) }
    val redirectUri = config.redirectUri
    val clientId = config.clientId ?: withContext(Dispatchers.IO) {
        auth.registerClient(metadata, redirectUri)
    }
    val (verifier, challenge) = auth.pkce()
    val oauthState = auth.newState()
    val scopes = config.scopes.ifBlank { metadata.scopesSupported.firstOrNull() ?: "default" }
    val authorizeUrl = auth.buildAuthorizeUrl(metadata, clientId, redirectUri, scopes, config.url, oauthState, challenge)
    val callback = auth.awaitCallback(oauthState) { auth.launchAuthorize(context, authorizeUrl) }
    if (callback.error != null) error("Notion sign-in was cancelled: ${callback.error}")
    val code = callback.code ?: error("Notion did not return an authorization code.")
    val tokens = withContext(Dispatchers.IO) {
        auth.exchangeCode(metadata, clientId, code, verifier, redirectUri, config.url)
    }
    auth.storeTokens(serverId, tokens.accessToken, tokens.refreshToken)

    val latest = registry.get(serverId) ?: return
    registry.addOrUpdate(
        latest.config.copy(
            clientId = clientId,
            bearerToken = null,
            authType = McpAuthType.OAUTH,
        ),
    )
    registry.connect(serverId)
}

private suspend fun removeServer(
    context: Context,
    registry: McpRegistry,
    state: McpServerState,
    revoke: Boolean,
) {
    val config = state.config
    if (revoke && config.authType == McpAuthType.OAUTH && !config.clientId.isNullOrBlank()) {
        val auth = McpAuth(context)
        val token = auth.readAccessToken(config.id)
        if (token.isNotBlank()) {
            runCatching {
                val metadata = withContext(Dispatchers.IO) { auth.discover(config.url) }
                withContext(Dispatchers.IO) { auth.revokeBestEffort(metadata, config.clientId!!, token) }
            }
        }
    }
    McpAuth(context).clearTokens(config.id)
    registry.remove(config.id)
}

private fun uniqueId(name: String, url: String): String {
    val base = McpTool.sanitize(name).take(20).ifBlank { "server" }
    val suffix = (url.hashCode() and 0xffff).toString(16)
    return "${base}_$suffix"
}

@Preview(name = "MCP empty light")
@Preview(name = "MCP empty dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "MCP empty large text", fontScale = 1.5f)
@Composable
private fun McpEmptyPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            McpSettingsContent(emptyList(), null, null, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(name = "MCP servers light")
@Preview(name = "MCP servers dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "MCP servers large text", fontScale = 1.5f)
@Composable
private fun McpServersPreview() {
    val notion =
        McpServerState(
            config =
                McpServerConfig(
                    id = "notion",
                    displayName = "Notion",
                    url = "https://mcp.notion.com/mcp",
                    authType = McpAuthType.OAUTH,
                ),
            status = McpStatus.CONNECTED,
            tools =
                listOf(
                    McpTool("notion", "search", "Search pages and databases.", org.json.JSONObject(), true),
                    McpTool("notion", "update_page", "Update a page's properties.", org.json.JSONObject(), false),
                ),
        )
    val draft =
        McpServerState(
            config =
                McpServerConfig(
                    id = "local",
                    displayName = "Local tools",
                    url = "https://tools.example.com/mcp",
                ),
            status = McpStatus.DISCONNECTED,
        )
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            McpSettingsContent(listOf(notion, draft), null, null, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
