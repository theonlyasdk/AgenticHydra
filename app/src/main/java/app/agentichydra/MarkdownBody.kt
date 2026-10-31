package app.agentichydra

import android.content.res.Configuration
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.CharacterStyle
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.animation.core.CubicBezierEasing
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
import kotlin.math.roundToInt

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

private const val TOKEN_FADE_MAX_CHARS = 96
private const val FADE_MIN_ALPHA = 0.25f
private const val FADE_BUCKETS = 8

/** Ease-out curve for the fade: bright immediately, then settle gently instead of ramping linearly. */
private val FADE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Roughly how many characters a settle covers at a given duration, so longer fades travel further. */
private fun fadeWidth(millis: Int): Int =
    (millis / 1000f * 150f).roundToInt().coerceIn(FADE_BUCKETS, TOKEN_FADE_MAX_CHARS)

private class StreamGrowth(
    var text: String = "",
)

/** Characters appended since the previous frame of a live stream, so only new tokens fade in. */
@Composable
private fun rememberAppendedChars(
    text: String,
    streaming: Boolean,
): Int {
    val growth = remember { StreamGrowth() }
    return remember(text, streaming) {
        val previous = growth.text
        growth.text = text
        when {
            !streaming || text.isEmpty() -> 0
            previous.isEmpty() -> text.length
            text.length > previous.length && text.startsWith(previous) -> text.length - previous.length
            else -> 0
        }
    }.coerceAtMost(TOKEN_FADE_MAX_CHARS)
}

@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    highlight: ChatHighlight? = null,
    streaming: Boolean = false,
) {
    val sections = remember(text) { MarkdownSections.parse(text) }
    val appended = rememberAppendedChars(text, streaming)
    val cs = MaterialTheme.colorScheme
    val palette = rememberMatchPalette()
    val renderer = rememberMarkdownRenderer(MaterialTheme.typography.bodyLarge)
    val query = highlight?.query.orEmpty()
    val starts = remember(sections, query) { sectionMatchStarts(sections, query) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sections.forEachIndexed { sectionIndex, section ->
            val active = sectionActive(highlight, starts, sectionIndex)
            when {
                section.table != null -> {
                    MarkdownTable(section.table, query, active)
                }

                section.language != null -> {
                    val clipboard = LocalClipboardManager.current
                    val scroll = rememberScrollState()
                    val direction = LocalLayoutDirection.current
                    var copied by remember(section.text) { mutableStateOf(false) }
                    val code =
                        remember(section.text, query, active, palette) {
                            highlightedText(section.text, query, active, palette)
                        }
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
                                    code,
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
                    val rendered =
                        remember(section.text, renderer, query, active, palette) {
                            highlightSpans(renderer.render(section.text.trim('\n', '\r')), query, active, palette)
                        }
                    MarkdownText(
                        rendered,
                        renderer,
                        Modifier.fillMaxWidth(),
                        fadeChars = if (sectionIndex == sections.lastIndex) appended else 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    rows: List<List<String>>,
    query: String = "",
    active: Int = -1,
) {
    if (rows.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val palette = rememberMatchPalette()
    val renderer = rememberMarkdownRenderer(MaterialTheme.typography.bodyMedium)
    val rendered =
        remember(rows, renderer, query, active, palette) {
            var ordinal = 0
            rows.mapIndexed { index, row ->
                List(rows.first().size) { column ->
                    val content = row.getOrElse(column) { "" }
                    val matches = occurrenceRanges(content, query).size
                    val spanned = renderer.render(if (index == 0) "**$content**" else content)
                    val local = if (active in ordinal until ordinal + matches) active - ordinal else -1
                    ordinal += matches
                    highlightSpans(spanned, query, local, palette)
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

/** Fades the newest characters of a live stream; the rest of the paragraph stays at full opacity. */
private class FadeSpan : CharacterStyle() {
    var alpha = 1f

    override fun updateDrawState(tp: TextPaint) {
        tp.alpha = (tp.alpha * alpha).roundToInt().coerceIn(0, 255)
    }
}

/**
 * Keeps the newest characters of a stream in a soft, eased gradient. The fade is positional rather
 * than a restart-from-zero animation, so a burst of tokens never blinks: text flows in while the
 * newest glyphs are faint, and the gradient lifts to full opacity once that burst settles.
 */
private class TailFade {
    var view: TextView? = null
    var spannable: Spannable? = null
    private val spans = List(FADE_BUCKETS) { FadeSpan() }
    private var owner: Spannable? = null

    /** Replaces the tail spans of [owner] with [chars] characters eased from faint to full. */
    fun draw(owner: Spannable, chars: Int, flat: Float) {
        if (this.owner !== owner) {
            this.owner?.let { previous -> spans.forEach(previous::removeSpan) }
            this.owner = owner
        }
        spans.forEach(owner::removeSpan)
        if (chars <= 0 || owner.isEmpty()) return
        val end = owner.length
        val start = (end - chars).coerceAtLeast(0)
        val length = end - start
        if (length <= 0) return
        val buckets = minOf(FADE_BUCKETS, length)
        for (index in 0 until buckets) {
            val from = start + length * index / buckets
            val to = start + length * (index + 1) / buckets
            val eased = FADE_EASING.transform((to - start).toFloat() / length)
            val gradient = FADE_MIN_ALPHA + (1f - FADE_MIN_ALPHA) * eased
            spans[index].alpha = gradient + (1f - gradient) * flat
            owner.setSpan(spans[index], from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

@Composable
private fun MarkdownText(
    rendered: Spanned,
    renderer: MarkdownRenderer,
    modifier: Modifier = Modifier,
    color: Int = renderer.foreground,
    fadeChars: Int = 0,
) {
    val fadeEnabled = LocalResponseFade.current
    val fadeMillis = LocalResponseFadeMillis.current
    val fade = remember { TailFade() }
    val tail = if (fadeEnabled && fadeChars > 0) fadeWidth(fadeMillis) else 0
    AndroidView(factory = { context ->
        TextView(context)
            .apply {
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1f)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }.also { view -> fade.view = view }
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
        val spannable = view.text as? Spannable
        fade.spannable = spannable
        if (spannable != null) {
            fade.draw(spannable, tail, flat = 0f)
            view.invalidate()
        }
    })
    // Let a burst settle to full opacity; a new burst restarts this and re-softens the tail.
    LaunchedEffect(rendered, tail, fadeEnabled, fadeMillis) {
        if (tail <= 0) return@LaunchedEffect
        val textView = fade.view ?: return@LaunchedEffect
        val started = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val elapsed = (frame - started) / 1_000_000f
            val progress = (elapsed / fadeMillis).coerceIn(0f, 1f)
            val spannable = fade.spannable ?: break
            fade.draw(spannable, tail, flat = FADE_EASING.transform(progress))
            textView.invalidate()
            if (progress >= 1f) break
        }
    }
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

@Preview(name = "Markdown search light")
@Preview(name = "Markdown search dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Markdown search large text", fontScale = 1.5f)
@Composable
private fun MarkdownSearchPreview() {
    HydraTheme {
        Surface {
            MarkdownBody(
                "# Clear headings\n\nBody text with `inline code`, **emphasis**, and consistent spacing.\n\n| Name | Description |\n|---|---|\n| `response_time` | A naturally wide cell |\n\n```kotlin\nval response = inspectProject()\n```",
                Modifier.padding(16.dp),
                highlight = ChatHighlight("response", 1),
            )
        }
    }
}
