package app.agentichydra.mcp

import android.content.Context
import android.content.SharedPreferences
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

    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()

    init {
        load()
    }

    fun load() {
        val arr = runCatching {
            JSONArray(prefs.getString("servers", "[]"))
        }.getOrDefault(JSONArray())
        val map = linkedMapOf<String, McpServerState>()
        for (i in 0 until arr.length()) {
            val c = configFromJson(arr.getJSONObject(i))
            map[c.id] = McpServerState(config = c)
        }
        _states.value = map
    }

    private fun persist() {
        val arr = JSONArray()
        _states.value.values.forEach { arr.put(configToJson(it.config)) }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    fun addOrUpdate(config: McpServerConfig) {
        val map = _states.value.toMutableMap()
        val existing = map[config.id]
        map[config.id] = (existing ?: McpServerState(config = config)).copy(config = config)
        _states.value = map
        persist()
    }

    fun remove(serverId: String) {
        val map = _states.value.toMutableMap()
        map[serverId]?.session?.close()
        map.remove(serverId)
        _states.value = map
        persist()
    }

    fun get(serverId: String): McpServerState? = _states.value[serverId]

    fun allTools(): List<McpTool> {
        val out = mutableListOf<McpTool>()
        for (s in _states.value.values) {
            if (s.status != McpStatus.CONNECTED) continue
            for (t in s.tools) {
                if (t.originalName in s.config.disabledTools) continue
                out.add(t)
            }
        }
        return out
    }

    fun readOnlyToolNames(): Set<String> =
        allTools().filter { it.readOnlyHint }.map { it.namespaced() }.toSet()

    fun isRead(name: String): Boolean = name in readOnlyToolNames()

    fun findTool(namespaced: String): Pair<McpServerState, McpTool>? {
        for (s in _states.value.values) {
            val t = s.tools.firstOrNull { it.namespaced() == namespaced }
            if (t != null) return s to t
        }
        return null
    }

    fun toggleTool(serverId: String, toolName: String, enabled: Boolean) {
        val state = get(serverId) ?: return
        val disabled = state.config.disabledTools.toMutableSet()
        if (enabled) disabled.remove(toolName) else disabled.add(toolName)
        addOrUpdate(state.config.copy(disabledTools = disabled))
    }

    fun applyToolPreset(serverId: String, readOnlyOnly: Boolean) {
        val state = get(serverId) ?: return
        val disabled =
            if (readOnlyOnly) {
                state.tools.filterNot { it.readOnlyHint }.map { it.originalName }.toSet()
            } else {
                emptySet()
            }
        addOrUpdate(state.config.copy(disabledTools = disabled))
    }

    fun connect(serverId: String, onProgress: (String?, String) -> Unit = { _, _ -> }) {
        val state = _states.value[serverId] ?: return
        updateStatus(serverId, McpStatus.CONNECTING, null)
        scope.launch {
            try {
                val token =
                    state.config.bearerToken?.takeIf { it.isNotBlank() }
                        ?: McpAuth(context).readAccessToken(serverId).takeIf { it.isNotBlank() }
                val transport: McpTransport = when (state.config.transportType) {
                    McpTransportType.REMOTE_HTTP -> HttpTransport(state.config.url, state.config.allowPrivateUrl, token)
                    McpTransportType.STDIO -> throw UnsupportedOperationException("stdio not implemented in v1")
                }
                val session = McpSession(state.config, transport, onProgress = { token, msg -> onProgress(token, msg) })
                val tools = session.connect()
                val newState = McpServerState(
                    config = state.config,
                    status = McpStatus.CONNECTED,
                    tools = tools,
                    instructions = session.serverInstructions,
                    session = session,
                )
                val map = _states.value.toMutableMap()
                map[serverId] = newState
                _states.value = map
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                val status = if (e is McpAuthException) McpStatus.NEEDS_AUTH else McpStatus.ERROR
                updateStatus(serverId, status, msg)
                _states.value[serverId]?.session?.close()
            }
        }
    }

    fun disconnect(serverId: String) {
        val state = _states.value[serverId] ?: return
        state.session?.close()
        updateStatus(serverId, McpStatus.DISCONNECTED, null)
        val map = _states.value.toMutableMap()
        map[serverId] = state.copy(status = McpStatus.DISCONNECTED, session = null, tools = emptyList())
        _states.value = map
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
            put("bearerToken", c.bearerToken)
            put("clientId", c.clientId)
            put("clientSecret", c.clientSecret)
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
            bearerToken = o.optStringOrNull("bearerToken"),
            clientId = o.optStringOrNull("clientId"),
            clientSecret = o.optStringOrNull("clientSecret"),
            redirectUri = o.optString("redirectUri", "app.agentichydra://oauth/mcp/callback"),
        )
    }
}