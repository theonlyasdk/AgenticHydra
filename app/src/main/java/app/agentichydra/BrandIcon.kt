package app.agentichydra

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/** Bundled mark for the provider itself; local and custom endpoints keep Hydra's trident. */
private fun providerMark(provider: Provider): Int? =
    when (provider) {
        Provider.NIM -> R.drawable.brand_nvidia
        Provider.GEMINI -> R.drawable.brand_googlegemini
        Provider.GROQ -> R.drawable.brand_groq
        Provider.CEREBRAS -> R.drawable.brand_cerebras
        Provider.OPENROUTER -> R.drawable.brand_openrouter
        Provider.MISTRAL -> R.drawable.brand_mistral
        Provider.TOGETHER -> R.drawable.brand_together
        Provider.FIREWORKS -> R.drawable.brand_fireworks
        Provider.DEEPSEEK -> R.drawable.brand_deepseek
        Provider.OLLAMA -> R.drawable.brand_ollama
        Provider.LLAMACPP, Provider.CUSTOM -> null
    }

@Composable
fun ProviderIcon(
    provider: Provider,
    modifier: Modifier = Modifier,
) {
    val resource = providerMark(provider)
    if (resource != null) {
        Icon(painterResource(resource), null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        HydraMark(modifier)
    }
}

/** Bundled brand artwork; never fetched from a third party while chatting. */
@Composable
fun BrandIcon(
    modelId: String,
    provider: Provider,
    modifier: Modifier = Modifier,
) {
    val id = modelId.lowercase()
    val resource =
        when {
            id.startsWith("openai/") || id.contains("gpt-") -> R.drawable.brand_openai
            id.startsWith("meta/") || id.contains("llama") -> R.drawable.brand_meta
            id.contains("gemini") -> R.drawable.brand_googlegemini
            id.contains("claude") || id.startsWith("anthropic/") -> R.drawable.brand_anthropic
            id.contains("qwen") || id.startsWith("alibaba") -> R.drawable.brand_alibabacloud
            id.contains("mistral") || id.contains("mixtral") || id.contains("codestral") -> R.drawable.brand_mistral
            id.contains("deepseek") -> R.drawable.brand_deepseek
            else -> providerMark(provider)
        }
    if (resource != null) {
        Icon(painterResource(resource), null, modifier, tint = MaterialTheme.colorScheme.onSurface)
    } else {
        HydraMark(modifier) // Unknown/custom brands retain Hydra's identity, not a fabricated logo.
    }
}
