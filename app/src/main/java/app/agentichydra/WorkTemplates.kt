package app.agentichydra

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val placeholder = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_ ]{0,39}?)\s*}}""")

private data class WorkTemplate(val id: String, val name: String, val prompt: String) {
    val variables get() = placeholder.findAll(prompt).map { it.groupValues[1] }.distinct().toList()

    fun fill(values: Map<String, String>) =
        placeholder.replace(prompt) { values.getValue(it.groupValues[1]) }

    fun json() = JSONObject().put("id", id).put("name", name).put("prompt", prompt)
}

private val defaultTemplates = listOf(
    WorkTemplate("review", "Code review", "Review {{files}} for correctness, regressions, and missing tests. Prioritize findings and cite the relevant lines."),
    WorkTemplate("audit", "Bug audit", "Investigate {{symptoms}} in {{area}}. Identify likely causes and explain how to verify them."),
    WorkTemplate("article", "Summarize article", "Summarize {{article}}. Include the main argument, key evidence, and any limitations."),
)

private class WorkTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("work_templates", Context.MODE_PRIVATE)

    fun load(): List<WorkTemplate> {
        val raw = prefs.getString("templates", null) ?: return defaultTemplates
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                WorkTemplate(item.getString("id"), item.getString("name"), item.getString("prompt"))
            }
        }.getOrDefault(defaultTemplates)
    }

    fun save(templates: List<WorkTemplate>) {
        prefs.edit { putString("templates", JSONArray(templates.map { it.json() }).toString()) }
    }
}

@Composable
internal fun WorkTemplatesSheet(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { WorkTemplateStore(context) }
    var templates by remember { mutableStateOf(store.load()) }
    var editor by remember { mutableStateOf<WorkTemplate?>(null) }
    var selected by remember { mutableStateOf<WorkTemplate?>(null) }
    var deleting by remember { mutableStateOf<WorkTemplate?>(null) }
    fun replace(updated: List<WorkTemplate>) {
        store.save(updated)
        templates = updated
    }

    HydraBottomSheet(onDismiss = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetHeader(Icons.Outlined.Description, "Prompt templates")
            val editing = editor
            val filling = selected
            when {
                editing != null -> TemplateEditor(
                    editing,
                    onCancel = { editor = null },
                    onSave = { updated ->
                        replace(
                            if (templates.any { it.id == updated.id }) {
                                templates.map { if (it.id == updated.id) updated else it }
                            } else {
                                templates + updated
                            },
                        )
                        editor = null
                    },
                )
                filling != null -> TemplateVariables(filling, onCancel = { selected = null }) { prompt ->
                    onInsert(prompt)
                    dismiss()
                }
                else -> {
                    if (templates.isEmpty()) Text("No saved templates.")
                    templates.forEach { template ->
                        ListItem(
                            headlineContent = { Text(template.name) },
                            modifier = Modifier.clickable {
                                if (template.variables.isEmpty()) {
                                    onInsert(template.prompt)
                                    dismiss()
                                } else {
                                    selected = template
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editor = template }) {
                                        Icon(Icons.Outlined.Edit, "Edit ${template.name}")
                                    }
                                    IconButton(onClick = { deleting = template }) {
                                        Icon(Icons.Outlined.Delete, "Delete ${template.name}")
                                    }
                                }
                            },
                        )
                    }
                    Button(onClick = { editor = WorkTemplate(UUID.randomUUID().toString(), "", "") }) {
                        Text("New template")
                    }
                }
            }
        }
    }
    deleting?.let { template ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${template.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    replace(templates.filterNot { it.id == template.id })
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TemplateEditor(template: WorkTemplate, onCancel: () -> Unit, onSave: (WorkTemplate) -> Unit) {
    var name by remember(template) { mutableStateOf(template.name) }
    var prompt by remember(template) { mutableStateOf(template.prompt) }
    OutlinedTextField(
        name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
    )
    OutlinedTextField(
        prompt, { prompt = it.take(16_000) }, Modifier.fillMaxWidth(), label = { Text("Prompt") },
        supportingText = { Text("Use {{variable}} for text you want to fill in each time.") },
        minLines = 3,
        maxLines = 8,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
            onSave(template.copy(name = name.trim(), prompt = prompt.trim()))
        }) { Text("Save template") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun TemplateVariables(template: WorkTemplate, onCancel: () -> Unit, onInsert: (String) -> Unit) {
    val variables = remember(template) { template.variables }
    val values = remember(template) { mutableStateMapOf<String, String>() }
    Text(template.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    variables.forEach { variable ->
        OutlinedTextField(
            values[variable].orEmpty(),
            { values[variable] = it },
            Modifier.fillMaxWidth(),
            label = { Text(variable.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
            maxLines = 5,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = variables.all { !values[it].isNullOrBlank() },
            onClick = { onInsert(template.fill(values)) },
        ) { Text("Insert prompt") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(fontScale = 1.5f)
@Composable
private fun TemplateFieldsPreview() {
    HydraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateVariables(defaultTemplates.first(), {}, {})
        }
    }
}
