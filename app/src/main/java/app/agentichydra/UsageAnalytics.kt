package app.agentichydra

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

private enum class UsageGroup(val title: String) { Day("Day"), Task("Task"), Model("Model") }

private sealed interface UsageState {
    data object Loading : UsageState
    data object Error : UsageState
    data class Ready(val rows: List<UsageRow>) : UsageState
}

private data class UsageBucket(
    val id: String,
    val title: String,
    val rows: List<UsageRow>,
    val prices: Map<UsageModel, TokenPrices>,
) {
    val input = rows.sumOf { it.input }
    val output = rows.sumOf { it.output }
    val unreported = rows.sumOf { it.unreported }
    val unpriced = rows.filter { it.model !in prices }.sumOf { it.input + it.output }
    val inputCost = rows.sumOf { prices[it.model]?.estimate(it.input, 0) ?: BigDecimal.ZERO }
    val outputCost = rows.sumOf { prices[it.model]?.estimate(0, it.output) ?: BigDecimal.ZERO }
    val hasPrices = rows.any { it.model in prices }

    fun total(spend: Boolean) = if (spend) (inputCost + outputCost).toDouble() else input.toDouble() + output
}

@Composable
internal fun UsageAnalyticsPage() {
    val context = LocalContext.current.applicationContext
    val ledger = remember(context) { UsageLedger(context) }
    var state by remember { mutableStateOf<UsageState>(UsageState.Loading) }
    var revision by remember { mutableStateOf(0) }
    var prices by remember { mutableStateOf<Map<UsageModel, TokenPrices>>(emptyMap()) }
    var editing by remember { mutableStateOf<UsageModel?>(null) }
    LaunchedEffect(ledger, revision) {
        state = UsageState.Loading
        try {
            val (rows, loadedPrices) = withContext(Dispatchers.IO) {
                try {
                    val rows = ledger.read()
                    rows to rows.map { it.model }.distinct().mapNotNull { model ->
                        ledger.prices(model)?.let { model to it }
                    }.toMap()
                } finally {
                    ledger.close()
                }
            }
            prices = loadedPrices
            state = UsageState.Ready(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = UsageState.Error
        }
    }
    when (val current = state) {
        UsageState.Loading -> UsageMessage { CircularProgressIndicator() }
        UsageState.Error -> UsageMessage {
            Text("Could not load usage.")
            TextButton(onClick = { revision++ }) { Text("Try again") }
        }
        is UsageState.Ready -> {
            if (current.rows.isEmpty()) {
                UsageEmptyState()
            } else {
                UsageChart(current.rows, prices, onPriceEdit = { editing = it })
            }
        }
    }
    editing?.let { model ->
        UsagePriceDialog(model, prices[model], onDismiss = { editing = null }) { price ->
            ledger.setPrices(model, price)
            prices = if (price == null) prices - model else prices + (model to price)
            editing = null
        }
    }
}

@Composable
private fun UsageEmptyState() {
    val colors = MaterialTheme.colorScheme
    UsageMessage {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.BarChart, null, Modifier.size(36.dp))
            }
        }
        Text(
            "No usage yet",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            "After your next model response, you can see token counts and estimated spend here.",
            modifier = Modifier.widthIn(max = 320.dp).padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UsageMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = { content() })
    }
}

@Composable
private fun UsageChart(
    rows: List<UsageRow>,
    prices: Map<UsageModel, TokenPrices>,
    onPriceEdit: (UsageModel) -> Unit,
) {
    var group by remember { mutableStateOf(UsageGroup.Day) }
    var provider by remember { mutableStateOf<String?>(null) }
    var spend by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<String?>(null) }
    val providers = remember(rows) { rows.map { it.model.provider }.distinct().sorted() }
    val buckets = remember(rows, provider, group, prices) {
        val filtered = rows.filter { provider == null || it.model.provider == provider }
        filtered.groupBy {
            when (group) {
                UsageGroup.Day -> it.day
                UsageGroup.Task -> it.taskId
                UsageGroup.Model -> "${it.model.provider}:${it.model.id}"
            }
        }.map { (key, bucket) ->
            val label = when (group) {
                UsageGroup.Day -> key
                UsageGroup.Task -> bucket.first().task
                UsageGroup.Model -> bucket.first().model.label
            }
            UsageBucket(key, label, bucket, prices)
        }
    }
    val ordered = remember(buckets, group, spend) {
        if (group == UsageGroup.Day) buckets else buckets.sortedByDescending { it.total(spend) }
    }
    val totals = remember(buckets, prices) { UsageBucket("", "", buckets.flatMap { it.rows }, prices) }
    val selected = ordered.firstOrNull { it.id == selection }
    val maximum = ordered.maxOfOrNull { it.total(spend) }?.takeIf { it > 0 } ?: 1.0
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${compactTokenLabel(totals.input + totals.output)} total tokens",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "${compactTokenLabel(totals.input)} input",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.primary,
                    )
                    Text(
                        "${compactTokenLabel(totals.output)} output",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.tertiary,
                    )
                }
                Text(
                    if (totals.hasPrices) "Estimated spend ${money(totals.inputCost + totals.outputCost)}"
                    else "Set USD rates under Model to estimate spend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (totals.unreported > 0) {
                    Text(
                        "${compactTokenLabel(totals.unreported)} responses with incomplete token counts",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (totals.hasPrices && totals.unpriced > 0) {
                    Text(
                        "${compactTokenLabel(totals.unpriced)} tokens without a price",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UsageGroup.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = group == choice,
                        onClick = {
                            group = choice
                            selection = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, UsageGroup.entries.size),
                    ) { Text(choice.title) }
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = provider == null,
                    onClick = {
                        provider = null
                        selection = null
                    },
                    label = { Text("All providers") },
                )
                providers.forEach { name ->
                    val label = Provider.entries.firstOrNull { it.name == name }?.label ?: name
                    FilterChip(
                        selected = provider == name,
                        onClick = {
                            provider = name
                            selection = null
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!spend, { spend = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Tokens") }
                SegmentedButton(spend, { spend = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Estimated spend") }
            }
        }
        items(ordered.size, key = { ordered[it].id }) { index ->
            val bucket = ordered[index]
            val first = if (spend) bucket.inputCost.toDouble() else bucket.input.toDouble()
            val second = if (spend) bucket.outputCost.toDouble() else bucket.output.toDouble()
            val metricLabel =
                if (!spend) "${compactTokenLabel(bucket.input + bucket.output)} tokens"
                else if (bucket.hasPrices) money(bucket.inputCost + bucket.outputCost)
                else "No rate"
            Surface(
                color = cs.surfaceContainerHigh,
                shape = groupedRowShape(index, ordered.size),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .clickable { selection = bucket.id },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            bucket.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            metricLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = cs.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    UsageBar((first / maximum).toFloat(), (second / maximum).toFloat(), false)
                    if (group == UsageGroup.Model) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { onPriceEdit(bucket.rows.first().model) }) {
                                Text("Set rates")
                            }
                        }
                    }
                }
            }
        }
    }
    if (selected != null) {
        UsageBucketDialog(
            bucket = selected,
            showRatesAction = group == UsageGroup.Model,
            onSetRates = {
                selection = null
                onPriceEdit(selected.rows.first().model)
            },
            onDismiss = { selection = null },
        )
    }
}

@Composable
private fun UsageBucketDialog(
    bucket: UsageBucket,
    showRatesAction: Boolean,
    onSetRates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                bucket.title,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageDetailRow("Total tokens", tokenLabel(bucket.input + bucket.output))
                UsageDetailRow("Input", tokenLabel(bucket.input), cs.primary)
                UsageDetailRow("Output", tokenLabel(bucket.output), cs.tertiary)
                if (bucket.hasPrices) {
                    HorizontalDivider(color = cs.outlineVariant)
                    UsageDetailRow("Estimated spend", money(bucket.inputCost + bucket.outputCost))
                    UsageDetailRow("Input cost", money(bucket.inputCost))
                    UsageDetailRow("Output cost", money(bucket.outputCost))
                }
                if (bucket.unreported > 0) {
                    Text(
                        "${tokenLabel(bucket.unreported)} responses with incomplete counts",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (bucket.unpriced > 0) {
                    Text(
                        "${tokenLabel(bucket.unpriced)} tokens without a price",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (showRatesAction) {
            { TextButton(onClick = onSetRates) { Text("Set rates") } }
        } else {
            null
        },
    )
}

@Composable
private fun UsageDetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

@Composable
private fun UsageBar(input: Float, output: Float, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(if (selected) 12.dp else 8.dp).clearAndSetSemantics {}) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(colors.surfaceContainerHighest, cornerRadius = radius)
        val inputWidth = size.width * input.coerceIn(0f, 1f)
        val outputWidth = (size.width * output).coerceIn(0f, size.width - inputWidth)
        if (inputWidth > 0f) {
            drawRoundRect(colors.primary, size = Size(inputWidth, size.height), cornerRadius = radius)
        }
        if (outputWidth > 0f) {
            drawRoundRect(colors.tertiary, topLeft = Offset(inputWidth, 0f), size = Size(outputWidth, size.height), cornerRadius = radius)
        }
    }
}

@Composable
private fun UsagePriceDialog(
    model: UsageModel,
    rates: TokenPrices?,
    onDismiss: () -> Unit,
    onSave: (TokenPrices?) -> Unit,
) {
    var input by remember(model) { mutableStateOf(rates?.input?.toPlainString().orEmpty()) }
    var output by remember(model) { mutableStateOf(rates?.output?.toPlainString().orEmpty()) }
    val parsedInput = input.rateOrNull()
    val parsedOutput = output.rateOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token rates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.label)
                Text("Enter USD per million tokens. Changes update estimates for past usage.")
                OutlinedTextField(
                    input, { input = it.take(20) }, label = { Text("Input rate") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = input.isNotBlank() && parsedInput == null,
                )
                OutlinedTextField(
                    output, { output = it.take(20) }, label = { Text("Output rate") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = output.isNotBlank() && parsedOutput == null,
                )
                if (rates != null) TextButton(onClick = { onSave(null) }) { Text("Clear rates") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedInput != null && parsedOutput != null,
                onClick = {
                    if (parsedInput != null && parsedOutput != null) onSave(TokenPrices(parsedInput, parsedOutput))
                },
            ) { Text("Save rates") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun String.rateOrNull(): BigDecimal? =
    trim().replace(',', '.').takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]{1,8})?")) }
        ?.toBigDecimalOrNull()

private fun tokenLabel(count: Long): String = NumberFormat.getIntegerInstance().format(count)

private fun compactTokenLabel(count: Long): String {
    if (count < 1_000L) return NumberFormat.getIntegerInstance().format(count)
    if (count < 1_000_000L) {
        val k = count / 1_000.0
        val text = if (k >= 100.0) String.format(Locale.US, "%.0f", k) else String.format(Locale.US, "%.1f", k).removeSuffix(".0")
        return "${text}k"
    }
    val m = count / 1_000_000.0
    val text = if (m >= 100.0) String.format(Locale.US, "%.0f", m) else String.format(Locale.US, "%.1f", m).removeSuffix(".0")
    return "${text}M"
}

private fun money(cost: BigDecimal): String =
    "USD ${cost.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}"

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(fontScale = 1.5f)
@Composable
private fun UsageChartPreview() {
    HydraTheme {
        val model = UsageModel(Provider.CUSTOM.name, "example-model")
        UsageChart(
            listOf(UsageRow("2026-11-24", "example", "Review notes", model, 4000, 1200, 0)),
            mapOf(model to TokenPrices(BigDecimal("1.25"), BigDecimal("5"))),
            onPriceEdit = {},
        )
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(fontScale = 1.5f)
@Composable
private fun UsageEmptyPreview() {
    HydraTheme { UsageEmptyState() }
}
