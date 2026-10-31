package app.agentichydra

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class AdvancedModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HydraTheme { AdvancedModelPage { finish() } } }
    }
}

@Composable
internal fun AdvancedModelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberAdvancedModelSettings()
    AdvancedModelContent(
        settings = settings,
        onBack = onBack,
        onPreventChange = { setPreventRateLimits(context, it) },
        onResumeChange = { setResumeOnRateLimit(context, it) },
        onRequestsPerMinuteChange = { setRequestsPerMinute(context, it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedModelContent(
    settings: AdvancedModelSettings,
    onBack: () -> Unit,
    onPreventChange: (Boolean) -> Unit,
    onResumeChange: (Boolean) -> Unit,
    onRequestsPerMinuteChange: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Provider settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            SettingsPageColumn(spacing = 24.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToggleRow(
                        index = 0,
                        count = 2,
                        icon = Icons.Outlined.Speed,
                        title = "Prevent rate limits",
                        supporting = "Space requests out so the provider never has to say no",
                        checked = settings.preventRateLimits,
                        onCheckedChange = onPreventChange,
                    )
                    ToggleRow(
                        index = 1,
                        count = 2,
                        icon = Icons.Outlined.Autorenew,
                        title = "Resume after a rate limit",
                        supporting = "Read the provider's wait hint and continue on its own",
                        checked = settings.resumeOnRateLimit,
                        onCheckedChange = onResumeChange,
                    )
                }

                RequestsPerMinuteRow(settings.requestsPerMinute, onRequestsPerMinuteChange)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Published provider limits", style = MaterialTheme.typography.titleMedium)
                    if (settings.requestsPerMinute > 0) {
                        Text(
                            "Requests per minute is set to ${settings.requestsPerMinute}, so every provider " +
                                "below is paced at that budget instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.primary,
                        )
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PUBLISHED_LIMITS.forEachIndexed { index, (provider, limit) ->
                            LimitRow(
                                index = index,
                                count = PUBLISHED_LIMITS.size,
                                provider = provider,
                                title = provider.label,
                                value = limit?.let { "$it requests per minute" } ?: "Not paced",
                            )
                        }
                    }
                    Text(
                        "These are conservative starting points, not provider guarantees: paid tiers change the " +
                            "real limit and most providers also cap tokens per minute. NVIDIA NIM starts at 40 " +
                            "requests per minute, Gemini, Groq and Cerebras at 30, and the other hosted providers " +
                            "at 60. Ollama, llama.cpp and custom endpoints run on your own server, so they are " +
                            "never paced. Automatic resume still follows whatever wait the provider sends back " +
                            "when your real limit is lower than the number shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    index: Int,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = groupedRowShape(index, count),
        modifier =
            Modifier.fillMaxWidth().toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            leadingContent = { Icon(icon, null, tint = cs.primary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
        )
    }
}

private val REQUESTS_PER_MINUTE = listOf(0, 10, 20, 30, 40, 60, 120)

/** Every provider with the budget the pacer uses unless Requests per minute overrides it. */
private val PUBLISHED_LIMITS = Provider.entries.map { it to ProviderRateLimits.documented(it) }

@Composable
private fun RequestsPerMinuteRow(
    value: Int,
    onChange: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = { menu = true },
        color = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text("Requests per minute") },
            supportingContent = {
                Text(
                    if (value == 0) {
                        "Follow each provider's published limit"
                    } else {
                        "Budget $value requests per minute for every provider"
                    },
                )
            },
            leadingContent = { Icon(Icons.Outlined.Timer, null, tint = cs.primary) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (value == 0) "Auto" else "$value", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Box {
                        Icon(Icons.Outlined.ArrowDropDown, null)
                        HydraOverflowMenu(menu, { menu = false }, headerText = "Requests per minute") {
                            REQUESTS_PER_MINUTE.forEach { preset ->
                                HydraMenuItem(
                                    if (preset == 0) "Auto (provider default)" else "$preset per minute",
                                    Icons.Outlined.Timer,
                                    selected = preset == value,
                                ) {
                                    menu = false
                                    onChange(preset)
                                }
                            }
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerHigh),
        )
    }
}

@Composable
private fun LimitRow(
    index: Int,
    count: Int,
    provider: Provider,
    title: String,
    value: String,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = groupedRowShape(index, count)) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = { ProviderIcon(provider, Modifier.size(20.dp)) },
            trailingContent = {
                Text(value, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            },
            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
        )
    }
}

@Preview(name = "Provider settings light")
@Preview(name = "Provider settings dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Provider settings large text", fontScale = 1.5f)
@Composable
private fun AdvancedModelPreview() {
    HydraTheme {
        AdvancedModelContent(AdvancedModelSettings(), {}, {}, {}, {})
    }
}
