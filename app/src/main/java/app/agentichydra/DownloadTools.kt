package app.agentichydra

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import kotlin.math.max

/** Downloads a public URL into the project while reporting byte progress. */
internal class FileDownloader(
    client: OkHttpClient,
    private val context: Context,
    private val project: ProjectTools,
) {
    private val client =
        client
            .newBuilder()
            // Redirects are followed by hand so every hop is checked against the public-address rule.
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String) =
                        Dns.SYSTEM.lookup(hostname).also { addresses ->
                            if (addresses.isEmpty() || !addresses.all(::isPublicAddress)) {
                                throw UnknownHostException("Only public web addresses are supported.")
                            }
                        }
                },
            ).build()

    suspend fun download(
        url: String,
        requestedPath: String,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): WorkToolResult {
        var target = url.trim()
        validateDownloadUrl(target)
        var active: Call? = null
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { active?.cancel() }
        try {
            var redirects = 0
            while (true) {
                val call =
                    client.newCall(
                        Request.Builder().url(target).header("User-Agent", "Mozilla/5.0 (Android) Hydra").build(),
                    )
                active = call
                val response = call.execute()
                response.use { result ->
                    if (result.isRedirect) {
                        require(redirects++ < 5) { "Too many redirects." }
                        target =
                            result.request.url
                                .resolve(result.header("Location") ?: error("A redirect has no destination."))
                                ?.toString() ?: error("Invalid redirect URL.")
                        validateDownloadUrl(target)
                        return@use
                    }
                    if (!result.isSuccessful) {
                        val detail = runCatching { result.peekBody(8192).string() }.getOrNull()
                        error(describeHttpFailure("Download failed", result.code, detail))
                    }
                    val body = result.body ?: error("The server returned no file.")
                    val total = body.contentLength().coerceAtLeast(0)
                    val name = downloadFileName(target, result.header("Content-Disposition"))
                    val path = downloadDestinationPath(requestedPath, name)
                    val destination = project.createDownloadDestination(path)
                    val started = SystemClock.elapsedRealtime()
                    var downloaded = 0L
                    var lastPost = 0L
                    try {
                        context.contentResolver.openOutputStream(destination.uri, "wt")?.use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastPost >= 200L || (total > 0 && downloaded >= total)) {
                                        lastPost = now
                                        onProgress(progress(path, downloaded, total, now - started))
                                    }
                                }
                            }
                        } ?: error("Could not open $path for writing.")
                    } catch (e: Throwable) {
                        runCatching { destination.delete() }
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val finished = progress(path, downloaded, if (total > 0) total else downloaded, elapsed)
                    onProgress(finished)
                    return WorkToolResult(
                        JSONObject()
                            .put("status", "downloaded")
                            .put("url", target)
                            .put("path", path)
                            .put("bytes", downloaded)
                            .put("content_type", result.header("Content-Type")?.substringBefore(';')?.trim().orEmpty())
                            .put("elapsed_ms", elapsed)
                            .put("average_bytes_per_second", finished.bytesPerSecond)
                            .toString(),
                    )
                }
            }
        } finally {
            cancellation.dispose()
        }
    }

    private fun progress(
        path: String,
        downloaded: Long,
        total: Long,
        elapsedMs: Long,
    ) = DownloadProgress(
        path = path,
        downloaded = downloaded,
        total = total,
        elapsedMs = elapsedMs,
        bytesPerSecond = if (elapsedMs > 0) downloaded * 1000L / max(elapsedMs, 1L) else 0,
    )
}

internal fun validateDownloadUrl(url: String) {
    val uri = runCatching { URI(url) }.getOrElse { error("Use a valid http or https URL.") }
    require(uri.scheme?.lowercase() in setOf("http", "https")) { "Downloads must use http or https." }
    require(!uri.host.isNullOrBlank()) { "Use a complete URL with a host." }
    require(uri.userInfo == null) { "URLs with embedded credentials are not supported." }
    val host = uri.host
    if (host.contains(':') || Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(host)) {
        require(isPublicAddress(InetAddress.getByName(host))) { "Only public web addresses are supported." }
    }
}

/** Picks the server's filename when offered, otherwise the last URL segment. */
internal fun downloadFileName(
    url: String,
    contentDisposition: String?,
): String {
    val parts = contentDisposition?.split(';')?.map { it.trim() }.orEmpty()
    val headerName =
        parts
            .firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.substringAfter("''")
            ?.takeIf { it.isNotBlank() }
            ?: parts
                .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', '\'')
    val urlName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
    return (headerName ?: urlName)
        .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F\"*:<>?|]"), "_")
        .trim()
        .take(120)
        .ifBlank { "download" }
}

/** A blank path means downloads/<name>; a folder path keeps the detected filename. */
internal fun downloadDestinationPath(
    requestedPath: String,
    fileName: String,
): String {
    val requested = requestedPath.trim()
    return when {
        requested.isEmpty() -> "downloads/$fileName"
        requested.endsWith('/') -> requested + fileName
        else -> requested
    }
}
