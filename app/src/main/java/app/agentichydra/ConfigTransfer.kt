package app.agentichydra

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Settings, provider connections and API keys leave the device as one JSON bundle, written to a
 * file through the storage picker or split into QR frames another phone scans. Keys live on the
 * phone behind a Keystore key that cannot be exported, so a bundle that travels carries them in
 * clear — which is why only a bundle the user asks for includes conversations.
 */
internal const val CONFIG_FILE_NAME = "hydra-config.json"

private const val CONFIG_KIND = "hydra.config"
private const val CONFIG_VERSION = 1

/** Prefs strings that already hold JSON travel as JSON, so they stay compact and readable. */
private val JSON_STRING_KEYS = setOf("history", "records", "snapshot")

private const val MAX_CONFIG_BYTES = 8 * 1024 * 1024

/** Frame layout: magic, 4-byte session, frame index, frame count, then the compressed payload. */
private val FRAME_MAGIC = byteArrayOf('H'.code.toByte(), 'Y'.code.toByte(), 'C'.code.toByte())
private const val FRAME_HEADER_BYTES = 11
private const val FRAME_DATA_BYTES = 256
private const val MAX_FRAMES = 512
private const val MAX_SESSIONS = 8

/** What a transfer carries. The sending phone ticks these before codes are drawn or a file is written. */
internal data class ConfigSelection(
    val connection: Boolean = true,
    val keys: Boolean = true,
    val appearance: Boolean = true,
    val conversations: Boolean = false,
) {
    val any get() = connection || keys || appearance || conversations
}

/** Remembers the last choice, so Export and Share always carry the same things. */
internal object ConfigSelectionStore {
    private const val PREFS = "config_transfer"

    fun load(context: Context): ConfigSelection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ConfigSelection(
            connection = prefs.getBoolean("connection", true),
            keys = prefs.getBoolean("keys", true),
            appearance = prefs.getBoolean("appearance", true),
            conversations = prefs.getBoolean("conversations", false),
        )
    }

    fun save(
        context: Context,
        selection: ConfigSelection,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("connection", selection.connection)
            .putBoolean("keys", selection.keys)
            .putBoolean("appearance", selection.appearance)
            .putBoolean("conversations", selection.conversations)
            .apply()
    }
}

/** Everything a phone needs to recreate this one's setup, as readable JSON. */
internal fun configBundle(
    context: Context,
    selection: ConfigSelection = ConfigSelection(),
): String {
    val hydra = prefs(context, "hydra")
    val work = prefs(context, "work")
    val settings =
        JSONObject().apply {
            put(
                "hydra",
                hydra.json { key ->
                    when {
                        key.startsWith("catalog_") -> false
                        key == "history" -> selection.conversations
                        else -> selection.connection
                    }
                },
            )
            if (selection.connection) {
                put("advanced_model", prefs(context, "advanced_model").json { true })
                put("context", prefs(context, "context").json { true })
            }
            if (selection.appearance) {
                put("appearance", prefs(context, "appearance").json { true })
                put("external_links", prefs(context, "external_links").json { true })
            }
            put(
                "work",
                JSONObject().apply {
                    if (selection.appearance) {
                        work.getString("approvalMode", null)?.let { put("approvalMode", it) }
                    }
                    if (selection.conversations) {
                        work.getString("records", null)?.let { put("records", jsonValue(it) ?: it) }
                    }
                },
            )
        }
    val keys =
        JSONObject().apply {
            if (selection.keys) {
                val vault = KeyVault(context)
                Provider.entries.forEach { provider ->
                    vault.read(provider.name).takeIf { it.isNotBlank() }?.let { put(provider.name, it) }
                }
            }
        }
    val version =
        runCatching {
            context
                .packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull().orEmpty()
    return JSONObject()
        .put("kind", CONFIG_KIND)
        .put("version", CONFIG_VERSION)
        .put("app", version)
        .put("created", System.currentTimeMillis())
        .put("keys", keys)
        .put("settings", settings)
        .toString()
}

/** What a bundle would change here, shown before it is applied. */
internal data class ConfigSummary(
    val connection: String,
    val providers: Int,
    val keys: Int,
    val chats: Int,
    val tasks: Int,
    val savedOn: String,
) {
    val body: String
        get() =
            buildString {
                append("Applies the settings bundle from the other phone.")
                if (chats > 0 || tasks > 0) {
                    append(" It adds $chats chats and $tasks finished tasks.")
                } else {
                    append(" Chats and task history stay untouched.")
                }
                append("\n\nActive connection: $connection")
                append("\nProvider entries: $providers")
                append("\nAPI keys: $keys")
                append("\nSaved on the other device: $savedOn")
            }
}

internal fun readConfigSummary(bundle: String): ConfigSummary {
    val json = parseBundle(bundle)
    val settings = json.getJSONObject("settings")
    val hydra = settings.optJSONObject("hydra") ?: JSONObject()
    val connection =
        if (!hydra.has("provider")) {
            "Not included in this bundle"
        } else {
            val provider =
                runCatching { Provider.valueOf(hydra.optString("provider")) }.getOrDefault(Provider.NIM)
            val model = hydra.optString("model", provider.defaultModel)
            "${provider.label} · ${model.ifBlank { "no model chosen" }}"
        }
    val keys = json.getJSONObject("keys")
    return ConfigSummary(
        connection = connection,
        providers = hydra.keys().asSequence().count { it.startsWith("endpoint_") },
        keys = keys.length(),
        chats = hydra.optJSONArray("history")?.length() ?: 0,
        tasks = settings.optJSONObject("work")?.optJSONArray("records")?.length() ?: 0,
        savedOn = DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(json.optLong("created", System.currentTimeMillis())),
    )
}

/** Writes the bundle back into every prefs file it came from and points the live models at it. */
internal fun applyConfig(
    context: Context,
    bundle: String,
) {
    val json = parseBundle(bundle)
    val settings = json.getJSONObject("settings")
    settings.keys().asSequence().forEach { name ->
        val values = settings.optJSONObject(name) ?: return@forEach
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        values.keys().asSequence().forEach { key ->
            if (name != "work" || key != "records") editor.putTyped(key, values.get(key))
        }
        editor.apply()
    }
    val vault = KeyVault(context)
    val keys = json.getJSONObject("keys")
    keys.keys().asSequence().forEach { vault.write(it, keys.getString(it)) }

    val models = (context.applicationContext as HydraApplication).models
    val chat = models[HydraModel::class.java]
    val hydra = settings.optJSONObject("hydra") ?: JSONObject()
    chat.invalidateConnectionChecks(
        Provider.entries.filter {
            keys.has(it.name) || hydra.has("endpoint_${it.name}") || hydra.optString("provider") == it.name
        },
    )
    if (hydra.has("provider")) {
        val provider = runCatching { Provider.valueOf(hydra.optString("provider")) }.getOrElse { Provider.NIM }
        // A bundle can leave keys out; an empty key would then erase the live one.
        val key = keys.optString(provider.name).ifBlank { vault.read(provider.name) }
        chat.configure(provider, hydra.optString("endpoint", provider.base), hydra.optString("model", ""), key)
    } else if (keys.length() > 0) {
        // Keys alone still have to reach the running model, which reads them once at startup.
        chat.configure(chat.provider, chat.endpoint, chat.model, vault.read(chat.provider.name))
    }
    // The models read their history once at startup, so hand them the restored lists.
    if (hydra.has("history")) chat.reloadConversations()
    (settings.optJSONObject("work")?.opt("records") as? String)?.let {
        models[WorkModel::class.java].reloadRecords(it)
    }
    settings.optJSONObject("work")?.optString("approvalMode")?.let { raw ->
        models[WorkModel::class.java].mode = runCatching { ApprovalMode.valueOf(raw) }.getOrDefault(ApprovalMode.CHANGES)
    }
    refreshBudget(context)
    chat.refreshContextUsage()
    models[WorkModel::class.java].refreshContextUsage()
}

/** Re-reads the window just written to prefs, because the in-memory copy only loads once. */
private fun refreshBudget(context: Context) {
    val tokens = context.getSharedPreferences("context", Context.MODE_PRIVATE).getInt("tokens", DEFAULT_CONTEXT_TOKENS)
    ContextBudget.choose(context, tokens)
}

private fun prefs(
    context: Context,
    name: String,
) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

private fun SharedPreferences.json(keep: (String) -> Boolean): JSONObject {
    val out = JSONObject()
    all.forEach { (key, value) ->
        if (value == null || !keep(key)) return@forEach
        if (value is String && key in JSON_STRING_KEYS) {
            out.put(key, jsonValue(value) ?: value)
        } else {
            out.putTyped(key, value)
        }
    }
    return out
}

/** Parses a prefs string that already holds JSON, or null when it is plain text. */
private fun jsonValue(raw: String): Any? = runCatching { JSONTokener(raw).nextValue() }.getOrNull()

private fun JSONObject.putTyped(
    key: String,
    value: Any,
) {
    when (value) {
        is String -> put(key, value)
        is Boolean -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Float -> put(key, value)
        is Double -> put(key, value)
        is JSONObject -> put(key, value)
        is JSONArray -> put(key, value)
        else -> {}
    }
}

private fun SharedPreferences.Editor.putTyped(
    key: String,
    value: Any,
) {
    when (value) {
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Double -> putFloat(key, value.toFloat())
        is JSONObject, is JSONArray -> putString(key, value.toString())
        else -> {}
    }
}

private fun parseBundle(bundle: String): JSONObject {
    require(bundle.length <= MAX_CONFIG_BYTES) { "The settings file is too large to import." }
    val json =
        runCatching { JSONObject(bundle) }.getOrNull()
            ?: error("That is not a Hydra settings file.")
    require(json.optInt("version", -1) == CONFIG_VERSION && json.optString("kind") == CONFIG_KIND) {
        error("That settings file came from another app, or a Hydra version this one cannot read.")
    }
    require(json.has("settings") && json.has("keys")) { error("That settings file is missing its settings.") }
    return json
}

/**
 * Compressed frames sized for a camera to pick up off a screen: each holds 256 payload bytes and
 * is looped on the sending side, so the whole bundle arrives even when a scan catches only some.
 * Base64 keeps the codes ASCII, because a reader hands back text and a stray control byte would
 * survive as a different string than it went in as.
 */
internal fun configQrFrames(bundle: String): List<String> {
    val payload = deflate(bundle.toByteArray(Charsets.UTF_8))
    val total = ceil(payload.size / FRAME_DATA_BYTES.toDouble()).toInt()
    require(total >= 1) { "There is nothing to send yet." }
    require(total <= MAX_FRAMES) {
        "These settings would need $total codes, and this version shows up to $MAX_FRAMES. " +
            "Export them to a file instead."
    }
    val session = ByteBuffer.wrap(ByteArray(4).also { Random.nextBytes(it) }).int
    val encoder = Base64.getEncoder()
    return (0 until total).map { index ->
        val from = index * FRAME_DATA_BYTES
        val slice = payload.copyOfRange(from, minOf(from + FRAME_DATA_BYTES, payload.size))
        val frame =
            ByteBuffer
                .allocate(FRAME_HEADER_BYTES + slice.size)
                .put(FRAME_MAGIC)
                .putInt(session)
                .putShort(index.toShort())
                .putShort(total.toShort())
                .put(slice)
                .array()
        encoder.encodeToString(frame)
    }
}

/** Progress of one reassembling scan; a fresh session on screen restarts the count. */
internal data class ConfigFrameProgress(
    val frames: Int,
    val total: Int,
    val bundle: String?,
)

/** Collects frames out of order and across rescans, then hands back the finished bundle. */
internal class ConfigFrameCollector {
    private val sessions = LinkedHashMap<Int, MutableMap<Int, ByteArray>>()

    /** Null when the code is not a Hydra settings frame, so the caller can ignore other QR codes. */
    fun accept(code: String): ConfigFrameProgress? {
        val frame = runCatching { Base64.getDecoder().decode(code) }.getOrNull() ?: return null
        if (frame.size <= FRAME_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(frame)
        val magic = ByteArray(FRAME_MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(FRAME_MAGIC)) return null
        val session = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        if (total !in 1..MAX_FRAMES || index >= total) return null
        val slices = sessions.getOrPut(session) {
            if (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
            mutableMapOf()
        }
        if (slices.size < total || index in slices) {
            slices[index] = buffer.array().copyOfRange(FRAME_HEADER_BYTES, frame.size)
        }
        val received = slices.size
        if (received < total) return ConfigFrameProgress(received, total, null)
        val payload = ByteArrayOutputStream()
        (0 until total).forEach { payload.write(slices.getValue(it)) }
        val bundle = runCatching { inflate(payload.toByteArray()).toString(Charsets.UTF_8) }.getOrNull()
        return ConfigFrameProgress(total, total, bundle)
    }
}

private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    deflater.setInput(bytes)
    deflater.finish()
    val out = ByteArrayOutputStream(bytes.size)
    val buffer = ByteArray(4096)
    while (!deflater.finished()) {
        out.write(buffer, 0, deflater.deflate(buffer))
        require(out.size() <= MAX_CONFIG_BYTES) { "The settings are too large to share this way." }
    }
    deflater.end()
    return out.toByteArray()
}

private fun inflate(bytes: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(bytes)
    val out = ByteArrayOutputStream(bytes.size * 4)
    val buffer = ByteArray(4096)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0 && inflater.needsInput()) break
        out.write(buffer, 0, count)
        require(out.size() <= MAX_CONFIG_BYTES) { "The settings sent are too large." }
    }
    inflater.end()
    return out.toByteArray()
}
