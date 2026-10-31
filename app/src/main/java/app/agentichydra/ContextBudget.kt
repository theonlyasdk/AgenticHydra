package app.agentichydra

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

internal val CONTEXT_WINDOW_OPTIONS = listOf(8_000, 16_000, 32_000, 64_000, 128_000)

/**
 * App-wide context window, estimated in tokens. All engines read the same value so the ring,
 * the sheet, and the request wire never disagree.
 */
internal object ContextBudget {
    private const val PREFS = "context"
    private const val KEY = "tokens"
    private var loaded = false

    var tokens by mutableIntStateOf(DEFAULT_CONTEXT_TOKENS)
        private set

    val chars get() = tokens * 4

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val stored =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY, DEFAULT_CONTEXT_TOKENS)
        tokens = stored.takeIf { it in CONTEXT_WINDOW_OPTIONS } ?: DEFAULT_CONTEXT_TOKENS
    }

    fun choose(
        context: Context,
        value: Int,
    ) {
        if (value !in CONTEXT_WINDOW_OPTIONS) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, value)
            .apply()
        tokens = value
    }
}
