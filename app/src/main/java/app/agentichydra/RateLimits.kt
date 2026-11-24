package app.agentichydra

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import okhttp3.Response
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToLong

private const val ADVANCED_PREFS = "advanced_model"
private const val PREVENT_KEY = "prevent_rate_limits"
private const val RESUME_KEY = "resume_on_rate_limit"
private const val RPM_KEY = "requests_per_minute"

/** Conservative request budgets per minute per hosted provider; see Provider settings. */
internal object ProviderRateLimits {
    fun documented(provider: Provider): Int? =
        when (provider) {
            // Hosted NIM allows 40 requests per minute per API key.
            Provider.NIM -> 40
            // Free tiers of these hosted services sit around 30 requests per minute.
            Provider.GEMINI, Provider.GROQ, Provider.CEREBRAS -> 30
            // Paid plans vary by tier; 60 leaves a full minute of headroom at one request per second.
            Provider.OPENROUTER,
            Provider.MISTRAL,
            Provider.TOGETHER,
            Provider.FIREWORKS,
            Provider.DEEPSEEK -> 60
            // Local servers and user-run endpoints never rate limit us.
            Provider.OLLAMA, Provider.LLAMACPP, Provider.CUSTOM -> null
        }
}

internal data class AdvancedModelSettings(
    val preventRateLimits: Boolean = true,
    val resumeOnRateLimit: Boolean = true,
    /** 0 follows the provider default; anything else overrides it for every provider. */
    val requestsPerMinute: Int = 0,
) {
    fun limitFor(provider: Provider): Int? =
        requestsPerMinute.takeIf { it > 0 } ?: ProviderRateLimits.documented(provider)
}

private fun advancedPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(ADVANCED_PREFS, Context.MODE_PRIVATE)

internal fun advancedModelSettings(context: Context): AdvancedModelSettings {
    val prefs = advancedPrefs(context)
    return AdvancedModelSettings(
        preventRateLimits = prefs.getBoolean(PREVENT_KEY, true),
        resumeOnRateLimit = prefs.getBoolean(RESUME_KEY, true),
        requestsPerMinute = prefs.getInt(RPM_KEY, 0),
    )
}

internal fun setPreventRateLimits(
    context: Context,
    value: Boolean,
) = advancedPrefs(context).edit().putBoolean(PREVENT_KEY, value).apply()

internal fun setResumeOnRateLimit(
    context: Context,
    value: Boolean,
) = advancedPrefs(context).edit().putBoolean(RESUME_KEY, value).apply()

internal fun setRequestsPerMinute(
    context: Context,
    value: Int,
) = advancedPrefs(context).edit().putInt(RPM_KEY, value.coerceAtLeast(0)).apply()

@Composable
internal fun rememberAdvancedModelSettings(): AdvancedModelSettings {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(advancedModelSettings(context)) }
    DisposableEffect(context) {
        val prefs = advancedPrefs(context)
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                settings = advancedModelSettings(context)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

internal fun Context.isWorkPowerConstrained(): Boolean =
    getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

internal fun Context.isWorkNetworkMetered(): Boolean =
    getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

/** What one request path should do about provider rate limits. */
internal data class RateLimitPolicy(
    val prevent: Boolean = false,
    val resume: Boolean = false,
    val rpm: Int? = null,
    val maxRetries: Int = 3,
) {
    companion object {
        fun forProvider(
            context: Context,
            provider: Provider,
            background: Boolean = false,
        ): RateLimitPolicy {
            val settings = advancedModelSettings(context)
            val configured = settings.limitFor(provider)
            val constrained = context.isWorkPowerConstrained() || (background && context.isWorkNetworkMetered())
            return if (constrained) {
                RateLimitPolicy(true, settings.resumeOnRateLimit, minOf(configured ?: 12, 12), maxRetries = 1)
            } else {
                RateLimitPolicy(settings.preventRateLimits, settings.resumeOnRateLimit, configured)
            }
        }
    }
}

/** A 429 from a provider, carrying how long the provider asked us to wait. */
internal class ProviderRateLimitException(
    message: String,
    val retryAfterMillis: Long,
) : IOException(message)

internal fun rateLimitNotice(waitMillis: Long): String =
    "Rate limit reached. Resuming automatically in ${((waitMillis + 999) / 1000)}s…"

internal fun isRateLimitNotice(text: String): Boolean = text.startsWith("Rate limit reached.")

/**
 * Shared sliding-window pacer for every model request in the app: chat, task turns,
 * background runs, and conversation naming all draw from the same provider budget.
 */
internal object RequestPacer {
    private const val WINDOW_MILLIS = 60_000L
    private const val MAX_BLOCK_MILLIS = 300_000L
    private val lock = Any()
    private val sent = mutableMapOf<Provider, ArrayDeque<Long>>()
    private val blockedUntil = mutableMapOf<Provider, Long>()

    /** Suspends until sending one more request keeps the provider under [requestsPerMinute]. */
    suspend fun acquire(
        provider: Provider,
        requestsPerMinute: Int?,
    ) {
        val rpm = requestsPerMinute?.takeIf { it > 0 } ?: return
        // A little headroom: provider counters include requests from other devices on the same key.
        val usable = max(1, rpm - max(1, rpm / 20))
        val gap = WINDOW_MILLIS / rpm
        while (true) {
            val wait =
                synchronized(lock) {
                    val now = SystemClock.elapsedRealtime()
                    val history = sent.getOrPut(provider) { ArrayDeque() }
                    while (history.isNotEmpty() && now - history.first() >= WINDOW_MILLIS) history.removeFirst()
                    val blocked = ((blockedUntil[provider] ?: 0L) - now).coerceAtLeast(0L)
                    val gapWait = history.lastOrNull()?.let { (it + gap - now).coerceAtLeast(0L) } ?: 0L
                    val windowWait =
                        if (history.size >= usable) {
                            (WINDOW_MILLIS - (now - history.first())).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                    val ready = max(blocked, max(gapWait, windowWait))
                    if (ready <= 0L) history.addLast(now)
                    ready
                }
            if (wait <= 0L) return
            delay(wait + 20L)
        }
    }

    /** Remembers a 429 so every later request to this provider waits too. */
    fun noteRateLimited(
        provider: Provider,
        retryAfterMillis: Long,
    ) {
        val until = SystemClock.elapsedRealtime() + retryAfterMillis.coerceIn(1_000L, MAX_BLOCK_MILLIS)
        synchronized(lock) {
            blockedUntil[provider] = max(blockedUntil[provider] ?: 0L, until)
        }
    }

    /** Uses remaining-request headers to pause before the provider has to answer 429. */
    fun noteResponseHeaders(
        provider: Provider,
        remaining: String?,
        resetAfter: String?,
    ) {
        if (remaining?.trim()?.toIntOrNull() != 0) return
        val wait = resetAfter?.let(::parseRateLimitDuration)?.coerceIn(1_000L, MAX_BLOCK_MILLIS) ?: 15_000L
        noteRateLimited(provider, wait)
    }
}

/** Reads how long the provider wants us to wait before the next request. */
internal fun Response.rateLimitWaitMillis(provider: Provider): Long {
    val hinted =
        sequenceOf(
            header("Retry-After")?.let(::parseRetryAfter),
            header("x-ratelimit-reset-requests")?.let(::parseRateLimitDuration),
            runCatching { retryHintFromBody(peekBody(16_384L).string()) }.getOrNull(),
        ).filterNotNull().firstOrNull()
    return (hinted ?: defaultRateLimitWait(provider)).coerceIn(2_000L, 300_000L)
}

internal fun defaultRateLimitWait(provider: Provider): Long =
    when (provider) {
        Provider.NIM -> 20_000L
        else -> 30_000L
    }

private fun parseRetryAfter(value: String): Long? {
    val seconds = value.trim().toDoubleOrNull() ?: return null
    return (seconds * 1000).roundToLong()
}

private val DURATION_PART = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h)")

/** Parses "24s", "1m12s", "500ms", or a bare number of seconds. */
internal fun parseRateLimitDuration(value: String): Long? {
    val text = value.trim().lowercase()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return (it * 1000).roundToLong() }
    var total = 0.0
    var matched = false
    DURATION_PART.findAll(text).forEach { match ->
        val amount = match.groupValues[1].toDoubleOrNull() ?: return@forEach
        matched = true
        total +=
            amount *
            when (match.groupValues[2]) {
                "ms" -> 1.0
                "m" -> 60_000.0
                "h" -> 3_600_000.0
                else -> 1_000.0
            }
    }
    return if (matched) total.roundToLong() else null
}

private val RETRY_DELAY_FIELD = Regex("\"retryDelay\"\\s*:\\s*\"([\\d.]+)s\"")
private val RETRY_IN_MESSAGE = Regex("retry in ([\\d.]+)\\s*s", RegexOption.IGNORE_CASE)
private val RETRY_SECONDS_MESSAGE = Regex("(?:wait|after) ([\\d.]+) seconds?", RegexOption.IGNORE_CASE)

/** Google and OpenAI-compatible error bodies both state a retry hint in text form. */
internal fun retryHintFromBody(body: String): Long? {
    if (body.isBlank()) return null
    val seconds =
        RETRY_DELAY_FIELD.find(body)?.groupValues?.get(1)
            ?: RETRY_IN_MESSAGE.find(body)?.groupValues?.get(1)
            ?: RETRY_SECONDS_MESSAGE.find(body)?.groupValues?.get(1)
            ?: return null
    val value = seconds.toDoubleOrNull() ?: return null
    return (value * 1000).roundToLong()
}

/** One retry summary for logs and notices, e.g. "waiting 24s". */
internal fun waitLabel(waitMillis: Long): String = "${((waitMillis + 999) / 1000)}s"
