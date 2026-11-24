package app.agentichydra.mcp

import android.content.Context
import android.content.SharedPreferences
import app.agentichydra.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class McpRegistry(private val context: Context) { // touch
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)

    private val vault = KeyVault(context)
    private val connectionAttempts = mutableMapOf<String, Any>()

    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()

    init {
        load()
    }

    @Synchronized
    fun load() {
        connectionAttempts.clear()
        _states.value.values.forEach { it.session?.close() }
        val arr = runCatching {
            JSONArray(prefs.getString("servers", "[]"))
        }.getOrDefault(JSONArray())
        var migrated = false
        val map = linkedMapOf<String, McpServerState>()
        for (i in 0 until arr.length()) {
            val json = arr.getJSONObject(i)
            val id = json.getString("id")
            for (field in listOf("bearerToken", "clientSecret")) {
                if (json.has(field)) {
                    json.optStringOrNull(field)?.let { vault.write(secretKey(id, field), it) }
                    json.remove(field)
                    migrated = true
                }
            }
            val c = configFromJson(json).copy(
                bearerToken = vault.read(secretKey(id, "bearerToken")).ifEmpty { null },
                clientSecret = vault.read(secretKey(id, "clientSecret")).ifEmpty { null },
            )
            map[c.id] = McpServerState(config = c)
        }
        if (migrated) prefs.edit().putString("servers", arr.toString()).apply()
        _states.value = map
    }

    private fun persist() {
        val arr = JSONArray()
        _states.value.values.forEach { arr.put(configToJson(it.config)) }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    @Synchronized
    fun addOrUpdate(config: McpServerConfig) {
        val map = _states.value.toMutableMap()
        val existing = map[config.id]
        if (existing?.config?.bearerToken != config.bearerToken) {
            vault.write(secretKey(config.id, "bearerToken"), config.bearerToken.orEmpty())
        }
        if (existing?.config?.clientSecret != config.clientSecret) {
            vault.write(secretKey(config.id, "clientSecret"), config.clientSecret.orEmpty())
        }
        map[config.id] = (existing ?: McpServerState(config = config)).copy(config = config)
        _states.value = map
        persist()
    }

    @Synchronized
    fun remove(serverId: String) {
        connectionAttempts.remove(serverId)
        val map = _states.value.toMutableMap()
        map[serverId]?.session?.close()
        if (map.remove(serverId) != null) {
            vault.write(secretKey(serverId, "bearerToken"), "")
            vault.write(secretKey(serverId, "clientSecret"), "")
        }
        _states.value = map
        persist()
    }

    fun get(serverId: String): McpServerState? = _states.value[serverId]

    private class ToolCatalog(
        val states: Map<String, McpServerState>,
        val tools: List<McpTool>,
    ) {
        val readOnlyNames = tools.asSequence().filter { it.readOnlyHint }.map { it.namespaced() }.toSet()
        // Each additional tool adds one separator to the nonempty built-in schema.
        val schemaChars by lazy { tools.sumOf { it.toOpenAiSchema().toString().length.toLong() + 1 } }
    }

    @Volatile
    private var cachedCatalog = ToolCatalog(emptyMap(), emptyList())

    private fun toolCatalog(): ToolCatalog {
        val catalog = cachedCatalog
        return if (catalog.states === _states.value) catalog else refreshToolCatalog()
    }

    @Synchronized
    private fun refreshToolCatalog(): ToolCatalog {
        val current = _states.value
        val catalog = cachedCatalog
        if (catalog.states === current) return catalog
        val tools = current.values.asSequence()
            .filter { it.status == McpStatus.CONNECTED && it.config.enabled }
            .flatMap { state -> state.tools.asSequence().filter { state.config.permitsTool(it.originalName) } }
            .distinctBy { it.namespaced() }
            .toList()
        return ToolCatalog(current, tools).also { cachedCatalog = it }
    }

    fun allTools(): List<McpTool> = toolCatalog().tools

    internal val toolSchemaChars: Long get() = toolCatalog().schemaChars

    fun readOnlyToolNames(): Set<String> = toolCatalog().readOnlyNames

    fun isRead(name: String): Boolean = name in readOnlyToolNames()

    fun findTool(namespaced: String): Pair<McpServerState, McpTool>? {
        for (s in _states.value.values) {
            if (s.status != McpStatus.CONNECTED || !s.config.enabled) continue
            val t = s.tools.firstOrNull { s.config.permitsTool(it.originalName) && it.namespaced() == namespaced }
            if (t != null) return s to t
        }
        return null
    }

    @Synchronized
    fun toggleTool(serverId: String, toolName: String, enabled: Boolean) {
        val state = get(serverId) ?: return
        val config = state.config
        val allowed = config.allowedTools
        if (allowed != null) {
            addOrUpdate(config.copy(allowedTools = if (enabled) allowed + toolName else allowed - toolName))
        } else {
            val disabled = config.disabledTools
            addOrUpdate(config.copy(disabledTools = if (enabled) disabled - toolName else disabled + toolName))
        }
    }

    @Synchronized
    fun applyToolPreset(serverId: String, readOnlyOnly: Boolean) {
        val state = get(serverId) ?: return
        val allowed = if (readOnlyOnly) {
            state.tools.filter { it.readOnlyHint }.map { it.originalName }.toSet()
        } else {
            null
        }
        addOrUpdate(state.config.copy(disabledTools = emptySet(), allowedTools = allowed))
    }

    @Synchronized
    fun allowSelectedTools(serverId: String) {
        val state = get(serverId) ?: return
        val allowed = state.tools.filter { state.config.permitsTool(it.originalName) }.map { it.originalName }.toSet()
        addOrUpdate(state.config.copy(disabledTools = emptySet(), allowedTools = allowed))
    }

    @Synchronized
    fun connect(serverId: String, onProgress: (String?, String) -> Unit = { _, _ -> }) {
        val state = _states.value[serverId] ?: return
        val attempt = Any()
        connectionAttempts[serverId] = attempt
        updateStatus(serverId, McpStatus.CONNECTING, null)
        scope.launch {
            var session: McpSession? = null
            try {
                val token =
                    state.config.bearerToken?.takeIf { it.isNotBlank() }
                        ?: McpAuth(context).readAccessToken(serverId).takeIf { it.isNotBlank() }
                val transport: McpTransport = when (state.config.transportType) {
                    McpTransportType.REMOTE_HTTP -> HttpTransport(state.config.url, state.config.allowPrivateUrl, token)
                    McpTransportType.STDIO -> throw UnsupportedOperationException("stdio not implemented in v1")
                }
                val candidate = McpSession(state.config, transport, onProgress = { token, msg -> onProgress(token, msg) })
                session = candidate
                val tools = candidate.connect()
                val accepted = synchronized(this@McpRegistry) {
                    val current = _states.value[serverId]
                    if (current == null || connectionAttempts[serverId] !== attempt) {
                        false
                    } else {
                        connectionAttempts.remove(serverId)
                        current.session?.close()
                        _states.value = _states.value + (serverId to current.copy(
                            status = McpStatus.CONNECTED,
                            tools = tools,
                            instructions = candidate.serverInstructions,
                            session = candidate,
                            lastError = null,
                        ))
                        true
                    }
                }
                if (!accepted) candidate.close()
            } catch (e: Exception) {
                session?.close()
                synchronized(this@McpRegistry) {
                    if (connectionAttempts[serverId] === attempt) {
                        connectionAttempts.remove(serverId)
                        val msg = e.message ?: e.javaClass.simpleName
                        val status = if (e is McpAuthException) McpStatus.NEEDS_AUTH else McpStatus.ERROR
                        updateStatus(serverId, status, msg)
                        _states.value[serverId]?.session?.close()
                    }
                }
            }
        }
    }

    @Synchronized
    fun disconnect(serverId: String) {
        connectionAttempts.remove(serverId)
        val state = _states.value[serverId] ?: return
        state.session?.close()
        _states.value = _states.value + (serverId to state.copy(
            status = McpStatus.DISCONNECTED,
            session = null,
            tools = emptyList(),
            lastError = null,
        ))
    }

    private fun updateStatus(serverId: String, status: McpStatus, error: String?) {
        val map = _states.value.toMutableMap()
        val s = map[serverId] ?: return
        map[serverId] = s.copy(status = status, lastError = error)
        _states.value = map
    }

    fun connectAllAuto() {
        for (s in _states.value.values) {
            if (s.config.autoConnect && s.config.enabled) {
                connect(s.config.id)
            }
        }
    }

    private fun secretKey(id: String, field: String) = "mcp_config_${id}_$field"

    companion object {
        fun configToJson(c: McpServerConfig): JSONObject = JSONObject().apply {
            put("id", c.id)
            put("displayName", c.displayName)
            put("transportType", c.transportType.name)
            put("url", c.url)
            put("command", c.command)
            put("args", JSONArray(c.args))
            put("authType", c.authType.name)
            put("scopes", c.scopes)
            put("autoConnect", c.autoConnect)
            put("allowPrivateUrl", c.allowPrivateUrl)
            put("enabled", c.enabled)
            put("disabledTools", JSONArray(c.disabledTools.toList()))
            c.allowedTools?.let { put("allowedTools", JSONArray(it.toList())) }
            put("clientId", c.clientId)
            put("redirectUri", c.redirectUri)
        }

        fun configFromJson(o: JSONObject): McpServerConfig = McpServerConfig(
            id = o.getString("id"),
            displayName = o.optString("displayName", o.optString("id")),
            transportType = runCatching { McpTransportType.valueOf(o.optString("transportType", "REMOTE_HTTP")) }.getOrDefault(McpTransportType.REMOTE_HTTP),
            url = o.optString("url", ""),
            command = o.optString("command", ""),
            args = (o.optJSONArray("args") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
            authType = runCatching { McpAuthType.valueOf(o.optString("authType", "NONE")) }.getOrDefault(McpAuthType.NONE),
            scopes = o.optString("scopes", "default"),
            autoConnect = o.optBoolean("autoConnect", true),
            allowPrivateUrl = o.optBoolean("allowPrivateUrl", false),
            enabled = o.optBoolean("enabled", true),
            disabledTools = (o.optJSONArray("disabledTools") ?: JSONArray()).let { a -> (0 until a.length()).mapTo(mutableSetOf()) { a.getString(it) } },
            allowedTools = o.optJSONArray("allowedTools")?.let { a ->
                (0 until a.length()).mapTo(mutableSetOf()) { a.getString(it) }
            },
            clientId = o.optStringOrNull("clientId"),
            redirectUri = o.optString("redirectUri", "app.agentichydra://oauth/mcp/callback"),
        )
    }
}