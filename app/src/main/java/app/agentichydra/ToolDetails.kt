package app.agentichydra

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Keys whose values read better as monospace blocks than as prose. */
private val codeKeys = setOf("content", "old_text", "new_text", "diff", "command")

private fun fieldLabel(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
internal fun ToolArgumentsView(
    arguments: String,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(arguments) { runCatching { JSONObject(arguments) }.getOrNull() }
    when {
        arguments.isBlank() -> EmptyValue("No arguments", modifier)
        parsed == null -> CodeBlock(arguments, modifier)
        parsed.length() == 0 -> EmptyValue("No arguments", modifier)
        else -> TreeObject(parsed, modifier)
    }
}

@Composable
internal fun ToolOutputView(
    output: String,
    modifier: Modifier = Modifier,
) {
    val trimmed = output.trim()
    val parsed =
        remember(trimmed) {
            when {
                trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                else -> null
            }
        }
    when {
        trimmed.isBlank() -> EmptyValue("No result details", modifier)
        parsed is JSONObject && parsed.length() == 0 -> EmptyValue("No result details", modifier)
        parsed is JSONArray && parsed.length() == 0 -> EmptyValue("No result details", modifier)
        parsed is JSONObject -> TreeObject(parsed, modifier)
        parsed is JSONArray -> TreeArray(parsed, modifier)
        else -> CodeBlock(output, modifier)
    }
}

@Composable
private fun TreeObject(
    value: JSONObject,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    ancestors: List<Boolean> = emptyList(),
) {
    val entries =
        value
            .keys()
            .asSequence()
            .map { it to value.opt(it) }
            .sortedBy { it.first }
            .toList()
    Column(modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, (key, child) ->
            TreeRow(fieldLabel(key), key, child, depth, index == entries.lastIndex, ancestors)
        }
    }
}

@Composable
private fun TreeArray(
    value: JSONArray,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    ancestors: List<Boolean> = emptyList(),
) {
    Column(modifier.fillMaxWidth()) {
        for (index in 0 until value.length()) {
            TreeRow("Item ${index + 1}", "", value.opt(index), depth, index == value.length() - 1, ancestors)
        }
    }
}

@Composable
private fun TreeRow(
    label: String,
    key: String,
    value: Any?,
    depth: Int,
    last: Boolean,
    ancestors: List<Boolean>,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    val children =
        when (value) {
            is JSONObject -> value.takeIf { it.length() > 0 }
            is JSONArray -> value.takeIf { it.length() > 0 }
            else -> null
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawTreeLines(depth, last, children != null, ancestors, lineColor) }
                .padding(vertical = 3.dp),
        ) {
            Spacer(Modifier.width((16 + depth * 16).dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (value) {
                    is JSONObject -> if (value.length() == 0) ValueText("Empty object")
                    is JSONArray -> if (value.length() == 0) ValueText("Empty list")
                    else -> ScalarValue(key, value)
                }
            }
        }
        if (children != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 8.dp.toPx() + depth * 16.dp.toPx()
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    },
            ) {
                when (children) {
                    is JSONObject -> TreeObject(children, depth = depth + 1, ancestors = ancestors + !last)
                    is JSONArray -> TreeArray(children, depth = depth + 1, ancestors = ancestors + !last)
                }
            }
        }
    }
}

private fun DrawScope.drawTreeLines(
    depth: Int,
    last: Boolean,
    hasChildren: Boolean,
    ancestors: List<Boolean>,
    color: Color,
) {
    val step = 16.dp.toPx()
    val base = 8.dp.toPx()
    val stroke = 1.dp.toPx()
    val center = size.height / 2f
    for (i in 0 until (depth - 1).coerceAtLeast(0)) {
        if (ancestors.getOrElse(i) { false }) {
            val x = base + i * step
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        }
    }
    val parentX = if (depth == 0) base else base + (depth - 1) * step
    val contentX = 16.dp.toPx() + depth * step
    val ownX = base + depth * step
    val parentEnd = if (last) center else size.height
    drawLine(color, Offset(parentX, 0f), Offset(parentX, parentEnd), stroke)
    drawLine(color, Offset(parentX, center), Offset(contentX, center), stroke)
    if (hasChildren) {
        drawLine(color, Offset(ownX, center), Offset(ownX, size.height), stroke)
    }
}

@Composable
private fun ScalarValue(
    key: String,
    value: Any?,
) {
    when (value) {
        null, JSONObject.NULL -> ValueText("None")
        is Boolean -> ValueText(if (value) "Yes" else "No")
        is Number -> ValueText(value.toString())
        else -> StringValue(key, value.toString())
    }
}

@Composable
private fun StringValue(
    key: String,
    text: String,
) {
    when {
        text.isBlank() -> ValueText("Empty")
        key in codeKeys || text.contains('\n') || text.length > 240 -> CodeBlock(text)
        else -> SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EmptyValue(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scrollable = text.length > 1200 || text.count { it == '\n' } > 24
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .then(
                if (scrollable) {
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            ),
    ) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.code,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                softWrap = false,
            )
        }
    }
}
