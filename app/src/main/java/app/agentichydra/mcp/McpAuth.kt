package app.agentichydra.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.agentichydra.KeyVault
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class AsMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val revokeEndpoint: String?,
    val scopesSupported: List<String>,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long?,
    val scope: String?,
)

data class OAuthCallback(val code: String?, val state: String?, val error: String?)

class McpAuth(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val keyVault = KeyVault(context)

    companion object {
        private val _callbacks = MutableSharedFlow<OAuthCallback>(
            replay = 1, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val callbacks: SharedFlow<OAuthCallback> = _callbacks.asSharedFlow()

        private val pendingCallback = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<OAuthCallback>?>(null)

        fun deliverCallback(cb: OAuthCallback) {
            pendingCallback.getAndSet(null)?.complete(cb)
            _callbacks.tryEmit(cb)
        }

        fun setPending(deferred: kotlinx.coroutines.CompletableDeferred<OAuthCallback>) {
            pendingCallback.set(deferred)
        }
    }

    suspend fun discover(resourceUrl: String): AsMetadata {
        val uri = URI(resourceUrl)
        val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        val path = uri.path.orEmpty().trimEnd('/')
        // protected resource metadata
        var asUrl: String? = null
        val prmCandidates = listOf(
            "$origin/.well-known/oauth-protected-resource$path",
            "$origin/.well-known/oauth-protected-resource",
        )
        for (c in prmCandidates) {
            val r = httpGet(c)
            if (r.isSuccessful) {
                val j = JSONObject(r.body!!.string())
                val servers = j.optJSONArray("authorization_servers")
                if (servers != null && servers.length() > 0) { asUrl = servers.getString(0); break }
            }
        }
        val base = asUrl ?: origin
        val baseUri = URI(base)
        val baseOrigin = "${baseUri.scheme}://${baseUri.host}${if (baseUri.port > 0) ":${baseUri.port}" else ""}"
        val basePath = baseUri.path.orEmpty().trimEnd('/')
        val asmCandidates = listOf(
            "$baseOrigin/.well-known/oauth-authorization-server$basePath",
            "$baseOrigin/.well-known/oauth-authorization-server",
        )
        for (c in asmCandidates) {
            val r = httpGet(c)
            if (r.isSuccessful) {
                val j = JSONObject(r.body!!.string())
                return AsMetadata(
                    issuer = j.optString("issuer", base),
                    authorizationEndpoint = j.getString("authorization_endpoint"),
                    tokenEndpoint = j.getString("token_endpoint"),
                    registrationEndpoint = j.optStringOrNull("registration_endpoint"),
                    revokeEndpoint = j.optStringOrNull("revocation_endpoint"),
                    scopesSupported = (j.optJSONArray("scopes_supported") ?: JSONArray()).let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    }.ifEmpty { listOf("default") },
                )
            }
        }
        // fallback
        return AsMetadata(
            issuer = base,
            authorizationEndpoint = "$baseOrigin$basePath/authorize",
            tokenEndpoint = "$baseOrigin$basePath/token",
            registrationEndpoint = null,
            revokeEndpoint = null,
            scopesSupported = listOf("default"),
        )
    }

    suspend fun registerClient(metadata: AsMetadata, redirectUri: String): String {
        val ep = metadata.registrationEndpoint ?: throw IllegalStateException("No registration endpoint")
        val body = JSONObject().apply {
            put("client_name", "Hydra")
            put("redirect_uris", JSONArray().put(redirectUri))
            put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            put("response_types", JSONArray().put("code"))
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
        }
        val req = Request.Builder().url(ep)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("DCR failed: ${resp.code} ${resp.body?.string()}")
        val j = JSONObject(resp.body!!.string())
        return j.getString("client_id")
    }

    fun pkce(): Pair<String, String> {
        val verifier = randomString(64)
        val challenge = base64Url(sha256(verifier))
        return verifier to challenge
    }

    fun newState(): String = randomString(32)

    fun buildAuthorizeUrl(
        metadata: AsMetadata,
        clientId: String,
        redirectUri: String,
        scopes: String,
        resource: String,
        state: String,
        challenge: String,
    ): String {
        val scopeStr = if (scopes.isBlank()) metadata.scopesSupported.firstOrNull() ?: "default" else scopes
        return metadata.authorizationEndpoint +
            "?response_type=code" +
            "&client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&scope=${Uri.encode(scopeStr)}" +
            "&state=${Uri.encode(state)}" +
            "&code_challenge=${Uri.encode(challenge)}" +
            "&code_challenge_method=S256" +
            "&resource=${Uri.encode(resource)}"
    }

    suspend fun exchangeCode(
        metadata: AsMetadata,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
        resource: String,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .add("resource", resource)
            .build()
        val req = Request.Builder().url(metadata.tokenEndpoint).post(form).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("Token exchange failed: ${resp.code} ${resp.body?.string()}")
        val j = JSONObject(resp.body!!.string())
        return TokenResponse(
            accessToken = j.getString("access_token"),
            refreshToken = j.optStringOrNull("refresh_token"),
            expiresIn = if (j.has("expires_in")) j.getLong("expires_in") else null,
            scope = j.optStringOrNull("scope"),
        )
    }

    suspend fun refreshToken(metadata: AsMetadata, clientId: String, refreshToken: String, resource: String): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("resource", resource)
            .build()
        val req = Request.Builder().url(metadata.tokenEndpoint).post(form).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("Refresh failed: ${resp.code} ${resp.body?.string()}")
        val j = JSONObject(resp.body!!.string())
        return TokenResponse(
            accessToken = j.getString("access_token"),
            refreshToken = j.optStringOrNull("refresh_token") ?: refreshToken,
            expiresIn = if (j.has("expires_in")) j.getLong("expires_in") else null,
            scope = j.optStringOrNull("scope"),
        )
    }

    fun launchAuthorize(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder().build().intent
        intent.data = Uri.parse(url)
        context.startActivity(intent)
    }

    suspend fun awaitCallback(timeoutMs: Long = 300_000): OAuthCallback {
        val deferred = kotlinx.coroutines.CompletableDeferred<OAuthCallback>()
        McpAuth.setPending(deferred)
        return withTimeout(timeoutMs) { deferred.await() }
    }

    fun storeTokens(serverId: String, access: String, refresh: String?) {
        keyVault.write("mcp_token_$serverId", access)
        if (refresh != null) keyVault.write("mcp_refresh_$serverId", refresh)
    }

    fun readAccessToken(serverId: String): String = keyVault.read("mcp_token_$serverId")
    fun readRefreshToken(serverId: String): String = keyVault.read("mcp_refresh_$serverId")

    fun clearTokens(serverId: String) {
        keyVault.write("mcp_token_$serverId", "")
        keyVault.write("mcp_refresh_$serverId", "")
    }

    fun revokeBestEffort(metadata: AsMetadata, clientId: String, token: String) {
        val ep = metadata.revokeEndpoint ?: return
        runCatching {
            val form = FormBody.Builder().add("token", token).add("client_id", clientId).build()
            client.newCall(Request.Builder().url(ep).post(form).build()).execute().close()
        }
    }

    private fun httpGet(url: String): okhttp3.Response = client.newCall(Request.Builder().url(url).get().build()).execute()

    private fun randomString(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val sb = StringBuilder(len)
        val r = SecureRandom()
        repeat(len) { sb.append(chars[r.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

// (end of file)