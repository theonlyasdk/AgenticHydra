package app.agentichydra

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** The active @-mention token in a composer draft, with the index of its leading '@'. */
internal data class MentionQuery(
    val query: String,
    val start: Int,
)

/**
 * Finds the mention being typed at the end of [draft]: the last '@' that begins a token
 * (start of text or after whitespace) whose tail has no whitespace or further '@'.
 */
internal fun mentionQuery(draft: String): MentionQuery? {
    val at = draft.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !draft[at - 1].isWhitespace()) return null
    val token = draft.substring(at + 1)
    if (token.any { it.isWhitespace() }) return null
    return MentionQuery(token, at)
}

/** Replaces the active @-mention token in [draft] with "@path " and returns the new draft. */
internal fun applyMention(
    draft: String,
    query: MentionQuery,
    path: String,
): String = draft.substring(0, query.start) + "@" + path + " " + draft.substring(query.start + 1 + query.query.length)

/**
 * Subsequence fuzzy score for [path] against [query]; null when the query is not a subsequence.
 * Rewards consecutive hits and matches at file-name or path-segment boundaries.
 */
internal fun fuzzyScore(
    path: String,
    query: String,
): Int? {
    if (query.isEmpty()) return 0
    val lowerPath = path.lowercase()
    val nameStart = lowerPath.lastIndexOf('/') + 1
    var score = 0
    var cursor = 0
    var previous = -2
    for (ch in query.lowercase()) {
        val found = lowerPath.indexOf(ch, cursor)
        if (found < 0) return null
        score += 1
        if (found == previous + 1) score += 6
        when {
            found == nameStart -> score += 10
            found == 0 || lowerPath[found - 1] == '/' -> score += 8
            lowerPath[found - 1] in "_-." -> score += 4
        }
        previous = found
        cursor = found + 1
    }
    return score - path.length / 8
}

/** Best fuzzy matches for [query], most relevant first; an empty query lists the first files. */
internal fun fuzzyFilter(
    paths: List<String>,
    query: String,
    limit: Int = 50,
): List<String> {
    if (query.isEmpty()) return paths.take(limit)
    return paths
        .mapNotNull { path -> fuzzyScore(path, query)?.let { it to path } }
        .sortedWith(
            compareByDescending<Pair<Int, String>> { it.first }
                .thenBy { it.second.length }
                .thenBy { it.second },
        ).take(limit)
        .map { it.second }
}

/** Fuzzy file picker shown above the composer while the user types an @-mention. */
@Composable
internal fun MentionPanel(
    paths: List<String>,
    query: String,
    loading: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val matches = remember(paths, query) { fuzzyFilter(paths, query) }
    Surface(
        color = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().heightIn(max = 280.dp),
    ) {
        when {
            matches.isEmpty() && loading -> PanelMessage("Indexing project files…")
            matches.isEmpty() -> PanelMessage("No matching files")
            else ->
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(matches, key = { it }) { path ->
                        val parent = path.substringBeforeLast('/', "")
                        ListItem(
                            headlineContent = {
                                Text(
                                    path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    parent.ifEmpty { "Project root" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Description, null, tint = cs.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onPick(path) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Mention panel light")
@Preview(name = "Mention panel dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Mention panel large text", fontScale = 1.5f)
@Composable
private fun MentionPanelPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MentionPanel(
                paths =
                    listOf(
                        "app/src/main/java/app/agentichydra/WorkModel.kt",
                        "app/src/main/java/app/agentichydra/WorkChatActivity.kt",
                        "app/src/main/java/app/agentichydra/Theme.kt",
                        "README.md",
                    ),
                query = "work",
                loading = false,
                onPick = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}