package app.agentichydra

import android.content.res.Configuration
import android.text.Layout
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil

/** Separate fenced code so every code block has a copy action and horizontal scrolling. */
data class MarkdownSection(
    val text: String,
    val language: String? = null,
    val table: List<List<String>>? = null,
)

object MarkdownSections {
    fun parse(input: String): List<MarkdownSection> {
        val result = mutableListOf<MarkdownSection>()
        val text = StringBuilder()
        val code = StringBuilder()
        var fence = ""
        var language = ""
        input.split('\n').forEach { line ->
            val match = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
            if (fence.isEmpty() && match != null) {
                if (text.isNotEmpty()) result += MarkdownSection(text.toString().trimEnd('\n'))
                text.clear()
                fence = match.groupValues[1]
                language = match.groupValues[2].trim().substringBefore(' ')
            } else if (fence.isNotEmpty() && line.trim().length >= fence.length && line.trim().all { it == fence[0] }) {
                result += MarkdownSection(code.toString().removeSuffix("\n"), language)
                code.clear()
                fence = ""
            } else if (fence.isNotEmpty()) {
                code.append(line).append('\n')
            } else {
                text.append(line).append('\n')
            }
        }
        if (fence.isNotEmpty()) result += MarkdownSection(code.toString().removeSuffix("\n"), language)
        if (text.isNotEmpty()) result += MarkdownSection(text.toString().trimEnd('\n'))
        return result.flatMap { section ->
            if (section.language != null) listOf(section) else splitTables(section.text)
        }
    }

    private fun splitTables(text: String): List<MarkdownSection> {
        val lines = text.lines()
        val sections = mutableListOf<MarkdownSection>()
        val prose = StringBuilder()

        fun cells(line: String) =
            line
                .trim()
                .removePrefix("|")
                .removeSuffix("|")
                .split(Regex("(?<!\\\\)\\|"))
                .map { it.trim().replace("\\|", "|") }
        var i = 0
        while (i < lines.size) {
            val separators = if (i + 1 < lines.size) cells(lines[i + 1]) else emptyList()
            if (
                lines[i].contains('|') &&
                separators.size >= 2 &&
                separators.all { Regex(":?-{3,}:?").matches(it) } &&
                cells(lines[i]).size == separators.size
            ) {
                if (prose.isNotEmpty()) {
                    sections += MarkdownSection(prose.toString().trimEnd())
                    prose.clear()
                }
                val rows = mutableListOf(cells(lines[i]))
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += cells(lines[i])
                    i++
                }
                sections += MarkdownSection("", table = rows)
            } else {
                prose.append(lines[i]).append('\n')
                i++
            }
        }
        if (prose.isNotBlank()) sections += MarkdownSection(prose.toString().trimEnd())
        return sections
    }
}

@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
) {
    val sections = remember(text) { MarkdownSections.parse(text) }
    val cs = MaterialTheme.colorScheme
    val renderer = rememberMarkdownRenderer(MaterialTheme.typography.bodyLarge)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sections.forEach { section ->
            when {
                section.table != null -> {
                    MarkdownTable(section.table)
                }

                section.language != null -> {
                    val clipboard = LocalClipboardManager.current
                    val scroll = rememberScrollState()
                    val direction = LocalLayoutDirection.current
                    var copied by remember(section.text) { mutableStateOf(false) }
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    section.language.ifBlank { "Code" },
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(section.text))
                                    copied = true
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (copied) "Copied" else "Copy")
                                }
                            }
                            SelectionContainer {
                                Text(
                                    section.text,
                                    style = MaterialTheme.typography.code,
                                    softWrap = false,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 420.dp)
                                            .verticalScroll(rememberScrollState())
                                            .horizontalScrollFadingEdges(scroll, cs.surfaceContainerHigh, direction)
                                            .horizontalScroll(scroll)
                                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                }

                section.text.isNotBlank() -> {
                    val rendered = remember(section.text, renderer) { renderer.render(section.text.trim('\n', '\r')) }
                    MarkdownText(rendered, renderer, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val renderer = rememberMarkdownRenderer(MaterialTheme.typography.bodyMedium)
    val rendered =
        remember(rows, renderer) {
            rows.mapIndexed { index, row ->
                List(rows.first().size) { column ->
                    val content = row.getOrElse(column) { "" }
                    renderer.render(if (index == 0) "**$content**" else content)
                }
            }
        }
    val widths =
        remember(rendered, renderer, density.density) {
            List(rows.first().size) { column ->
                // Measure natural content widths, including inline-code spans, instead of dividing the viewport.
                val pixels = rendered.maxOf { Layout.getDesiredWidth(it[column], renderer.paint) }
                maxOf(64.dp, (ceil((pixels / density.density + 16) / 4) * 4).dp)
            }
        }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val totalWidth = widths.fold(0.dp) { sum, width -> sum + width }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = cs.surfaceContainer,
            modifier = Modifier.width(minOf(maxWidth, totalWidth)),
        ) {
            Column(
                Modifier
                    .horizontalScrollFadingEdges(
                        scroll,
                        cs.surfaceContainer,
                        direction,
                    ).horizontalScroll(scroll)
                    .testTag("markdown-table"),
            ) {
                rendered.forEachIndexed { index, row ->
                    Row(Modifier.background(if (index == 0) cs.primaryContainer else cs.surfaceContainer)) {
                        row.forEachIndexed { column, cell ->
                            MarkdownText(
                                cell,
                                renderer,
                                Modifier.width(widths[column]).padding(horizontal = 8.dp, vertical = 4.dp),
                                if (index == 0) cs.onPrimaryContainer.toArgb() else cs.onSurface.toArgb(),
                            )
                        }
                    }
                    if (index < rendered.lastIndex) HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(
    rendered: Spanned,
    renderer: MarkdownRenderer,
    modifier: Modifier = Modifier,
    color: Int = renderer.foreground,
) {
    AndroidView(factory = { context ->
        TextView(context).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setLineSpacing(0f, 1f)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        }
    }, modifier = modifier, update = { view ->
        val signature = rendered to color
        if (view.tag != signature) {
            view.typeface = renderer.metrics.typeface
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, renderer.metrics.size)
            view.setTextColor(color)
            view.setLinkTextColor(renderer.link)
            renderer.markwon.setParsedMarkdown(view, rendered)
            view.tag = signature
        }
    })
}

@Preview(name = "Markdown light")
@Preview(name = "Markdown dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Markdown large text", fontScale = 1.5f)
@Composable
private fun MarkdownPreview() {
    HydraTheme {
        Surface {
            MarkdownBody(
                "# Clear headings\n\nBody text with `inline code`, **emphasis**, and consistent spacing.\n\n## Details\n\n- First item\n- Second item\n\n| Name | Description |\n|---|---|\n| `response_time` | A naturally wide cell that scrolls horizontally |\n\n```kotlin\nval result = inspectProject()\n```",
                Modifier.padding(16.dp),
            )
        }
    }
}
