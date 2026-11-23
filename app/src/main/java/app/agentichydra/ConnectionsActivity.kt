package app.agentichydra

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

private const val EXTRA_PROVIDER = "provider"

internal fun openConnections(context: Context) {
    context.startActivity(Intent(context, ConnectionsActivity::class.java))
}

class ConnectionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as HydraApplication).models[HydraModel::class.java]
        setContent {
            HydraTheme {
                ConnectionsPage(
                    model.enabledProviders,
                    model.configuredProviders,
                    onBack = ::finish,
                    onToggle = model::setProviderEnabled,
                    onOpen = { provider ->
                        startActivity(
                            Intent(this, ProviderConnectionActivity::class.java).putExtra(EXTRA_PROVIDER, provider.name),
                        )
                    },
                )
            }
        }
    }
}

class ProviderConnectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        val provider = Provider.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PROVIDER) }
        if (provider == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val model = (application as HydraApplication).models[HydraModel::class.java]
        setContent { HydraTheme { ProviderConnectionEditor(provider, model, ::finish) } }
    }
}

@Composable
private fun ConnectionsPage(
    enabled: Set<Provider>,
    configured: List<Provider>,
    onBack: () -> Unit,
    onToggle: (Provider, Boolean) -> Unit,
    onOpen: (Provider) -> Unit,
) {
    ConnectionPage("Connections", onBack, spacing = 4.dp) {
        val rowColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Provider.entries.forEachIndexed { index, provider ->
            Surface(color = rowColor, shape = groupedRowShape(index, Provider.entries.size)) {
                ListItem(
                    headlineContent = { Text(provider.label) },
                    supportingContent = { Text(if (provider in configured) "Configured" else "Set up connection") },
                    leadingContent = {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            ProviderIcon(provider, Modifier.size(24.dp), colored = provider in configured)
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = provider in enabled,
                            onCheckedChange = { onToggle(provider, it) },
                            modifier = Modifier.semantics { contentDescription = "Show ${provider.label} in Models" },
                        )
                    },
                    modifier = Modifier.clickable(onClickLabel = "Configure ${provider.label}") { onOpen(provider) },
                    colors = ListItemDefaults.colors(containerColor = rowColor),
                )
            }
        }
    }
}

private data class ConnectionForm(
    val endpoint: String,
    val key: String = "",
    val modelId: String = "",
    val showKey: Boolean = false,
    val check: ConnectionCheckUiState = ConnectionCheckUiState(),
)

@Composable
private fun ProviderConnectionEditor(provider: Provider, vm: HydraModel, onBack: () -> Unit) {
    val savedEndpoint = vm.savedEndpoint(provider)
    val savedModel = vm.savedModel(provider)
    val savedKey = vm.savedKey(provider)
    val savedManualModel = savedModel.takeIf { vm.manualSelection(provider, it) }.orEmpty()
    var form by remember {
        mutableStateOf(
            ConnectionForm(savedEndpoint, savedKey, savedManualModel),
        )
    }
    val validator = remember { ConnectionValidator() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var checkJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(validator) {
        onDispose {
            checkJob?.cancel()
            validator.cancel()
        }
    }
    val context = LocalContext.current
    val prompt = rememberExternalIntentPrompt()
    val snackbar = remember { SnackbarHostState() }
    val apiKeyPage = provider.apiKeyPage()
    val verifiedDraft = vm.isConnectionVerified(provider) &&
        form.key.trim() == savedKey && form.endpoint.trim().trimEnd('/') == savedEndpoint.trimEnd('/') &&
        form.modelId.trim() == savedManualModel
    ProviderConnectionPage(
        provider = provider,
        form = form,
        canSave = provider != vm.provider || !vm.busy,
        showVerifiedIcon = provider in vm.enabledProviders && verifiedDraft,
        snackbarHost = { SnackbarHost(snackbar) },
        onShowVerification = {
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                snackbar.showSnackbar(if (verifiedDraft) "Connection verified." else "Connection not verified yet.")
            }
        },
        onGetApiKey = apiKeyPage?.let { url -> { openExternally(context, url, prompt) } },
        onBack = onBack,
        onChange = { form = it.copy(check = ConnectionCheckUiState()) },
        onCancel = {
            checkJob?.cancel()
            validator.cancel()
            form = form.copy(check = ConnectionCheckUiState())
        },
        onSave = {
            val endpoint = form.endpoint.trim().trimEnd('/')
            val key = form.key.trim()
            val manualModel = form.modelId.trim()
            val preferredModel = manualModel.ifBlank {
                if (endpoint == savedEndpoint.trimEnd('/')) savedModel else ""
            }
            keyboard?.hide()
            form = form.copy(check = ConnectionCheckUiState(ConnectionCheckPhase.Checking), showKey = false)
            checkJob = scope.launch {
                try {
                    val verified = validator.verify(provider, endpoint, key, preferredModel)
                    ensureActive()
                    vm.saveConnection(provider, endpoint, verified.id, key, manualModel.isNotBlank())
                    form = form.copy(check = ConnectionCheckUiState(ConnectionCheckPhase.Verified, modelName = verified.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureActive()
                    val message = if (e is IOException || e is IllegalArgumentException) e.message else null
                    form = form.copy(check = ConnectionCheckUiState(message = message ?: "Could not verify the connection."))
                }
            }
        },
    )
    ExternalIntentHost(prompt)
}

private fun Provider.apiKeyPage(): String? =
    when (this) {
        Provider.NIM -> "https://build.nvidia.com/settings/api-keys"
        Provider.GEMINI -> "https://aistudio.google.com/api-keys"
        Provider.GROQ -> "https://console.groq.com/keys"
        Provider.CEREBRAS -> "https://cloud.cerebras.ai/platform"
        Provider.OPENROUTER -> "https://openrouter.ai/settings/keys"
        Provider.MISTRAL -> "https://console.mistral.ai/api-keys"
        Provider.TOGETHER -> "https://api.together.ai/settings/api-keys"
        Provider.FIREWORKS -> "https://app.fireworks.ai/settings/users/api-keys"
        Provider.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
        Provider.OLLAMA, Provider.LLAMACPP, Provider.CUSTOM -> null
    }

@Composable
private fun ProviderConnectionPage(
    provider: Provider,
    form: ConnectionForm,
    canSave: Boolean,
    showVerifiedIcon: Boolean,
    onBack: () -> Unit,
    onChange: (ConnectionForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    onShowVerification: () -> Unit = {},
    onGetApiKey: (() -> Unit)? = null,
) {
    ConnectionPage(provider.label, onBack, snackbarHost = snackbarHost) {
        when (form.check.phase) {
            ConnectionCheckPhase.Checking -> {
                ConnectionCheckStatus()
                FilledTonalButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel check")
                }
            }

            ConnectionCheckPhase.Verified -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.CheckCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Connection saved",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${form.check.modelName} accepted the check.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Done")
                }
                TextButton(onClick = { onChange(form) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit connection")
                }
            }

            ConnectionCheckPhase.Editing -> {
                Column(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onShowVerification, modifier = Modifier.size(72.dp)) {
                        Box(Modifier.semantics { contentDescription = "Connection verification status" }) {
                            ProviderIcon(provider, Modifier.size(64.dp), colored = showVerifiedIcon)
                        }
                    }
                    if (onGetApiKey != null) {
                        TextButton(onClick = onGetApiKey, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Get an API key")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = form.key,
                    onValueChange = { onChange(form.copy(key = it)) },
                    label = { Text(if (provider.keyRequired) "API key" else "API key (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (form.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onChange(form.copy(showKey = !form.showKey)) }) {
                            Icon(
                                if (form.showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (form.showKey) "Hide API key" else "Show API key",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = form.endpoint,
                    onValueChange = { onChange(form.copy(endpoint = it)) },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = form.modelId,
                    onValueChange = { onChange(form.copy(modelId = it)) },
                    label = { Text("Model ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                if (form.check.message.isNotBlank()) {
                    Text(form.check.message, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (form.check.message.isBlank()) "Check & save connection" else "Try again")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPage(
    title: String,
    onBack: () -> Unit,
    spacing: androidx.compose.ui.unit.Dp = 16.dp,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            SettingsPageColumn(spacing, content)
        }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(fontScale = 1.5f)
@Composable
private fun ConnectionsPreview() {
    HydraTheme { ConnectionsPage(setOf(Provider.NIM), listOf(Provider.NIM), {}, { _, _ -> }, {}) }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(fontScale = 1.5f)
@Composable
private fun ProviderConnectionPreview() {
    HydraTheme { ProviderConnectionPage(Provider.NIM, ConnectionForm(Provider.NIM.base), true, false, {}, {}, {}, {}) }
}
