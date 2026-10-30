package app.agentichydra

import android.content.Intent
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable
internal fun TaskSchedulesSheet(
    schedules: TaskSchedules,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    HydraBottomSheet(onDismiss = onDismiss) { dismiss ->
        TaskScheduleContent(
            schedules,
            { id ->
                context.startActivity(Intent(context, WorkChatActivity::class.java).putExtra("conversationId", id))
            },
            dismiss,
        )
    }
}

@Composable
private fun TaskScheduleContent(
    schedules: TaskSchedules,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }
    var editTime by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    val records = schedules.records
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Schedule, null, tint = cs.primary)
            Text("Scheduled tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            HintIconButton("Close scheduled tasks", Icons.Outlined.Close, onClick = onDismiss)
        }
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(56.dp), tint = cs.onSurfaceVariant)
                        Text(
                            "No scheduled tasks",
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            items(records, key = { it.id }) { record ->
                var rowMenu by remember { mutableStateOf(false) }
                Surface(
                    color = cs.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                    onClick = {
                        editTitle = record.title
                        editPrompt = record.prompt
                        editTime =
                            java.time.OffsetDateTime
                                .ofInstant(
                                    java.time.Instant.ofEpochMilli(record.runAt),
                                    java.time.ZoneId.systemDefault(),
                                ).toString()
                        editError = null
                        editing = record
                    },
                ) {
                    ListItem(
                        headlineContent = { MarqueeTitle(record.title, surface = cs.surfaceContainerHigh) },
                        supportingContent = {
                            Column {
                                Text(record.state, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    DateFormat.getMediumDateFormat(context).format(Date(record.runAt)) + " · " +
                                        DateFormat.getTimeFormat(context).format(Date(record.runAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (record.intervalSeconds > 0) {
                                    Text(
                                        "Repeats every ${record.intervalSeconds / 60} min · ${record.remaining} runs left",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (record.error.isNotBlank()) {
                                    Text(
                                        record.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.error,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(
                                    onClick = { rowMenu = true },
                                ) { Icon(Icons.Outlined.MoreHoriz, "Scheduled task options") }
                                HydraOverflowMenu(
                                    expanded = rowMenu,
                                    onDismiss = { rowMenu = false },
                                    headerText = record.title,
                                ) {
                                    DropdownMenuItem(text = {
                                        Text(
                                            "Open conversation",
                                        )
                                    }, leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = {
                                        rowMenu = false
                                        onOpen(record.conversationId)
                                    })
                                    DropdownMenuItem(
                                        text = { Text("Edit") },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        enabled = record.state in setOf("Scheduled"),
                                        onClick = {
                                            rowMenu = false
                                            editTitle = record.title
                                            editPrompt = record.prompt
                                            editTime =
                                                java.time.OffsetDateTime
                                                    .ofInstant(
                                                        java.time.Instant.ofEpochMilli(record.runAt),
                                                        java.time.ZoneId.systemDefault(),
                                                    ).toString()
                                            editError = null
                                            editing = record
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Cancel task") },
                                        leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                        enabled = record.state in setOf("Scheduled", "Running"),
                                        onClick = {
                                            rowMenu = false
                                            schedules.cancel(record.id)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = cs.error) },
                                        onClick = {
                                            rowMenu = false
                                            deleting = record
                                        },
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
                    )
                }
            }
        }
    }
    editing?.let { record ->
        HydraBottomSheet(onDismiss = { editing = null }) { dismiss ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Edit scheduled task",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    editTitle,
                    { editTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    editPrompt,
                    { editPrompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(editTime, {
                    editTime = it
                }, label = {
                    Text(
                        "Run at (ISO-8601 with offset)",
                    )
                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                editError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val runAt = runCatching { ScheduleTools.parseTime(editTime) }.getOrNull()
                        if (runAt == null) {
                            editError = "Use an ISO-8601 timestamp with offset."
                        } else {
                            try {
                                schedules.edit(record.id, editTitle, editPrompt, runAt)
                                dismiss()
                            } catch (e: Exception) {
                                editError = e.message
                            }
                        }
                    },
                    enabled = editTitle.isNotBlank() && editPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) { Text("Save") }
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete scheduled task?") },
            text = { Text("“${record.title}” will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    schedules.delete(record.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Preview(name = "Schedules light")
@Preview(name = "Schedules dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Schedules large text", fontScale = 1.5f)
@Composable
private fun TaskSchedulePreview() {
    HydraTheme { Surface { } }
}
