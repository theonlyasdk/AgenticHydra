package app.agentichydra

import android.content.res.Configuration
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Message that holds at least one search match. [itemIndex] is its position in the timeline list
 * and [offset] is where the match starts inside the message text.
 */
internal data class ChatMatch(
    val messageId: String,
    val itemIndex: Int,
    val offset: Int,
)

/** Where a message's matches sit inside the timeline-wide match list. */
internal data class MessageMatchGroup(val first: Int, val count: Int)

/**
 * Search query painted inside one message. [active] is the ordinal of the current match within
 * that message, or -1 when the message does not hold it.
 */
data class ChatHighlight(val query: String, val active: Int = -1)

internal val searchableMessageRoles = setOf("user", "assistant")

internal fun chatMatches(
    messages: List<Message>,
    query: String,
): List<ChatMatch> {
    if (query.isBlank()) return emptyList()
    val result = mutableListOf<ChatMatch>()
    messages.forEachIndexed { index, message ->
        if (message.role in searchableMessageRoles && message.text.isNotBlank()) {
            occurrenceRanges(message.text, query).forEach { range ->
                result += ChatMatch(message.id, index, range.first)
            }
        }
    }
    return result
}

internal fun messageMatchGroups(matches: List<ChatMatch>): Map<String, MessageMatchGroup> {
    val groups = LinkedHashMap<String, MessageMatchGroup>()
    matches.forEachIndexed { ordinal, match ->
        val group = groups[match.messageId]
        groups[match.messageId] = if (group == null) MessageMatchGroup(ordinal, 1) else group.copy(count = group.count + 1)
    }
    return groups
}

internal fun occurrenceRanges(
    text: String,
    query: String,
): List<IntRange> {
    if (query.isBlank() || text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from + query.length <= text.length) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        ranges += at until at + query.length
        from = at + query.length
    }
    return ranges
}

/** Cumulative match count in each section, so the active ordinal can be placed in one section. */
internal fun sectionMatchStarts(
    sections: List<MarkdownSection>,
    query: String,
): List<Int> {
    val starts = ArrayList<Int>(sections.size)
    var running = 0
    sections.forEach { section ->
        starts += running
        running += sectionOccurrences(section, query)
    }
    return starts
}

internal fun sectionOccurrences(
    section: MarkdownSection,
    query: String,
): Int =
    when {
        section.table != null -> section.table.sumOf { row -> row.sumOf { occurrenceRanges(it, query).size } }
        else -> occurrenceRanges(section.text, query).size
    }

/** Active ordinal relative to one section, or -1 when the active match lives elsewhere. */
internal fun sectionActive(
    highlight: ChatHighlight?,
    starts: List<Int>,
    index: Int,
): Int {
    if (highlight == null) return -1
    val local = highlight.active - starts.getOrElse(index) { -1 }
    return if (local >= 0) local else -1
}

internal data class MatchPalette(
    val matchBackground: Color,
    val matchText: Color,
    val activeBackground: Color,
    val activeText: Color,
) {
    fun background(active: Boolean) = if (active) activeBackground else matchBackground

    fun text(active: Boolean) = if (active) activeText else matchText
}

@Composable
internal fun rememberMatchPalette(): MatchPalette {
    val cs = MaterialTheme.colorScheme
    return MatchPalette(cs.secondaryContainer, cs.onSecondaryContainer, cs.primary, cs.onPrimary)
}

/** Paints every occurrence of the query, with [active] emphasised. */
internal fun highlightedText(
    text: String,
    query: String,
    active: Int,
    palette: MatchPalette,
): AnnotatedString {
    val ranges = occurrenceRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEachIndexed { index, range ->
            append(text, cursor, range.first)
            withStyle(SpanStyle(background = palette.background(index == active), color = palette.text(index == active))) {
                append(text, range.first, range.last + 1)
            }
            cursor = range.last + 1
        }
        append(text, cursor, text.length)
    }
}

@Composable
internal fun searchHighlighted(
    text: String,
    highlight: ChatHighlight?,
): AnnotatedString {
    val palette = rememberMatchPalette()
    return remember(text, highlight, palette) {
        highlightedText(text, highlight?.query.orEmpty(), highlight?.active ?: -1, palette)
    }
}

/** Paints matches found in the rendered text, keeping Markwon's own spans intact. */
internal fun highlightSpans(
    rendered: Spanned,
    query: String,
    active: Int,
    palette: MatchPalette,
): Spanned {
    val ranges = occurrenceRanges(rendered.toString(), query)
    if (ranges.isEmpty()) return rendered
    return SpannableStringBuilder(rendered).apply {
        ranges.forEachIndexed { index, range ->
            val current = index == active
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            setSpan(BackgroundColorSpan(palette.background(current).toArgb()), range.first, range.last + 1, flags)
            setSpan(ForegroundColorSpan(palette.text(current).toArgb()), range.first, range.last + 1, flags)
        }
    }
}

@Composable
internal fun ChatFindBar(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    activeIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        withFrameNanos { }
        requester.requestFocus()
    }
    Surface(
        color = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier.testTag("chat-find-bar"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("Search...", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { onNext() }),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(requester),
                )
                IconButton(onClick = onPrevious, enabled = matchCount > 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Previous match")
                }
                IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Next match")
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, "Close search", tint = cs.onSurfaceVariant)
                }
            }
            AnimatedVisibility(
                visible = query.isNotBlank(),
                modifier = Modifier.clipToBounds(),
                enter = slideInVertically(spring(stiffness = 600f)) { -it } +
                    expandVertically(spring(stiffness = 600f), expandFrom = Alignment.Top) +
                    fadeIn(spring(stiffness = 600f)),
                exit = slideOutVertically(spring(stiffness = 600f)) { -it } + fadeOut(spring(stiffness = 600f)),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.surfaceContainerHighest,
                ) {
                    Text(
                        if (matchCount == 0) "No matches" else "${activeIndex + 1} of $matchCount matches",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Preview(name = "Find bar light")
@Preview(name = "Find bar dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Find bar large text", fontScale = 1.5f)
@Composable
private fun ChatFindBarPreview() {
    HydraTheme {
        Surface {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatFindBar("response", {}, 12, 2, {}, {}, {}, Modifier.fillMaxWidth())
                ChatFindBar("nothing", {}, 0, -1, {}, {}, {}, Modifier.fillMaxWidth())
            }
        }
    }
}
