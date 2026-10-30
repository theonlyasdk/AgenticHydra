package app.agentichydra

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorkFilesSheet(
    vm: WorkModel,
    onDismiss: () -> Unit,
) {
    val selected = vm.selectedFile
    var full by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(selected?.first) {
        if (selected != null) full = true
    }
    val dismiss = {
        vm.selectedFile = null
        full = false
        onDismiss()
    }
    HydraBottomSheet(
        onDismiss = dismiss,
        fillHeight = full && selected != null,
        swipeEnabled = selected == null,
    ) { sheetDismiss ->
        if (selected == null) {
            FileListContent(vm, sheetDismiss)
        } else {
            FileEditorContent(
                vm = vm,
                path = selected.first,
                initialText = selected.second,
                full = full,
                onFullChange = { full = it },
                onBack = {
                    vm.selectedFile = null
                    full = false
                },
                onDismiss = sheetDismiss,
            )
        }
    }
}

@Composable
private fun FileListContent(
    vm: WorkModel,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = cs.primary)
            Text("Project files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            HintIconButton("Refresh project files", Icons.Outlined.Refresh, onClick = { vm.refreshFiles() })
            HintIconButton("Close project files", Icons.Outlined.Close, onClick = onDismiss)
        }
        Breadcrumb(vm)
        if (vm.files.isEmpty()) {
            WorkEmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = if (vm.projectUri == null) "Choose a project folder" else "No files here",
                body =
                    if (vm.projectUri == null) {
                        "Select a project folder to browse its files."
                    } else {
                        "This folder is empty."
                    },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).sheetContentScrollOnly(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(vm.files) { path ->
                    val directory = path.startsWith('/')
                    val name = path.removePrefix("/")
                    FileRow(name, directory) {
                        if (directory) vm.enterFolder(name) else vm.browse(fullPath(vm.browserPath, path))
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(vm: WorkModel) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val parts = vm.browserPath.split('/').filter { it.isNotBlank() }
    LaunchedEffect(vm.browserPath) {
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreadcrumbPart("Project", Icons.Outlined.FolderOpen, current = parts.isEmpty()) {
            vm.refreshFiles("")
        }
        parts.forEachIndexed { index, part ->
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier.padding(horizontal = 2.dp).size(16.dp),
                tint = cs.outline,
            )
            BreadcrumbPart(part, null, current = index == parts.lastIndex) {
                vm.refreshFiles(parts.take(index + 1).joinToString("/"))
            }
        }
    }
}

@Composable
private fun BreadcrumbPart(
    label: String,
    icon: ImageVector?,
    current: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .then(if (current) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                Modifier.size(16.dp),
                tint = if (current) cs.onSurface else cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (current) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileRow(
    name: String,
    directory: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                null,
                tint = if (directory) cs.primary else cs.onSurfaceVariant,
            )
        },
        headlineContent = {
            MarqueeTitle(name, surface = cs.surfaceContainerLow, style = MaterialTheme.typography.bodyLarge)
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = cs.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun FileEditorContent(
    vm: WorkModel,
    path: String,
    initialText: String,
    full: Boolean,
    onFullChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val editorScheme = remember(cs) { editorColors(cs) }
    var menu by remember { mutableStateOf(false) }
    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var saving by remember { mutableStateOf(false) }
    var language by remember(path) { mutableStateOf<TextMateLanguage?>(null) }
    var textMateScheme by remember { mutableStateOf<EditorColorScheme?>(null) }
    val dark = cs.background.luminance() < 0.5f

    LaunchedEffect(initialText) {
        if (editor?.text?.toString() != initialText) editor?.setText(initialText)
    }
    LaunchedEffect(path, dark) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching {
                    TextMateSupport.prepare(context, dark)
                    TextMateSupport.language(path) to TextMateSupport.colorScheme(cs)
                }.getOrNull()
            }
        language = loaded?.first
        textMateScheme = loaded?.second
    }
    LaunchedEffect(language, editor) {
        editor?.setEditorLanguage(language ?: EmptyLanguage())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (full) Modifier.fillMaxHeight() else Modifier.heightIn(min = 280.dp, max = 560.dp))
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HintIconButton("Back to project files", Icons.AutoMirrored.Outlined.ArrowBack, onClick = onBack)
            MarqueeTitle(
                path,
                Modifier.weight(1f),
                surface = cs.surfaceContainerLow,
                style = MaterialTheme.typography.titleMedium,
            )
            Box {
                HintIconButton("File options", Icons.Outlined.MoreVert, onClick = { menu = true })
                HydraOverflowMenu(menu, { menu = false }, headerText = path) {
                    DropdownMenuItem(
                        text = { Text(if (saving) "Saving…" else "Save") },
                        leadingIcon = { Icon(Icons.Outlined.Save, null) },
                        enabled = !saving,
                        onClick = {
                            menu = false
                            val text = editor?.text?.toString() ?: initialText
                            scope.launch {
                                saving = true
                                runCatching { vm.saveFile(path, text) }
                                    .onSuccess { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                                    .onFailure {
                                        Toast
                                            .makeText(
                                                context,
                                                it.message ?: "Couldn't save this file.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                    }
                                saving = false
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy all") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(editor?.text?.toString() ?: initialText))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (wrap) "Disable word wrap" else "Enable word wrap") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.WrapText, null) },
                        onClick = {
                            menu = false
                            wrap = !wrap
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (full) "Exit full screen" else "Full screen") },
                        leadingIcon = {
                            Icon(if (full) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, null)
                        },
                        onClick = {
                            menu = false
                            onFullChange(!full)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = {
                            menu = false
                            vm.browse(path)
                            Toast.makeText(context, "Reloading…", Toast.LENGTH_SHORT).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            menu = false
                            val text = editor?.text?.toString() ?: initialText
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(
                                            Intent.ACTION_SEND,
                                        ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                        "Share file",
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            HintIconButton("Close file", Icons.Outlined.Close, onClick = onDismiss)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(cs.surfaceContainerHigh)
                .testTag("work-file-editor"),
        ) {
            AndroidView(
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        setText(initialText)
                        setTextSize(14f)
                        setTypefaceText(Typeface.MONOSPACE)
                        setWordwrap(wrap)
                        setLineNumberEnabled(true)
                        setHighlightCurrentLine(true)
                        setEditable(true)
                        setUndoEnabled(true)
                        setLigatureEnabled(false)
                        setColorScheme(editorScheme)
                        setBackgroundColor(cs.surfaceContainerHigh.toArgb())
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setEdgeEffectColor(android.graphics.Color.TRANSPARENT)
                        isNestedScrollingEnabled = false
                        editor = this
                    }
                },
                update = { view ->
                    view.setWordwrap(wrap)
                    val scheme = textMateScheme ?: editorScheme
                    if (view.colorScheme !== scheme) {
                        (view.colorScheme as? TextMateColorScheme)?.detachEditor(view)
                        view.setColorScheme(scheme)
                    }
                    view.setBackgroundColor(cs.surfaceContainerHigh.toArgb())
                },
                onRelease = { view ->
                    (view.colorScheme as? TextMateColorScheme)?.detachEditor(view)
                    view.release()
                    editor = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun WorkChangesSheet(
    changes: List<FileChange>,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    HydraBottomSheet(onDismiss = onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Difference, null, tint = cs.primary)
                Text("Changes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (changes.isNotEmpty()) {
                    HintIconButton(
                        "Copy changes summary",
                        Icons.Outlined.ContentCopy,
                        onClick = {
                            val summary =
                                changes.joinToString("\n") { change ->
                                    val diff = diffPreview(change.before, change.after)
                                    "${change.path} (+${diff.added} −${diff.removed})"
                                }
                            clipboard.setText(AnnotatedString(summary))
                        },
                    )
                }
                HintIconButton("Close changes", Icons.Outlined.Close, onClick = dismiss)
            }
            if (changes.isEmpty()) {
                WorkEmptyState(
                    icon = Icons.Outlined.Difference,
                    title = "No changes yet",
                    body = "File edits from this task will appear here.",
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 600.dp).sheetContentScrollOnly(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(changes) { change -> ChangeCard(change, onOpenFile) }
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(
    change: FileChange,
    onOpenFile: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val preview = remember(change.path, change.before, change.after) { diffPreview(change.before, change.after) }
    val status =
        when {
            change.before.isEmpty() && change.after.isNotEmpty() -> {
                ChangeStatus("New", Icons.Outlined.AddCircle, cs.success, cs.successContainer, cs.onSuccessContainer)
            }

            change.after.isEmpty() && change.before.isNotEmpty() -> {
                ChangeStatus("Deleted", Icons.Outlined.Delete, cs.error, cs.errorContainer, cs.onErrorContainer)
            }

            else -> {
                ChangeStatus("Modified", Icons.Outlined.Edit, cs.primary, cs.primaryContainer, cs.onPrimaryContainer)
            }
        }
    Surface(color = cs.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(status.icon, null, Modifier.size(20.dp), tint = status.color)
                MarqueeTitle(
                    change.path,
                    Modifier.weight(1f),
                    surface = cs.surfaceContainerHigh,
                    style = MaterialTheme.typography.titleSmall,
                )
                ChangeBadge(status.label, status.container, status.content)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("+${preview.added}", style = MaterialTheme.typography.labelMedium, color = cs.success)
                Text("−${preview.removed}", style = MaterialTheme.typography.labelMedium, color = cs.error)
                Spacer(Modifier.weight(1f))
                if (change.after.isNotEmpty()) {
                    TextButton(
                        onClick = { onOpenFile(change.path) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Open file")
                    }
                }
            }
            DiffLines(preview)
        }
    }
}

@Composable
private fun ChangeBadge(
    label: String,
    container: Color,
    content: Color,
) {
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun DiffLines(preview: DiffPreview) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(cs.surfaceContainer)
            .padding(vertical = 6.dp),
    ) {
        Column {
            if (preview.lines.isEmpty()) {
                Text(
                    "Empty file",
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                preview.lines.forEach { line ->
                    val background =
                        when (line.kind) {
                            DiffKind.Added -> cs.successContainer
                            DiffKind.Removed -> cs.errorContainer
                            DiffKind.Context -> Color.Transparent
                        }
                    val foreground =
                        when (line.kind) {
                            DiffKind.Added -> cs.onSuccessContainer
                            DiffKind.Removed -> cs.onErrorContainer
                            DiffKind.Context -> cs.onSurfaceVariant
                        }
                    Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Text(
                            when (line.kind) {
                                DiffKind.Added -> "+"
                                DiffKind.Removed -> "-"
                                DiffKind.Context -> " "
                            },
                            style = MaterialTheme.typography.code,
                            color = foreground,
                            modifier = Modifier.width(12.dp),
                        )
                        Text(
                            line.text.ifEmpty { " " },
                            style = MaterialTheme.typography.code,
                            color = foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (preview.hidden > 0) {
                    Text(
                        "… ${preview.hidden} more lines",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkEmptyState(
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

private data class ChangeStatus(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val container: Color,
    val content: Color,
)

private enum class DiffKind { Context, Added, Removed }

private data class DiffLine(
    val kind: DiffKind,
    val text: String,
)

private data class DiffPreview(
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int,
    val hidden: Int,
)

private fun diffPreview(
    before: String,
    after: String,
    maxLines: Int = 24,
): DiffPreview {
    if (before == after) return DiffPreview(emptyList(), 0, 0, 0)
    val old = if (before.isEmpty()) emptyList() else before.lines()
    val new = if (after.isEmpty()) emptyList() else after.lines()
    var prefix = 0
    while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.size - prefix && suffix < new.size - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
    ) {
        suffix++
    }
    val oldEnd = old.size - suffix
    val newEnd = new.size - suffix
    val removed = (oldEnd - prefix).coerceAtLeast(0)
    val added = (newEnd - prefix).coerceAtLeast(0)
    val lines = mutableListOf<DiffLine>()
    old.subList((prefix - 3).coerceAtLeast(0), prefix).forEach { lines += DiffLine(DiffKind.Context, it) }
    old.subList(prefix, oldEnd).forEach { lines += DiffLine(DiffKind.Removed, it) }
    new.subList(prefix, newEnd).forEach { lines += DiffLine(DiffKind.Added, it) }
    old.subList(oldEnd, (oldEnd + 3).coerceAtMost(old.size)).forEach { lines += DiffLine(DiffKind.Context, it) }
    val visible = if (lines.size > maxLines) lines.take(maxLines) else lines
    return DiffPreview(visible, added, removed, (lines.size - visible.size).coerceAtLeast(0))
}

private fun fullPath(
    parent: String,
    name: String,
): String = if (parent.isEmpty()) name else "$parent/$name"

private fun editorColors(cs: ColorScheme): EditorColorScheme =
    EditorColorScheme().apply {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, cs.surfaceContainerHigh.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, cs.onSurface.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, cs.surfaceContainerHigh.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, cs.onSurfaceVariant.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, cs.primary.toArgb())
        setColor(EditorColorScheme.CURRENT_LINE, cs.surfaceContainerHighest.toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, cs.primaryContainer.toArgb())
        setColor(EditorColorScheme.TEXT_SELECTED, cs.onPrimaryContainer.toArgb())
        setColor(EditorColorScheme.LINE_DIVIDER, cs.outlineVariant.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB, cs.onSurfaceVariant.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_TRACK, cs.surfaceContainerHigh.toArgb())
        setColor(EditorColorScheme.SELECTION_HANDLE, cs.primary.toArgb())
        setColor(EditorColorScheme.SELECTION_INSERT, cs.primary.toArgb())
    }
