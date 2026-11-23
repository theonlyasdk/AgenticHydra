package app.agentichydra

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.agentichydra.mcp.McpToolRouter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import org.json.JSONObject

private fun openWork(
    context: Context,
    id: String? = null,
) {
    context.startActivity(
        Intent(context, WorkChatActivity::class.java).apply {
            if (id == null) {
                putExtra("newConversation", true)
            } else {
                putExtra("conversationId", id)
            }
        },
    )
}

private enum class WorkSort(
    val label: String,
    val detail: String,
    val icon: ImageVector,
) {
    RECENT("Newest", "Newest first", Icons.Outlined.ArrowDownward),
    OLDEST("Oldest", "Oldest first", Icons.Outlined.ArrowUpward),
    NAME("Name", "Name (A–Z)", Icons.Outlined.SortByAlpha),
}

private fun List<WorkRecord>.sortedFor(sort: WorkSort): List<WorkRecord> =
    when (sort) {
        WorkSort.RECENT -> sortedByDescending { it.updatedAt }
        WorkSort.OLDEST -> sortedBy { it.updatedAt }
        WorkSort.NAME -> sortedBy { it.title.lowercase() }
    }

private fun workTimeLabel(
    updatedAt: Long,
    relative: Boolean = false,
    now: Long = System.currentTimeMillis(),
): String {
    if (updatedAt <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val updated = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val date = updated.toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (relative) {
        val minutes = ((now - updatedAt) / 60_000L).coerceAtLeast(0)
        val days = minutes / (60 * 24)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            date == today.minusDays(1) || days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            date.year == today.year -> DateTimeFormatter.ofPattern("MMM d").format(date)
            else -> DateTimeFormatter.ofPattern("MMM d, yyyy").format(date)
        }
    }
    return when {
        date == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(updated)
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE").format(date)
        date.year == today.year -> DateTimeFormatter.ofPattern("MMM d").format(date)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy").format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun WorkScreen(
    chat: HydraModel,
    vm: WorkModel,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val externalPrompt = LocalExternalIntentPrompt.current
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<WorkRecord?>(null) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var multiOptions by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<WorkRecord?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var approvals by rememberSaveable { mutableStateOf(false) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(WorkSort.RECENT) }
    var searchFocused by remember { mutableStateOf(false) }
    var keyboardSeen by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val relativeTime = LocalRelativeTimeEnabled.current
    // Hiding the keyboard is the user's signal to leave search; drop focus with it.
    LaunchedEffect(searchFocused, keyboardVisible) {
        if (keyboardVisible) {
            keyboardSeen = true
        } else if (keyboardSeen) {
            keyboardSeen = false
            if (searchFocused) focusManager.clearFocus()
        }
    }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    val listState = rememberLazyListState()
    val searchFade by animateFloatAsState(
        targetValue = if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "Search fade",
    )
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { vm.selectProject(uri) }.onFailure { vm.error = it.message }
            }
        }
    val filtered =
        remember(vm.records, query, sort) {
            vm.records
                .filter {
                    it.title.contains(query, true) ||
                        it.projectName.contains(query, true)
                }.sortedFor(sort)
        }
    val selectionCount = selection.size
    Scaffold(
        containerColor = cs.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (selectionCount == 0) {
                            Text("Work", style = MaterialTheme.typography.titleLarge)
                        } else {
                            Text("$selectionCount selected", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                    actions = {
                        if (selectionCount > 0) {
                            HintIconButton("Selected task options", Icons.Outlined.MoreHoriz, onClick = {
                                multiOptions =
                                    true
                            })
                            HintIconButton("Clear selection", Icons.Outlined.Clear, onClick = { selection = emptySet() })
                        } else {
                            HintIconButton("Scheduled tasks", Icons.Outlined.Schedule, onClick = { scheduled = true })
                            HintIconButton("Tool approvals", Icons.Outlined.Shield, onClick = { approvals = true })
                        }
                    },
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 8.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockedSearchBar(inputField = {
                            SearchBarDefaults.InputField(
                                query = query,
                                onQueryChange = { query = it },
                                onSearch = {},
                                expanded = false,
                                onExpandedChange = {},
                                modifier = Modifier.onFocusChanged { searchFocused = it.isFocused },
                                placeholder = { Text("Search tasks") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = if (query.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = { query = "" },
                                            modifier = Modifier.offset(x = 16.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Clear,
                                                "Clear search",
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }, expanded = false, onExpandedChange = {}, modifier = Modifier.weight(1f)) {}
                        AnimatedVisibility(
                            visible = !searchFocused,
                            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                        ) {
                            Box(Modifier.padding(start = 8.dp)) {
                                WorkSortControl(sort) { sort = it }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openWork(context, if (vm.running) vm.activeId else null) },
                icon = {
                    Icon(if (vm.running) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Add, null)
                },
                text = { Text(if (vm.running) "Open running task" else "New task") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 840.dp).fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                vm.error?.let { error -> item { ErrorBanner(error, onDismiss = { vm.error = null }) } }
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillParentMaxHeight().fillMaxWidth().imePadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (query.isBlank()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    HydraIllustration(Modifier.size(100.dp))
                                    Text("No tasks yet", style = MaterialTheme.typography.titleLarge)
                                }
                            } else {
                                Column(
                                    modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 320.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = cs.surfaceContainerHigh,
                                        modifier = Modifier.size(64.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.SearchOff,
                                                null,
                                                modifier = Modifier.size(28.dp),
                                                tint = cs.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "No tasks match",
                                        style = MaterialTheme.typography.titleLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "“$query”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(20.dp))
                                    FilledTonalButton(onClick = { query = "" }) {
                                        Icon(Icons.Outlined.Clear, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Clear search")
                                    }
                                }
                            }
                        }
                    }
                }
                itemsIndexed(filtered, key = { _, record -> record.id }) { index, record ->
                    val active = vm.running && vm.activeId == record.id
                    val background = record.status == "Running"
                    val selected = record.id in selection
                    val rowColor = if (selected) cs.primaryContainer else cs.surfaceContainerHigh
                    val toggleSelection = {
                        selection = if (selected) selection - record.id else selection + record.id
                    }
                    Surface(
                        color = rowColor,
                        shape = groupedRowShape(index, filtered.size),
                        modifier =
                            Modifier.fillMaxWidth().semantics { this.selected = selected }.combinedClickable(
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        toggleSelection()
                                    } else {
                                        openWork(context, record.id)
                                    }
                                },
                                onLongClick = {
                                    selection = if (selected) selection - record.id else selection + record.id
                                },
                                onLongClickLabel = if (selected) "Deselect task" else "Select task",
                            ),
                    ) {
                        Box {
                            ListItem(
                                modifier = Modifier.padding(end = 40.dp),
                                headlineContent = {
                                    MarqueeTitle(
                                        record.title,
                                        surface = rowColor,
                                        style = MaterialTheme.typography.workListTitle,
                                    )
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            MarqueeTitle(
                                                projectSubtitle(record.projectName, record.projectUri != null, active),
                                                Modifier.weight(1f),
                                                surface = rowColor,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            val time = workTimeLabel(record.updatedAt, relativeTime)
                                            if (time.isNotBlank()) {
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    time,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = cs.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        when {
                                            active -> {
                                                WorkProgressLabel(
                                                    vm.progress.ifBlank { "Thinking…" },
                                                    rowColor,
                                                )
                                            }

                                            background -> {
                                                WorkProgressLabel(
                                                    if (vm.engine.isRunning(record.id)) {
                                                        vm.engine.progressFor(record.id).ifBlank {
                                                            "Working…"
                                                        }
                                                    } else {
                                                        "Working…"
                                                    },
                                                    rowColor,
                                                )
                                            }

                                            else -> {
                                                Text(
                                                    record.status,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = cs.workStatusColor(record.status),
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingContent = {
                                    when {
                                        selection.isNotEmpty() -> {
                                            Icon(
                                                if (selected) {
                                                    Icons.Outlined.CheckCircle
                                                } else {
                                                    Icons.Outlined.RadioButtonUnchecked
                                                },
                                                if (selected) "Selected" else "Not selected",
                                                Modifier.size(24.dp),
                                                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                                            )
                                        }

                                        active || background -> {
                                            CircularProgressIndicator(
                                                Modifier.size(24.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        }

                                        else -> {
                                            Icon(Icons.Outlined.Terminal, null, tint = cs.primary)
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = rowColor),
                            )
                            IconButton(
                                onClick = {
                                    if (selection.isNotEmpty()) multiOptions = true else options = record
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                            ) { Icon(Icons.Outlined.MoreHoriz, "Task options") }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .height(20.dp)
                    .graphicsLayer { alpha = searchFade }
                    .background(Brush.verticalGradient(listOf(cs.background, Color.Transparent))),
            )
        }
    }
    options?.let { record ->
        HydraBottomSheet(onDismiss = { options = null }) { dismiss ->
            var confirm by remember { mutableStateOf(false) }
            LaunchedEffect(record.id, confirm) {
                if (confirm) {
                    delay(3500)
                    confirm = false
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarqueeTitle(
                        record.title,
                        style = MaterialTheme.typography.headlineSmall,
                        surface = cs.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskOptionRow(0, 6, Icons.Outlined.ChatBubbleOutline, "Open conversation") {
                        dismiss()
                        openWork(context, record.id)
                    }
                    TaskOptionRow(1, 6, Icons.Outlined.CheckCircle, "Select task") {
                        dismiss()
                        selection = selection + record.id
                    }
                    TaskOptionRow(2, 6, Icons.Outlined.Edit, "Rename") {
                        dismiss()
                        title = record.title
                        rename = record
                    }
                    TaskOptionRow(3, 6, Icons.Outlined.FolderOpen, "Change directory", enabled = !vm.running) {
                        dismiss()
                        if (vm.viewRecord(record)) folder.launch(null)
                    }
                    TaskOptionRow(4, 6, Icons.Outlined.Share, "Export conversation") {
                        dismiss()
                        shareText(
                            context,
                            externalPrompt,
                            "Export task",
                            exportWorkRecord(record),
                            mime = "text/markdown",
                        )
                    }
                    TaskOptionRow(
                        5,
                        6,
                        Icons.Outlined.DeleteOutline,
                        "Delete",
                        enabled =
                            !(vm.running && vm.activeId == record.id) && !vm.engine.isRunning(record.id),
                        danger = true,
                        confirmDelete = confirm,
                        confirmLabel = "Tap again to confirm",
                    ) {
                        if (!confirm) {
                            confirm = true
                        } else {
                            confirm = false
                            vm.deleteRecord(record)
                            dismiss()
                        }
                    }
                }
            }
        }
    }
    if (multiOptions && selection.isNotEmpty()) {
        taskMultiOptionsSheet(vm, selection, selectAll = {
            selection = vm.records.map { it.id }.toSet()
        }, clearSelection = {
            selection =
                emptySet()
        }, onDismiss = {
            multiOptions = false
            selection = emptySet()
        })
    }
    if (approvals) ApprovalSheet(vm) { approvals = false }
    if (scheduled) TaskSchedulesSheet((context.applicationContext as HydraApplication).schedules) { scheduled = false }
    rename?.let { record ->
        RenameDialog(
            title = "Rename task",
            label = "Task name",
            value = title,
            onValueChange = { title = it },
            confirmLabel = "Rename",
            onConfirm = {
                vm.renameConversation(record.id, title)
                rename = null
            },
            onDismiss = { rename = null },
            autofocus = true,
        )
    }
}

@Composable
private fun WorkSortControl(
    sort: WorkSort,
    onSelect: (WorkSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sort.label, style = MaterialTheme.typography.labelLarge)
        }
        HydraOverflowMenu(expanded, { expanded = false }, headerText = "Sort tasks") {
            WorkSort.entries.forEach { option ->
                HydraMenuItem(option.detail, option.icon, selected = option == sort) {
                    expanded = false
                    onSelect(option)
                }
            }
        }
    }
}

internal fun projectSubtitle(
    name: String,
    selected: Boolean,
    running: Boolean,
) = if (!selected) "Choose a project" else "${if (running) "Running in" else "Inside"} $name"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskOptionRow(
    index: Int,
    total: Int,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    confirmDelete: Boolean = false,
    confirmLabel: String = label,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val rowColor by animateColorAsState(
        if (confirmDelete) cs.errorContainer else cs.surfaceContainerHigh,
        animationSpec = spring(dampingRatio = .9f, stiffness = 380f),
        label = "TaskOptionColor",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = rowColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(targetState = confirmDelete, transitionSpec = {
            (
                (slideInVertically(spring(dampingRatio = .9f, stiffness = 350f)) { it } + fadeIn(spring())) togetherWith
                    (slideOutVertically(spring(dampingRatio = .9f, stiffness = 350f)) { -it } + fadeOut(spring()))
            ).using(null)
        }, label = "TaskOptionLabel") { confirmed ->
            val text = if (confirmed) confirmLabel else label
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (confirmed) Icons.Outlined.CheckCircleOutline else icon,
                    text,
                    Modifier.size(if (confirmed) 24.dp else 26.dp),
                    tint = if (confirmed) cs.onErrorContainer else cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (confirmed) {
                            cs.onErrorContainer
                        } else if (danger) {
                            cs.error
                        } else {
                            cs.onSurface
                        },
                )
            }
        }
    }
}

@Composable
private fun taskMultiOptionsSheet(
    vm: WorkModel,
    selection: Set<String>,
    selectAll: () -> Unit,
    clearSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    HydraBottomSheet(onDismiss = onDismiss) { dismiss ->
        var confirm by remember { mutableStateOf(false) }
        LaunchedEffect(selection, confirm) {
            if (confirm) {
                delay(3500)
                confirm = false
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Checklist, null, tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${selection.size} of ${vm.records.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskOptionRow(0, 3, Icons.Outlined.SelectAll, "Select all") { selectAll() }
                TaskOptionRow(
                    1,
                    3,
                    Icons.Outlined.DeleteOutline,
                    "Delete selected",
                    danger = true,
                    confirmDelete = confirm,
                    confirmLabel = "Tap again to confirm",
                ) {
                    if (!confirm) {
                        confirm = true
                    } else {
                        confirm = false
                        vm.deleteRecords(selection)
                        clearSelection()
                        dismiss()
                    }
                }
                TaskOptionRow(2, 3, Icons.Outlined.Clear, "Clear selection") { dismiss() }
            }
        }
    }
}

@Composable
private fun WorkProgressLabel(
    text: String,
    surface: androidx.compose.ui.graphics.Color,
) {
    val scroll = rememberScrollState()
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    LaunchedEffect(text) { scroll.scrollTo(0) }
    Box(Modifier.fillMaxWidth().horizontalScrollFadingEdges(scroll, surface, direction).horizontalScroll(scroll)) {
        ShimmerStatusText(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

internal fun exportWorkRecord(record: WorkRecord) =
    "# ${record.title}\n\n" +
        record.messages.joinToString("\n\n") { message ->
            if (message.role == "tool") {
                record.steps
                    .firstOrNull {
                        it.id == message.id
                    }?.let { "### ${it.tool}\n\n```json\n${it.arguments}\n```\n\n```text\n${it.output}\n```" }
                    .orEmpty()
            } else {
                "### ${message.role.replaceFirstChar{it.uppercase()}}\n\n${message.text}"
            }
        }

class WorkChatActivity : ComponentActivity() {
    private var conversationId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as HydraApplication
        val work = application.models[WorkModel::class.java]
        val chat = application.models[HydraModel::class.java]
        val requested = savedInstanceState?.getString("conversationId") ?: intent.getStringExtra("conversationId")
        if (requested != null) {
            work.records.firstOrNull { it.id == requested }?.let { work.viewRecord(it) }
        } else if (savedInstanceState == null && intent.getBooleanExtra("newConversation", false)) {
            work.newThread()
        }
        conversationId = requested ?: work.activeId
        setContent {
            HydraTheme {
                val context = LocalContext.current
                val linkSheet = rememberLinkSheet()
                val externalPrompt = rememberExternalIntentPrompt()
                val openLink: (String) -> Unit = remember(context, linkSheet) {
                    { url -> openInLinkSheet(context, url, linkSheet, externalPrompt) }
                }
                CompositionLocalProvider(
                    LocalLinkOpener provides openLink,
                    LocalExternalIntentPrompt provides externalPrompt,
                ) {
                    WorkChatRoute(chat, work, conversationId) { finish() }
                    LinkSheet(linkSheet)
                    ExternalIntentHost(externalPrompt)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("conversationId", conversationId)
        super.onSaveInstanceState(outState)
    }
}

data class WorkChatUiState(
    val title: String = "New task",
    val project: String = "Choose a project",
    val messages: List<Message> = emptyList(),
    val steps: List<WorkStep> = emptyList(),
    val pendingId: String? = null,
    val running: Boolean = false,
    val status: String = "Ready",
    val error: String? = null,
    val conversationId: String = "new",
    val canSend: Boolean = true,
    val attachments: List<MessageAttachment> = emptyList(),
    val attaching: Boolean = false,
    val progress: String = "",
    val hasProject: Boolean = true,
    val streamingResponseId: String? = null,
    val activeTasks: Int = 0,
    val context: ContextUsage = ContextUsage(),
    val fileIndex: List<String> = emptyList(),
    val fileIndexLoading: Boolean = false,
)

class WorkChatActions(
    val onSend: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onProject: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onChanges: () -> Unit = {},
    val onApprovals: () -> Unit = {},
    val onConnections: () -> Unit = {},
    val onApprove: (String, Boolean) -> Unit = { _, _ -> },
    val onDismissError: () -> Unit = {},
    val onCopy: (Message) -> Unit = {},
    val onShare: (Message) -> Unit = {},
    val onExport: () -> Unit = {},
    val onAttachFile: () -> Unit = {},
    val onAttachMedia: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onRename: () -> Unit = {},
    val onActiveTasks: () -> Unit = {},
    val onContext: () -> Unit = {},
)

@Composable
private fun WorkChatRoute(
    chat: HydraModel,
    vm: WorkModel,
    id: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val externalPrompt = LocalExternalIntentPrompt.current
    var draft by rememberSaveable(id) { mutableStateOf("") }
    var models by rememberSaveable { mutableStateOf(false) }
    var approvals by rememberSaveable { mutableStateOf(false) }
    var files by rememberSaveable { mutableStateOf(false) }
    var changes by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    var tasks by remember { mutableStateOf(false) }
    var contextSheet by rememberSaveable { mutableStateOf(false) }
    val active = vm.activeId == id
    val saved = vm.records.firstOrNull { it.id == id }
    val savedContext =
        remember(saved?.apiHistory, ContextBudget.tokens) {
            saved?.let { vm.usageFor(it.apiHistory) } ?: ContextUsage()
        }
    LaunchedEffect(ContextBudget.tokens) { if (active) vm.refreshContextUsage() }
    LaunchedEffect(id, vm.projectUri) { if (active) vm.refreshFileIndex() }
    val engineRun = vm.engine.isRunning(id)
    val state =
        if (active) {
            WorkChatUiState(
                vm.task.ifBlank {
                    "New task"
                },
                vm.projectName,
                vm.messages,
                vm.steps,
                vm.pendingApproval?.id,
                vm.running,
                vm.status,
                vm.error,
                id,
                canSend = !engineRun,
                attachments = vm.draftAttachments,
                attaching = vm.attaching,
                progress = if (engineRun) vm.engine.progressFor(id) else vm.progress,
                hasProject =
                    vm.projectUri != null,
                streamingResponseId = vm.streamingId,
                activeTasks = vm.engine.active.size,
                context = vm.contextUsage,
                fileIndex = vm.fileIndex,
                fileIndexLoading = vm.fileIndexLoading,
            )
        } else {
            WorkChatUiState(
                saved?.title ?: "Task unavailable",
                saved?.projectName ?: "",
                saved?.messages.orEmpty(),
                saved?.steps.orEmpty(),
                status =
                    saved?.status ?: "Deleted",
                conversationId = id,
                canSend = !vm.running && saved != null && !engineRun,
                hasProject = saved?.projectUri != null,
                progress = vm.engine.progressFor(id),
                activeTasks = vm.engine.active.size,
                context = savedContext,
            )
        }

    fun activate() = vm.activeId == id || saved?.let { vm.viewRecord(it) } == true
    LaunchedEffect(vm, id) {
        vm.toasts.collect { event ->
            val visible =
                (context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
            if (event.conversationId == id && visible) Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
        }
    }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { vm.selectProject(uri) }.onFailure { vm.error = it.message }
            }
        }
    var askedForProject by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (active && vm.projectUri == null && vm.messages.isEmpty() && !askedForProject) {
            askedForProject = true
            folder.launch(null)
        }
    }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && activate()) {
                vm.attach(uri, media = false)
            }
        }
    val mediaPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null && activate()) {
                vm.attach(uri, media = true)
            }
        }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val actions =
        WorkChatActions(
            onBack = onBack,
            onSend = {
                if (activate() && vm.followUp(draft, chat)) {
                    draft = ""
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onResume = {
                if (activate() && vm.resume(chat)) {
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onStop = { if (vm.activeId == id) vm.stop() },
            onProject = { if (!vm.running && activate()) folder.launch(null) },
            onFiles = {
                if (activate()) {
                    vm.refreshFiles()
                    files = true
                }
            },
            onChanges = { if (activate()) changes = true },
            onApprovals = {
                approvals = true
            },
            onConnections = { openConnections(context) },
            onApprove = vm::approveStep,
            onDismissError = { vm.error = null },
            onAttachFile = {
                if (!vm.running && activate()) {
                    filePicker.launch(
                        arrayOf(
                            "text/*",
                            "application/json",
                            "application/xml",
                            "application/javascript",
                            "application/octet-stream",
                        ),
                    )
                }
            },
            onAttachMedia = {
                if (!vm.running && activate()) {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            onRemoveAttachment = vm::removeAttachment,
            onRename = {
                renameTitle = state.title
                rename = true
            },
            onActiveTasks = { tasks = true },
            onContext = { contextSheet = true },
            onCopy = { clipboard.setText(AnnotatedString(it.text)) },
            onShare = { message ->
                shareText(context, externalPrompt, "Share response", message.text)
            },
            onExport = {
                if (state.messages.isNotEmpty()) {
                    val record =
                        WorkRecord(state.title, state.status, "", state.steps, emptyList(), id, state.messages)
                    shareText(
                        context,
                        externalPrompt,
                        "Export conversation",
                        exportWorkRecord(record),
                        mime = "text/markdown",
                    )
                }
            },
        )
    WorkChatScreen(state, draft, { draft = it }, actions) {
        ComposerModelControl(chat, enabled = !vm.running) {
            models =
                true
        }
    }
    if (contextSheet) {
        ContextSheet(state.context, { if (active) vm.setContextTokens(it) else ContextBudget.choose(context, it) }) {
            contextSheet = false
        }
    }
    if (models) {
        ModelPickerSheet(chat, onConnections = {
            models = false
            openConnections(context)
        }, onDismiss = { models = false })
    }
    if (approvals) ApprovalSheet(vm) { approvals = false }
    if (files) WorkFilesSheet(vm) { files = false }
    if (changes) {
        WorkChangesSheet(vm.changes, onOpenFile = { path ->
            if (activate()) {
                vm.browse(path)
                changes = false
                files = true
            }
        }) { changes = false }
    }
    if (tasks) {
        ActiveTasksSheet(
            vm.engine,
            onOpen = { openId ->
                context.startActivity(Intent(context, WorkChatActivity::class.java).putExtra("conversationId", openId))
            },
            onStop = vm.engine::stop,
            onDismiss = { tasks = false },
        )
    }
    if (active) {
        vm.pendingQuestions?.let { request ->
            AgentQuestionSheet(
                request,
                { vm.answerQuestions(request.id, it) },
                { vm.answerQuestions(request.id, null) },
            )
        }
        vm.pendingQuiz?.let { quiz ->
            AgentQuizSheet(
                quiz,
                onFinish = { attempt ->
                    vm.finishQuiz(quiz.id, attempt)
                    QuizResultActivity.open(context, attempt)
                },
                onCancel = { vm.cancelQuiz(quiz.id) },
            )
        }
        DeviceActionHost(vm)
        if (vm.termuxPromptId != null) {
            AlertDialog(
                onDismissRequest = vm::dismissTermuxPrompt,
                icon = { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Open Termux?") },
                text = {
                    Text(
                        "Hydra asked Termux to run a command, but Termux isn't open. Open it now so the command can run?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismissTermuxPrompt()
                        TermuxBridge.open(context)
                    }) { Text("Open Termux") }
                },
                dismissButton = { TextButton(onClick = vm::dismissTermuxPrompt) { Text("Not now") } },
            )
        }
    }
    if (rename) {
        RenameDialog(
            title = "Rename task",
            label = "Task name",
            value = renameTitle,
            onValueChange = { renameTitle = it },
            confirmLabel = "Rename",
            onConfirm = {
                vm.renameConversation(id, renameTitle)
                rename = false
            },
            onDismiss = { rename = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkChatScreen(
    state: WorkChatUiState,
    draft: String,
    onDraft: (String) -> Unit,
    actions: WorkChatActions,
    modelControl: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val timeline = remember(state.messages, state.steps) { groupReadFileReads(state.messages, state.steps) }
    var menu by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    val mention = remember(draft) { mentionQuery(draft) }
    var mentionDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(mention) { mentionDismissed = false }
    val showMentions = mention != null && state.hasProject && !mentionDismissed
    BackHandler(enabled = showMentions) { mentionDismissed = true }
    val canResume =
        !state.running && (state.status == "Cancelled" || state.status == "Failed") && draft.isBlank() && state.attachments.isEmpty()
    val canSendMessage =
        state.running ||
            (
                state.canSend &&
                    !state.attaching &&
                    (draft.isNotBlank() || state.attachments.isNotEmpty() || canResume)
            )
    Scaffold(containerColor = cs.background, topBar = {
        TopAppBar(
            title = {
                Surface(
                    onClick = actions.onRename,
                    color = cs.background,
                    shape = MaterialTheme.shapes.medium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Rename task" },
                ) {
                    Column {
                        MarqueeTitle(state.title, style = MaterialTheme.typography.titleMedium, surface = cs.background)
                        MarqueeTitle(
                            projectSubtitle(state.project, state.hasProject, state.running),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            surface = cs.background,
                        )
                    }
                }
            },
            navigationIcon = {
                HintIconButton("Back to task history", Icons.AutoMirrored.Outlined.ArrowBack, onClick = actions.onBack)
            },
            actions = {
                ContextRingButton(state.context, onClick = actions.onContext)
                HintIconButton(
                    if (searchOpen) "Close search" else "Search in conversation",
                    Icons.Outlined.Search,
                    onClick = { searchOpen = !searchOpen },
                )
                Box {
                    HintIconButton("Task options", Icons.Outlined.MoreHoriz, onClick = { menu = true })
                    HydraOverflowMenu(menu, { menu = false }, headerText = state.title) {
                        HydraMenuItem(
                            if (state.activeTasks == 1) "1 active task" else "${state.activeTasks} active tasks",
                            Icons.Outlined.AddTask,
                            enabled = state.activeTasks > 0,
                        ) {
                            menu = false
                            actions.onActiveTasks()
                        }
                        HydraMenuItem("Project files", Icons.Outlined.FolderOpen, enabled = state.canSend) {
                            menu = false
                            actions.onFiles()
                        }
                        HydraMenuItem("Changes", Icons.Outlined.Difference, enabled = state.canSend) {
                            menu = false
                            actions.onChanges()
                        }
                        HydraMenuItem(
                            "Export conversation",
                            Icons.Outlined.Share,
                            enabled = state.messages.isNotEmpty(),
                        ) {
                            menu = false
                            actions.onExport()
                        }
                        HydraMenuItem("Tool approvals", Icons.Outlined.Shield) {
                            menu = false
                            actions.onApprovals()
                        }
                        HydraMenuItem("Connections", Icons.Outlined.Hub, enabled = !state.running) {
                            menu = false
                            actions.onConnections()
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )
    }, bottomBar = {
        Column(
            Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 840.dp)
                .fillMaxWidth(),
        ) {
            state.error?.let {
                ErrorBanner(
                    it,
                    onDismiss = actions.onDismissError,
                    actionLabel = if (!state.running) "Continue" else null,
                    onAction = if (!state.running) actions.onResume else null,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!searchOpen) {
                Column {
                    if (showMentions) {
                        mention?.let { active ->
                            MentionPanel(
                                paths = state.fileIndex,
                                query = active.query,
                                loading = state.fileIndexLoading,
                                onPick = { path -> onDraft(applyMention(draft, active, path)) },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    Surface(color = cs.surfaceContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(8.dp)) {
                            if (state.attaching) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.attachments.isNotEmpty()) {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.attachments.forEach { attachment ->
                                        InputChip(
                                            selected = false,
                                            onClick = { actions.onRemoveAttachment(attachment.id) },
                                            label = {
                                                MarqueeTitle(
                                                    attachment.name,
                                                    Modifier.widthIn(max = 180.dp),
                                                    surface = cs.surfaceContainer,
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (attachment.isImage) {
                                                        Icons.Outlined.Image
                                                    } else {
                                                        Icons.Outlined.AttachFile
                                                    },
                                                    null,
                                                )
                                            },
                                            trailingIcon = { Icon(Icons.Outlined.Close, null) },
                                            modifier =
                                                Modifier.semantics {
                                                    contentDescription = "Remove ${attachment.name}"
                                                },
                                        )
                                    }
                                }
                            }
                            TextField(
                                draft,
                                onDraft,
                                placeholder = { Text("Reply to Hydra") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 176.dp),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedContainerColor = cs.surfaceContainer,
                                        unfocusedContainerColor = cs.surfaceContainer,
                                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    IconButton(onClick = {
                                        attachmentMenu = true
                                    }, enabled = !state.running && state.canSend) {
                                        Icon(
                                            Icons.Outlined.Add,
                                            "Add attachment or change directory",
                                        )
                                    }
                                    HydraOverflowMenu(attachmentMenu, { attachmentMenu = false }) {
                                        HydraMenuItem("Attach file", Icons.Outlined.AttachFile) {
                                            attachmentMenu = false
                                            actions.onAttachFile()
                                        }
                                        HydraMenuItem("Attach media", Icons.Outlined.Image) {
                                            attachmentMenu = false
                                            actions.onAttachMedia()
                                        }
                                        HydraMenuItem("Change directory", Icons.Outlined.CreateNewFolder) {
                                            attachmentMenu = false
                                            actions.onProject()
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) { modelControl() }
                                FilledIconButton(
                                    onClick =
                                        when {
                                            state.running -> actions.onStop
                                            canResume -> actions.onResume
                                            else -> actions.onSend
                                        },
                                    enabled = canSendMessage,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        when {
                                            state.running -> Icons.Outlined.Stop
                                            canResume -> Icons.Outlined.PlayArrow
                                            else -> Icons.Outlined.ArrowUpward
                                        },
                                        when {
                                            state.running -> "Stop task"
                                            canResume -> "Resume task"
                                            else -> "Send task message"
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!state.canSend) {
                        Text(
                            "Another task is running. You can browse this conversation while it finishes.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (state.messages.isEmpty()) {
                Column(
                    Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HydraIllustration(Modifier.size(112.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("What are we working on?", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Inspect a project, make a change, or research an idea. You can keep refining it here.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = actions.onProject) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.hasProject) state.project else "Choose project")
                    }
                }
            } else {
                ChatTimeline(
                    ChatTimelineUiState(state.conversationId, timeline.messages, state.streamingResponseId),
                    actions.onCopy,
                    actions.onShare,
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                    searchOpen = searchOpen,
                    onSearchOpenChange = { searchOpen = it },
                    onViewQuiz = { message -> message.quiz?.let { QuizResultActivity.open(context, it) } },
                    toolContent = { message, _, _ ->
                        val group =
                            timeline.reads[message.id]
                                ?: state.steps.firstOrNull { it.id == message.id }?.let { listOf(it) }
                        group?.let { steps ->
                            WorkToolEntry(steps, state.pendingId) { id, allow -> actions.onApprove(id, allow) }
                        }
                    },
                    trailingContent = {
                        if ((state.running || state.status == "Running" || state.status == "Thinking") &&
                            state.pendingId == null && state.streamingResponseId == null
                        ) {
                            BusyStatusRow(
                                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                                spacing = 12.dp,
                            ) {
                                WipeMarqueeStatusText(
                                    state.progress.ifBlank {
                                        if (state.status == "Thinking") {
                                            "Thinking"
                                        } else {
                                            "Working…"
                                        }
                                    },
                                    Modifier.weight(1f),
                                    surface = cs.background,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        } else if (!state.running && state.status != "Running" && state.status != "Thinking") {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = cs.workStatusColor(state.status))) {
                                        append(state.status)
                                    }
                                    append("\u2002•\u2002Performed ${state.steps.size} ")
                                    append(if (state.steps.size == 1) "action" else "actions")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun WorkToolEntry(
    steps: List<WorkStep>,
    pendingStepId: String?,
    onApprove: (String, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val step = steps.last()
    var details by rememberSaveable(steps.joinToString("/") { it.id }) { mutableStateOf(false) }
    val args = remember(step.arguments) { runCatching { JSONObject(step.arguments) }.getOrDefault(JSONObject()) }
    val label = remember(step.tool, step.arguments) { toolLabel(step.tool, args) }
    val title = if (steps.size > 1) "$label ×${steps.size}" else label
    val running = steps.any { it.state == "Running" }
    val failed = steps.any { it.state == "Failed" }
    val pendingStep = steps.firstOrNull { it.id == pendingStepId }
    val icon =
        when (step.tool) {
            "web_search", "web_search_batch", "fetch_url", "find_in_page" -> Icons.Outlined.Public
            "download_file" -> Icons.Outlined.Download
            "ask_user" -> Icons.Outlined.QuestionAnswer
            "quiz_user" -> Icons.Outlined.Quiz
            in DeviceTools.names -> Icons.Outlined.Smartphone
            "run_command", "git_status", "git_diff" -> Icons.Outlined.Terminal
            "read_image" -> Icons.Outlined.Image
            "find_files", "search_files", "list_files_recursive" -> Icons.Outlined.Search
            "write_file", "append_file", "replace_in_file", "apply_diff" -> Icons.Outlined.Edit
            "start_task", "read_task_output" -> Icons.Outlined.AddTask
            else -> Icons.Outlined.Description
        }
    val notion =
        remember(step.tool) {
            val server = McpToolRouter.registry?.findTool(step.tool)?.first
            server?.config?.displayName?.contains("notion", ignoreCase = true) == true ||
                server?.config?.url?.contains("notion", ignoreCase = true) == true ||
                step.tool.startsWith("mcp_notion_")
        }
    val notionPainter = if (notion) painterResource(R.drawable.ic_notion) else null
    val wait = step.wait
    val waiting = running && wait != null && step.tool in setOf("wait_for", "wait_until")
    var waitNow by remember(step.id) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(step.id, waiting) {
        while (waiting) {
            waitNow = android.os.SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    val waitFraction =
        if (waiting && wait!!.durationMillis > 0) {
            ((waitNow - wait.startedElapsed).toFloat() / wait.durationMillis).coerceIn(0f, 1f)
        } else {
            0f
        }
    val waitSeconds =
        if (waiting) {
            (((wait!!.durationMillis - (waitNow - wait.startedElapsed)).coerceAtLeast(0) + 999) / 1000).toInt()
        } else {
            0
        }
    val waitLabel = if (waitSeconds < 60) "${waitSeconds}s" else "${waitSeconds / 60}m ${waitSeconds % 60}s"
    val rowTitle = if (waiting) "$title · $waitLabel" else title
    Column(Modifier.fillMaxWidth().testTag("work-tool-${steps.first().id}")) {
        Surface(color = cs.background, shape = MaterialTheme.shapes.extraSmall) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (waitFraction > 0f) {
                            drawRect(color = cs.secondaryContainer, size = Size(size.width * waitFraction, size.height))
                        }
                    }
                    .clickable { details = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    val pulse by rememberInfiniteTransition(label = "Tool activity").animateFloat(
                        .8f,
                        1f,
                        infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "Tool icon pulse",
                    )
                    if (notionPainter != null) {
                        Icon(
                            notionPainter,
                            null,
                            Modifier.size(16.dp).graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                                alpha = pulse
                            },
                            tint = cs.primary,
                        )
                    } else {
                        Icon(
                            icon,
                            null,
                            Modifier.size(20.dp).graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                                alpha = pulse
                            },
                            tint = cs.primary,
                        )
                    }
                    ShimmerStatusText(
                        rowTitle,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                } else {
                    if (notionPainter != null) {
                        Icon(
                            notionPainter,
                            null,
                            Modifier.size(16.dp),
                            tint = if (failed) cs.error else cs.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            icon,
                            null,
                            Modifier.size(20.dp),
                            tint = if (failed) cs.error else cs.onSurfaceVariant,
                        )
                    }
                    MarqueeTitle(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        surface = cs.background,
                        color = if (failed) cs.error else cs.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.ExpandMore, "View tool details", Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        if (step.tool == "download_file") {
            step.download?.let { DownloadStatusCard(it, running, failed) }
        }
        val curlProgress =
            remember(step.tool, step.output) {
                if (step.tool == "run_command") parseCurlProgress(step.output) else null
            }
        val curlName =
            remember(step.arguments) {
                curlTargetName(runCatching { JSONObject(step.arguments).optString("command") }.getOrDefault(""))
            }
        if (curlProgress != null) {
            DownloadStatusCard(curlProgress.copy(path = curlName), running, failed)
        } else if (running && step.tool in setOf("run_command", "git_status", "git_diff") && step.output.isNotBlank()) {
            LiveCommandOutput(step.output)
        }
        if (pendingStep != null) {
            Surface(
                color = cs.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, null, tint = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Allow this action?", style = MaterialTheme.typography.titleSmall)
                    }
                    ToolArgumentsView(pendingStep.arguments)
                    if (pendingStep.tool in
                        listOf("run_command", "git_status", "git_diff")
                    ) {
                        Text(
                            "Runs in Termux with its file and network access.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                onApprove(pendingStep.id, true)
                            },
                            shape =
                                groupedRowShape(
                                    0,
                                    2,
                                ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Allow once") }
                        FilledTonalButton(
                            onClick = {
                                onApprove(pendingStep.id, false)
                            },
                            shape =
                                groupedRowShape(
                                    1,
                                    2,
                                ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Decline") }
                    }
                }
            }
        }
        if (details) {
            HydraBottomSheet(onDismiss = { details = false }) { dismiss ->
                // The whole sheet scrolls, including its title, so long results get the full height.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .testTag("work-tool-${steps.first().id}-details"),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(20.dp), tint = cs.primary)
                        Spacer(Modifier.width(12.dp))
                        MarqueeTitle(
                            title,
                            Modifier.weight(1f),
                            surface = cs.surfaceContainerLow,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close tool details") }
                    }
                    when {
                        running -> {
                            BusyStatusRow(Modifier.padding(top = 8.dp)) {
                                Text("Running", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            }
                        }

                        steps.any { it.state !in setOf("Done", "Failed") } -> {
                            Text(
                                step.state,
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                            )
                        }

                        else -> Unit
                    }
                    steps.forEachIndexed { index, item ->
                        if (steps.size > 1) {
                            Text(
                                "Read ${index + 1} of ${steps.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (item.state == "Failed") cs.error else cs.onSurface,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        Text(
                            "Arguments",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = if (steps.size > 1) 8.dp else 16.dp, bottom = 8.dp),
                        )
                        ToolArgumentsView(item.arguments)
                        if (item.output.isNotBlank() || item.state == "Failed" || item.state == "Done") {
                            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("Result", style = MaterialTheme.typography.titleSmall)
                                    when (item.state) {
                                        "Done" -> {
                                            Icon(
                                                Icons.Outlined.Check,
                                                "Done",
                                                Modifier.size(18.dp),
                                                tint = cs.success,
                                            )
                                        }

                                        "Failed" -> {
                                            Icon(
                                                Icons.Outlined.Close,
                                                "Failed",
                                                Modifier.size(18.dp),
                                                tint = cs.error,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                ToolOutputView(item.output)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Live tail of a running Termux command, like the streaming output in opencode-style harnesses. */
@Composable
private fun LiveCommandOutput(output: String) {
    val cs = MaterialTheme.colorScheme
    val tail = remember(output) { output.trimEnd().lines().takeLast(8).joinToString("\n") }
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("command-live-output"),
    ) {
        Text(
            tail,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.code,
            color = cs.onSurfaceVariant,
            maxLines = 8,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun commandActivityLabel(command: String): String {
    val commandName =
        command
            .trim()
            .substringBefore(' ')
            .substringAfterLast('/')
            .lowercase()
    return when {
        command.trim().startsWith("git status") -> "Check Git status"
        command.trim().startsWith("git diff") -> "Review project changes"
        commandName == "git" -> "Work with the repository"
        commandName in setOf("gradle", "gradlew", "gradlew.bat") -> "Build and check the project"
        commandName in setOf("ls", "find", "pwd", "tree") -> "Explore project files"
        commandName in setOf("rg", "grep") -> "Search the project"
        commandName in setOf("cat", "head", "tail", "sed") -> "Inspect project content"
        commandName in setOf("pytest", "jest") || command.contains(" test") -> "Run project checks"
        else -> "Run a project command"
    }
}

internal fun toolLabel(
    name: String,
    args: JSONObject,
): String {
    val path = args.optString("path").substringAfterLast('/')
    return when (name) {
        "ask_user" -> "Ask a few questions"
        "quiz_user" -> "Quiz the user"
        "web_search_batch" -> "Research the web"
        "find_in_page" -> "Find relevant passages"
        "get_time" -> "Check the current time"
        "wait_for", "wait_until" -> "Wait before continuing"
        "schedule_task" -> "Schedule a follow-up"
        "start_task" -> "Start background task: ${args.optString("title")}"
        "read_task_output" -> "Check a background task"
        "list_scheduled_tasks" -> "Check scheduled tasks"
        "update_scheduled_task" -> "Update a schedule"
        "cancel_scheduled_task" -> "Cancel a schedule"
        in DeviceTools.names -> humanizedLabel(name)
        "list_files" -> "List ${path.ifBlank{"project files"}}"
        "list_files_recursive" -> "List files recursively in ${path.ifBlank{"project"}}"
        "read_file" -> "Read $path"
        "write_file" -> "Write $path"
        "run_command" -> commandActivityLabel(args.optString("command"))
        "search_files" -> "Search project: ${args.optString("query")}"
        "find_files" -> "Find ${args.optString("pattern")}"
        "file_info" -> "Inspect $path"
        "create_directory" -> "Create directory $path"
        "append_file" -> "Append to $path"
        "replace_in_file" -> "Edit $path"
        "apply_diff" -> "Edit $path"
        "read_image" -> "Read image $path"
        "delete_file" -> "Delete $path"
        "move_file" -> "Move ${args.optString("source")} → ${args.optString("destination")}"
        "copy_file" -> "Copy ${args.optString("source")} → ${args.optString("destination")}"
        "web_search" -> "Search the web: ${args.optString("query")}"
        "fetch_url" -> "Read ${args.optString("url")}"
        "download_file" ->
            "Download " +
                args.optString("path").substringAfterLast('/').ifBlank {
                    downloadFileName(args.optString("url"), null)
                }
        "git_status" -> "Check Git status"
        "git_diff" -> "Inspect Git diff"
        else -> name.replace('_', ' ')
    }
}

@Preview(name = "Task chat light")
@Preview(name = "Task chat dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Task chat large text", fontScale = 1.5f)
@Composable
private fun TaskChatPreview() {
    val step =
        WorkStep(
            "replace_in_file",
            "{\"path\":\"app/MainActivity.kt\",\"old_text\":\"old label\",\"new_text\":\"new label\"}",
            id = "preview-tool",
        )
    HydraTheme {
        WorkChatScreen(
            WorkChatUiState(
                "Improve the project’s startup",
                "Agentic Hydra",
                listOf(
                    Message("user", "Inspect the app and improve startup."),
                    Message("assistant", "I’ll inspect the project, then make a focused change."),
                    Message("tool", "replace_in_file", id = step.id),
                ),
                listOf(step),
                step.id,
                true,
                "Needs approval",
            ),
            "",
            {
            },
            WorkChatActions(),
        ) { TextButton(onClick = {}) { Text("Project model") } }
    }
}
