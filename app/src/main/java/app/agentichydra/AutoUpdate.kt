package app.agentichydra

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** A newer published release of the app. */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val assetName: String,
    val downloadUrl: String,
    val checksumUrl: String? = null,
)

private const val RELEASES_URL = "https://api.github.com/repos/theonlyasdk/AgenticHydra/releases/latest"
private const val PREFS_NAME = "updates"
private const val DOWNLOADED_TAG_KEY = "downloadedTag"
internal const val UPDATE_APK_NAME = "hydra-update.apk"
private const val USER_AGENT = "Hydra-Updater"

internal fun updatesEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("autoUpdate", true)

internal fun setUpdatesEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("autoUpdate", enabled).apply()
}

/** The release tag of the APK currently staged in [UPDATE_APK_NAME], if any. */
internal fun downloadedUpdateTag(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(DOWNLOADED_TAG_KEY, null)

private fun setDownloadedUpdateTag(context: Context, tag: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(DOWNLOADED_TAG_KEY, tag).apply()
}

/** Deletes the staged update APK and forgets its tag once the update has been installed. */
internal fun clearDownloadedUpdate(context: Context) {
    File(context.filesDir, UPDATE_APK_NAME).delete()
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(DOWNLOADED_TAG_KEY).apply()
}

/** The already-downloaded APK for [info] when it is staged and matches the release tag, else null. */
internal fun stagedUpdateApk(context: Context, info: UpdateInfo): File? {
    val file = File(context.filesDir, UPDATE_APK_NAME)
    return if (file.length() > 0 && downloadedUpdateTag(context) == info.tagName) file else null
}

/** Whether the user has allowed Hydra to install packages (the "Install unknown apps" toggle). */
internal fun canInstallUpdates(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

/** Opens the system "Install unknown apps" screen for Hydra so the user can allow updates. */
internal fun installPermissionSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

/** The app’s current version name, read from the running package. */
internal fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

/** The newest published release newer than the running version, or null when there is none. */
internal fun checkForUpdate(context: Context): UpdateInfo? =
    runCatching { fetchLatestRelease() }
        .getOrNull()
        ?.let { info -> info.takeIf { isNewerVersion(info.versionName, appVersionName(context)) } }

/**
 * The latest non-draft, non-prerelease release published on GitHub, or null when none is
 * found. [client] and [url] are parameters so unit tests can point them at a local server.
 */
internal fun fetchLatestRelease(client: OkHttpClient = OkHttpClient(), url: String = RELEASES_URL): UpdateInfo? {
    val request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
    val release =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            JSONObject(response.body?.string() ?: return null)
        }
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tagName = release.optString("tag_name").trim()
    if (tagName.isEmpty()) return null
    var apkName: String? = null
    var apkUrl: String? = null
    var fallbackName: String? = null
    var fallbackUrl: String? = null
    var checksumUrl: String? = null
    val assets = release.optJSONArray("assets")
    if (assets != null) {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val assetUrl = asset.optString("browser_download_url").trim()
            if (name.isEmpty() || assetUrl.isEmpty()) continue
            if (name.equals("SHA256SUMS", ignoreCase = true)) {
                checksumUrl = assetUrl
            } else if (name.endsWith(".apk")) {
                if (apkName == null) {
                    apkName = name
                    apkUrl = assetUrl
                }
            } else if (fallbackName == null) {
                fallbackName = name
                fallbackUrl = assetUrl
            }
        }
    }
    val assetName = apkName ?: fallbackName ?: return null
    val downloadUrl = apkUrl ?: fallbackUrl ?: return null
    return UpdateInfo(tagName, tagName.trimStart('v'), assetName, downloadUrl, checksumUrl)
}

/** True when [latest] sorts after [current]: "1.5.0" is newer than "1.4.0". */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    val latestParts = parseVersion(latest)
    val currentParts = parseVersion(current)
    for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
        val latestPart = latestParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

/** Splits a dotted version into integers: "v1.4.0" → [1, 4, 0]; a missing part counts as 0. */
internal fun parseVersion(version: String): List<Int> =
    version.trim().trimStart('v').split('.').map { it.trim().toIntOrNull() ?: 0 }

/** Lowercase hex SHA-256 of [file]. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** The SHA-256 listed for [assetName] in a SHA256SUMS file, lowercase, or null when absent. */
internal fun expectedSha256(sums: String, assetName: String): String? {
    for (line in sums.lines()) {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) continue
        val hash = parts[0]
        val name = parts[1].trim().trimStart('*').substringAfterLast('/')
        if (name == assetName && hash.length == 64 && hash.all { it in "0123456789abcdefABCDEF" }) {
            return hash.lowercase()
        }
    }
    return null
}

/** True when the APK matches the release's SHA256SUMS, or when the release publishes no sums. */
private fun verifyChecksum(info: UpdateInfo, file: File): Boolean {
    val url = info.checksumUrl ?: return true
    val sums =
        runCatching {
            OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        }.getOrNull() ?: return false
    val expected = expectedSha256(sums, info.assetName) ?: return false
    return sha256Hex(file).equals(expected, ignoreCase = true)
}

/**
 * Downloads [info] into the app’s private files directory and returns the APK file, or null
 * when the download fails or the response is not a file. [onProgress] receives the number of
 * bytes downloaded so far and the expected total; a total of -1 means unknown.
 */
internal suspend fun downloadUpdate(context: Context, info: UpdateInfo, onProgress: ((Long, Long) -> Unit)? = null): File? {
    val file = File(context.filesDir, UPDATE_APK_NAME)
    file.delete()
    try {
        val request = Request.Builder().url(info.downloadUrl).header("User-Agent", USER_AGENT).build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val total = body.contentLength()
            var downloaded = 0L
            FileOutputStream(file).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, total)
                    }
                }
            }
        }
        if (file.length() <= 0) return null
        if (!verifyChecksum(info, file)) {
            file.delete()
            return null
        }
        setDownloadedUpdateTag(context, info.tagName)
        return file
    } catch (e: Exception) {
        file.delete()
        if (e is CancellationException) throw e
        return null
    }
}

/**
 * Stages [apkFile] in a PackageInstaller session and commits it. The result is sent to
 * [InstallStatusReceiver], which toasts success or failure.
 */
internal fun installUpdateApk(context: Context, apkFile: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppLabel(context.packageManager.getApplicationLabel(context.applicationInfo))
    params.setSize(apkFile.length())
    val sessionId = installer.createSession(params)
    val session = installer.openSession(sessionId)
    var committed = false
    try {
        val out = session.openWrite(apkFile.name, 0, apkFile.length())
        FileInputStream(apkFile).use { input -> input.copyTo(out) }
        session.fsync(out)
        out.close()
        val statusIntent = Intent(context, InstallStatusReceiver::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        session.commit(pending.intentSender)
        committed = true
    } finally {
        // Drop any session that never committed so failed attempts don't accumulate.
        if (!committed) runCatching { session.abandon() }
        session.close()
    }
}

/** Toasts success or failure after the system installs the update APK. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_SUCCESS -> {
                clearDownloadedUpdate(context)
                Toast.makeText(context, "Update installed — open Hydra to finish.", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system needs the user to confirm; launch the intent it hands back.
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    Toast.makeText(context, "The update needs your confirmation in the system installer.", Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.trim()
                Toast.makeText(
                    context,
                    if (detail.isNullOrBlank()) {
                        "The update could not be installed. Check your phone’s install settings and try again."
                    } else {
                        "The update could not be installed: $detail"
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
