package app.agentichydra

import kotlin.math.max

/**
 * curl's default progress meter writes lines like:
 * `100 48.0M  100 48.0M    0     0  1512k      0  0:00:32  0:00:32 --:--:-- 1800k`
 * (% total, total, % received, received, % xferd, xferd, avg download, avg upload,
 * total time, spent, left, current speed). This matches one such data line.
 */
private val curlProgressLine =
    Regex(
        """^\s*(\d{1,3})\s+(\S+)\s+(\d{1,3})\s+(\S+)\s+(\d{1,3})\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*$""",
    )

private val curlTimeField = Regex("""^(?:--:--:--|\d+:\d{2}(?::\d{2})?)$""")
private val curlSizeField = Regex("""^([\d.]+)([kMGT]?)$""")

/** Reads the most recent curl progress line out of command output, mapping it to download counters. */
internal fun parseCurlProgress(output: String): DownloadProgress? {
    val lines = output.split('\r', '\n')
    for (index in lines.indices.reversed()) {
        val groups = curlProgressLine.matchEntire(lines[index])?.groupValues ?: continue
        if (!curlTimeField.matches(groups[9]) || !curlTimeField.matches(groups[10]) || !curlTimeField.matches(groups[11])) {
            continue
        }
        val received = curlBytes(groups[4])
        val transferred = curlBytes(groups[6])
        return DownloadProgress(
            downloaded = max(received, transferred),
            total = curlBytes(groups[2]),
            elapsedMs = curlTimeMs(groups[10]),
            bytesPerSecond = curlBytes(groups[12]),
        )
    }
    return null
}

/** A display name for a curl transfer: its -o/--output target, else the URL's last segment. */
internal fun curlTargetName(command: String): String {
    val output =
        Regex("""(?:-o|--output)\s+['"]?([^\s'"]+)['"]?""")
            .find(command)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it != "-" }
    if (!output.isNullOrBlank()) return output.substringAfterLast('/').ifBlank { "curl download" }
    val url = Regex("""https?://[^\s'"]+""").find(command)?.value
    val name = url?.substringBefore('?')?.substringAfterLast('/').orEmpty()
    return name.ifBlank { "curl download" }
}

private fun curlBytes(token: String): Long {
    val match = curlSizeField.matchEntire(token) ?: return 0
    val value = match.groupValues[1].toDoubleOrNull() ?: return 0
    val multiplier =
        when (match.groupValues[2]) {
            "k" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
    return (value * multiplier).toLong()
}

private fun curlTimeMs(token: String): Long {
    if (token == "--:--:--") return 0
    val parts = token.split(':').map { it.toLongOrNull() ?: 0L }
    return when (parts.size) {
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        2 -> (parts[0] * 60 + parts[1]) * 1000
        else -> 0L
    }
}