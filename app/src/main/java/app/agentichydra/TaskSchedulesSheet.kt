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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var editRunAt by remember { mutableStateOf(0L) }
    var editError by remember { mutableStateOf<String?>(null) }
    val records = schedules.records

    fun beginEdit(record: ScheduledTask) {
        editTitle = record.title
        editPrompt = record.prompt
        editRunAt = record.runAt
        editError = null
        editing = record
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(Icons.Outlined.Schedule, "Scheduled tasks") {
            HintIconButton("Close scheduled tasks", Icons.Outlined.Close, onClick = onDismiss)
        }
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) {
                item { HydraEmptyState(Icons.Outlined.Schedule, "No scheduled tasks") }
            }
            items(records, key = { it.id }) { record ->
                var rowMenu by remember { mutableStateOf(false) }
                Surface(
                    color = cs.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                    onClick = { beginEdit(record) },
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
                                    HydraMenuItem("Open conversation", Icons.Outlined.ChatBubbleOutline) {
                                        rowMenu = false
                                        onOpen(record.conversationId)
                                    }
                                    HydraMenuItem(
                                        "Edit",
                                        Icons.Outlined.Edit,
                                        enabled = record.state in setOf("Scheduled"),
                                    ) {
                                        rowMenu = false
                                        beginEdit(record)
                                    }
                                    HydraMenuItem(
                                        "Cancel task",
                                        Icons.Outlined.Cancel,
                                        enabled = record.state in setOf("Scheduled", "Running"),
                                    ) {
                                        rowMenu = false
                                        schedules.cancel(record.id)
                                    }
                                    HydraMenuItem("Delete", Icons.Outlined.DeleteOutline, tint = cs.error) {
                                        rowMenu = false
                                        deleting = record
                                    }
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
        var showDatePicker by remember(record.id) { mutableStateOf(false) }
        var showTimePicker by remember(record.id) { mutableStateOf(false) }
        var pickedDate by remember(record.id) { mutableStateOf<LocalDate?>(null) }
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
                Surface(
                    onClick = { showDatePicker = true },
                    color = cs.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("Run at") },
                        supportingContent = {
                            Text(
                                DateFormat.getMediumDateFormat(context).format(Date(editRunAt)) + " · " +
                                    DateFormat.getTimeFormat(context).format(Date(editRunAt)),
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.Event, null, tint = cs.primary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                        colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
                    )
                }
                editError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        try {
                            schedules.edit(record.id, editTitle, editPrompt, editRunAt)
                            dismiss()
                        } catch (e: Exception) {
                            editError = e.message
                        }
                    },
                    enabled = editTitle.isNotBlank() && editPrompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) { Text("Save") }
            }
        }
        if (showDatePicker) {
            val initialDate = Instant.ofEpochMilli(editRunAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val dateState =
                rememberDatePickerState(
                    initialSelectedDateMillis =
                        initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val chosen =
                            dateState.selectedDateMillis?.let {
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                        showDatePicker = false
                        if (chosen != null) {
                            pickedDate = chosen
                            showTimePicker = true
                        }
                    }) { Text("Next") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                },
            ) { DatePicker(dateState) }
        }
        if (showTimePicker) {
            val initialTime = Instant.ofEpochMilli(editRunAt).atZone(ZoneId.systemDefault()).toLocalTime()
            val timeState =
                rememberTimePickerState(
                    initialHour = initialTime.hour,
                    initialMinute = initialTime.minute,
                    is24Hour = DateFormat.is24HourFormat(context),
                )
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text("Run time") },
                text = { TimePicker(timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        val date =
                            pickedDate
                                ?: Instant.ofEpochMilli(editRunAt).atZone(ZoneId.systemDefault()).toLocalDate()
                        editRunAt =
                            date
                                .atTime(timeState.hour, timeState.minute)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        pickedDate = null
                        showTimePicker = false
                    }) { Text("Set time") }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                },
            )
        }
    }
    deleting?.let { record ->
        ConfirmDialog(
            title = "Delete scheduled task?",
            body = "“${record.title}” will be removed.",
            confirmLabel = "Delete",
            onConfirm = {
                schedules.delete(record.id)
                deleting = null
            },
            onDismiss = { deleting = null },
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
