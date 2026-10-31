package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** App-bar ring: a glanceable share of the context window, opening the context sheet. */
@Composable
internal fun ContextRingButton(
    usage: ContextUsage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Context ${usage.percent} percent used" },
        contentAlignment = Alignment.Center,
    ) {
        ContextRing(usage, Modifier.size(26.dp), 3.dp)
    }
}

@Composable
private fun ContextRing(
    usage: ContextUsage,
    modifier: Modifier,
    strokeWidth: Dp,
) {
    val cs = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(
        targetValue = usage.fraction,
        animationSpec = spring(dampingRatio = .8f, stiffness = 300f),
        label = "Context usage",
    )
    CircularProgressIndicator(
        progress = { fraction },
        modifier = modifier,
        color = if (fraction >= .9f) cs.tertiary else cs.primary,
        strokeWidth = strokeWidth,
        trackColor = cs.surfaceContainerHighest,
    )
}

@Composable
internal fun ContextSheet(
    usage: ContextUsage,
    onBudgetChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    HydraBottomSheet(onDismiss = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) { _ ->
        ContextSheetContent(usage, ContextBudget.tokens, onBudgetChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextSheetContent(
    usage: ContextUsage,
    selectedTokens: Int,
    onBudgetChange: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SheetHeader(Icons.Outlined.DonutLarge, "Context")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                ContextRing(usage, Modifier.fillMaxSize(), 8.dp)
                Text("${usage.percent}%", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "About ${formatContextTokens(usage.usedChars)} of " +
                        "${formatContextTokens(usage.budgetChars.toLong())} tokens",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    contextState(usage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Surface(color = cs.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Composition", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                ContextCompositionBar(usage)
                ContextCompositionRow("Conversation", usage.sentChars, cs.primary)
                ContextCompositionRow("Tools", usage.toolChars, cs.secondary)
                ContextCompositionRow("System prompt", usage.systemChars, cs.tertiary)
                ContextCompositionRow("Free", usage.freeChars, cs.surfaceContainerHighest)
            }
        }
        Surface(color = cs.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContextDetail(
                    "Messages sent",
                    "${usage.keptMessages} of ${usage.totalMessages}",
                )
                ContextDetail(
                    "Older turns",
                    if (usage.trimmed) "Trimmed automatically" else "All included",
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Context window", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CONTEXT_WINDOW_OPTIONS.forEach { tokens ->
                    FilterChip(
                        selected = selectedTokens == tokens,
                        onClick = { onBudgetChange(tokens) },
                        label = { Text("${tokens / 1000}k") },
                    )
                }
            }
            Text(
                "Hydra reserves room for the system prompt and tool definitions, then sends the newest complete " +
                    "turns that fit, so tool calls always arrive with their results.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContextDetail(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** Stacked bar showing how the window splits between the request parts and free space. */
@Composable
private fun ContextCompositionBar(usage: ContextUsage) {
    val cs = MaterialTheme.colorScheme
    val slices =
        listOf(
            usage.sentChars to cs.primary,
            usage.toolChars to cs.secondary,
            usage.systemChars to cs.tertiary,
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHighest)
            .semantics { contentDescription = "Context composition" },
    ) {
        slices.forEach { (chars, color) ->
            if (chars > 0) {
                Box(Modifier.fillMaxHeight().weight(chars.toFloat()).background(color))
            }
        }
        if (usage.freeChars > 0) {
            Box(Modifier.fillMaxHeight().weight(usage.freeChars.toFloat()))
        }
    }
}

@Composable
private fun ContextCompositionRow(
    label: String,
    chars: Long,
    color: Color,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Text("≈ ${formatContextTokens(chars)} tokens", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

private fun contextState(usage: ContextUsage): String =
    when {
        usage.overBudget -> "This request is past the window; the newest turn is still sent whole."
        usage.trimmed -> "Older turns are left out so every request fits."
        else -> "Every saved turn fits in the window."
    }

internal fun formatContextTokens(chars: Long): String {
    val tokens = chars / 4.0
    if (tokens < 1_000) return tokens.roundToInt().toLong().toString()
    val thousands = tokens / 1_000
    return if (abs(thousands - thousands.roundToInt()) < .05) {
        "${thousands.roundToInt()}k"
    } else {
        String.format(Locale.getDefault(), "%.1fk", thousands)
    }
}

@Preview(name = "Context light")
@Preview(name = "Context dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Context large text", fontScale = 1.5f)
@Composable
private fun ContextSheetContentPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ContextSheetContent(
                usage =
                    ContextUsage(
                        sentChars = 26_000,
                        totalChars = 41_000,
                        systemChars = 6_000,
                        toolChars = 9_000,
                        budgetChars = 64_000,
                        keptMessages = 9,
                        totalMessages = 14,
                    ),
                selectedTokens = 16_000,
                onBudgetChange = {},
            )
        }
    }
}
