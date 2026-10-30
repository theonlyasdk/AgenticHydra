package app.agentichydra

import android.content.Context
import org.json.JSONObject

internal data class WorkRunOutcome(
    val status: String,
    val error: String,
)

/** Immutable run configuration; credentials never enter schedule records. */
internal data class WorkConnection(
    val provider: Provider,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val selectedModel: ModelSpec,
    val effort: String,
) {
    val reasoning get() = ReasoningPolicy.profile(provider, selectedModel)

    fun saved() =
        JSONObject()
            .put("provider", provider.name)
            .put("endpoint", endpoint)
            .put("model", selectedModel.json())
            .put("effort", effort)

    companion object {
        fun from(chat: HydraModel) = WorkConnection(chat.provider, chat.endpoint, chat.model, chat.apiKey, chat.selectedModel, chat.effort)

        fun restore(
            context: Context,
            saved: JSONObject,
        ): WorkConnection {
            val provider = Provider.valueOf(saved.getString("provider"))
            val endpoint = saved.getString("endpoint")
            val prefs = context.getSharedPreferences("hydra", Context.MODE_PRIVATE)
            val current =
                prefs.getString(
                    "endpoint_${provider.name}",
                    if (prefs.getString("provider", "") == provider.name) {
                        prefs.getString("endpoint", provider.base)
                    } else {
                        provider.base
                    },
                )
            check(current?.trimEnd('/') == endpoint.trimEnd('/')) {
                "The provider endpoint changed. Update this schedule using the current connection."
            }
            val model = saved.getJSONObject("model")
            val spec =
                ModelSpec(
                    model.getString("id"),
                    model.getString("name"),
                    model.optString("owned_by"),
                    if (model.has("thinking")) model.getBoolean("thinking") else null,
                )
            return WorkConnection(
                provider,
                endpoint,
                spec.id,
                KeyVault(context).read(provider.name),
                spec,
                saved.optString("effort", "auto"),
            )
        }
    }
}
