package app.agentichydra

import android.content.res.Configuration
import android.animation.ValueAnimator
import android.os.SystemClock
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val ResponseFadeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    highlight: ChatHighlight? = null,
    streaming: Boolean = false,
    streamChunkStarts: List<Int> = emptyList(),
) {
    val sections = remember(text) { MarkdownSections.parse(text) }
    val animate = LocalResponseFade.current && ValueAnimator.areAnimatorsEnabled()
    val bufferStart = if (animate && streaming) streamChunkStarts.firstOrNull() else null
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
                                val buttonColor by animateColorAsState(
                                    if (copied) cs.successContainer else Color.Transparent,
                                    animationSpec = spring(stiffness = 600f),
                                    label = "Copy button background",
                                )
                                val iconColor by animateColorAsState(
                                    if (copied) cs.onSuccessContainer else cs.primary,
                                    animationSpec = spring(stiffness = 600f),
                                    label = "Copy button foreground",
                                )
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(section.text))
                                        copied = true
                                    },
                                    modifier = Modifier.animateContentSize(
                                        animationSpec = spring(stiffness = 600f),
                                        alignment = Alignment.CenterEnd,
                                    ),
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = buttonColor,
                                        contentColor = iconColor,
                                    ),
                                ) {
                                    Icon(
                                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                        null,
                                        Modifier.size(16.dp),
                                    )
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
                    val live = streaming && sectionIndex == sections.lastIndex
                    val revealThrough = remember(bufferStart, text, section.text, rendered, renderer, live) {
                        val sectionStart = if (live && bufferStart != null) text.lastIndexOf(section.text) else -1
                        val offset = if (sectionStart >= 0 && bufferStart != null) bufferStart - sectionStart else section.text.length
                        when {
                            offset <= 0 -> 0
                            offset >= section.text.length -> rendered.length
                            else -> renderer.render(section.text.take(offset).trim('\n', '\r'))
                                .toString().commonPrefixWith(rendered.toString()).length
                        }
                    }
                    MarkdownText(
                        rendered,
                        renderer,
                        Modifier.fillMaxWidth(),
                        revealThrough = revealThrough,
                        streaming = live,
                        animate = animate,
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

/** Draw-only spans keep unrevealed glyphs in the measured text layout. */
internal class FadeSpan(var alpha: Float = 0f) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.alpha = (tp.alpha * alpha).roundToInt()
        if (tp.bgColor != 0) {
            val backgroundAlpha = (android.graphics.Color.alpha(tp.bgColor) * alpha).roundToInt()
            tp.bgColor = (tp.bgColor and 0x00ffffff) or (backgroundAlpha shl 24)
        }
    }
}

internal class ResponseReveal {
    private class Range(
        val start: Int,
        var end: Int,
        val releaseAt: Long,
        var started: Long? = null,
        val span: FadeSpan = FadeSpan(),
    )

    private var owner: Spannable? = null
    private var previous = ""
    private var wasStreaming = false
    private var lastCutoff = -1
    private val ranges = mutableListOf<Range>()

    fun update(text: Spannable, streaming: Boolean, cutoff: Int, animate: Boolean, now: Long, millis: Int) {
        val current = text.toString()
        if (!animate || (!streaming && !wasStreaming && ranges.isEmpty())) {
            clear()
            previous = current
            wasStreaming = false
            return
        }
        if (owner === text && current == previous && streaming == wasStreaming && cutoff == lastCutoff) return

        val overlap = previous.commonPrefixWith(current).length
        val wasVisibleThrough = ranges.firstOrNull { it.started == null }?.start ?: previous.length
        owner?.let { old -> ranges.forEach { old.removeSpan(it.span) } }
        owner = text
        ranges.removeAll { it.start >= overlap }
        ranges.lastOrNull()?.let { it.end = minOf(it.end, overlap) }

        // Markdown can rewrite a rendered paragraph when a closing marker arrives.
        val newStart = maxOf(overlap, minOf(wasVisibleThrough, text.length))
        if (newStart < text.length) {
            ranges += Range(newStart, text.length, now + millis.coerceAtMost(90))
        }
        // Start the first visible answer immediately after the thinking state.
        val revealThrough = if (streaming && previous.isNotEmpty()) safeBoundary(text, cutoff) else text.length
        val index = ranges.indexOfFirst { it.started == null && revealThrough > it.start && revealThrough < it.end }
        if (index >= 0) {
            val pending = ranges[index]
            ranges.add(index + 1, Range(revealThrough, pending.end, pending.releaseAt))
            pending.end = revealThrough
        }
        ranges.forEach { range ->
            if (range.end <= revealThrough && range.started == null) range.started = now
            text.setSpan(range.span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        previous = current
        wasStreaming = streaming
        lastCutoff = cutoff
        tick(now, millis)
    }

    fun tick(now: Long, millis: Int): Boolean {
        val text = owner ?: return false
        ranges.removeAll { range ->
            if (range.started == null && now >= range.releaseAt) range.started = range.releaseAt
            val started = range.started ?: return@removeAll false
            val progress = ((now - started).toFloat() / millis.coerceAtLeast(1)).coerceIn(0f, 1f)
            val alpha = ResponseFadeEasing.transform(progress)
            if (progress >= 1f) {
                text.removeSpan(range.span)
            } else if (alpha != range.span.alpha) {
                text.removeSpan(range.span)
                range.span.alpha = alpha
                text.setSpan(range.span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            progress >= 1f
        }
        return ranges.isNotEmpty()
    }

    private fun clear() {
        owner?.let { text -> ranges.forEach { text.removeSpan(it.span) } }
        ranges.clear()
        owner = null
        lastCutoff = -1
    }

    private fun safeBoundary(text: Spannable, cutoff: Int): Int {
        val offset = cutoff.coerceIn(0, text.length)
        if (offset == 0 || offset == text.length) return offset
        val graphemes = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(text.toString()) }
        val boundary = if (graphemes.isBoundary(offset)) offset else graphemes.preceding(offset).coerceAtLeast(0)
        return text.getSpans(boundary, boundary, android.text.style.ReplacementSpan::class.java)
            .filter { text.getSpanStart(it) < boundary && text.getSpanEnd(it) > boundary }
            .map(text::getSpanStart)
            .fold(boundary, ::minOf)
    }
}

/** Drives the fade after AndroidView has installed the spans, including when the stream pauses. */
internal class StreamingMarkdownView(context: android.content.Context) : TextView(context) {
    private val reveal = ResponseReveal()
    private var fadeMillis = 0
    private val revealFrame = object : Runnable {
        override fun run() {
            val pending = reveal.tick(SystemClock.uptimeMillis(), fadeMillis)
            invalidate()
            if (pending && isAttachedToWindow) postOnAnimation(this)
        }
    }

    fun updateReveal(streaming: Boolean, cutoff: Int, animate: Boolean, millis: Int) {
        fadeMillis = millis
        (text as? Spannable)?.let {
            reveal.update(it, streaming, cutoff, animate, SystemClock.uptimeMillis(), fadeMillis)
        }
        removeCallbacks(revealFrame)
        revealFrame.run()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        revealFrame.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revealFrame)
        super.onDetachedFromWindow()
    }
}

@Composable
private fun MarkdownText(
    rendered: Spanned,
    renderer: MarkdownRenderer,
    modifier: Modifier = Modifier,
    color: Int = renderer.foreground,
    revealThrough: Int = rendered.length,
    streaming: Boolean = false,
    animate: Boolean = false,
) {
    val fadeMillis = LocalResponseFadeMillis.current
    AndroidView(factory = { context ->
        StreamingMarkdownView(context).apply {
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
        view.updateReveal(streaming, revealThrough, animate, fadeMillis)
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
