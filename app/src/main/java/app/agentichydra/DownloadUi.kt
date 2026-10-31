package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Inline progress card for download_file steps, live as bytes arrive. */
@Composable
internal fun DownloadStatusCard(
    download: DownloadProgress,
    running: Boolean,
    failed: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val fraction = if (download.total > 0) (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) else null
    val animated by animateFloatAsState(
        targetValue = if (running) fraction ?: 0f else 1f,
        animationSpec = spring(dampingRatio = .8f, stiffness = 260f),
        label = "Download progress",
    )
    val name = download.path.substringAfterLast('/').ifBlank { "Download" }
    val accent = if (failed) cs.error else cs.primary
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("download-progress"),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MarqueeTitle(
                    name,
                    Modifier.weight(1f),
                    surface = cs.surfaceContainerLow,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (failed) cs.error else cs.onSurface,
                )
                when {
                    failed -> Icon(Icons.Outlined.Close, "Download failed", Modifier.size(18.dp), tint = cs.error)
                    running -> {
                        fraction?.let {
                            Text(
                                "${(it * 100).roundToInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                                color = accent,
                            )
                        }
                    }

                    else -> Icon(Icons.Outlined.Check, "Downloaded", Modifier.size(18.dp), tint = cs.success)
                }
            }
            if (fraction == null && running) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accent,
                    trackColor = cs.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accent,
                    trackColor = cs.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }
            Text(
                downloadSummary(download, running, failed),
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) cs.error else cs.onSurfaceVariant,
            )
            if (download.path.isNotBlank()) {
                Text(
                    download.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun downloadSummary(
    download: DownloadProgress,
    running: Boolean,
    failed: Boolean,
): String {
    if (failed) {
        return if (download.downloaded > 0) {
            "Download failed after ${formatBytes(download.downloaded)}"
        } else {
            "Download failed"
        }
    }
    if (!running) {
        return if (download.downloaded > 0) {
            "${formatBytes(download.downloaded)} · Finished in ${formatDuration(download.elapsedMs)}"
        } else {
            "Download finished"
        }
    }
    val parts = mutableListOf<String>()
    if (download.total > 0) {
        parts += "${formatBytes(download.downloaded)} of ${formatBytes(download.total)}"
    } else if (download.downloaded > 0) {
        parts += formatBytes(download.downloaded)
    }
    if (download.bytesPerSecond > 0) parts += "${formatBytes(download.bytesPerSecond)}/s"
    if (download.total > 0) downloadEta(download.total - download.downloaded, download.bytesPerSecond)?.let { parts += it }
    return parts.joinToString(" · ").ifBlank { "Starting download…" }
}

private fun downloadEta(
    remaining: Long,
    bytesPerSecond: Long,
): String? {
    if (remaining <= 0) return null
    if (bytesPerSecond <= 0) return null
    val seconds = ceil(remaining.toDouble() / bytesPerSecond).toLong().coerceAtLeast(1)
    return when {
        seconds < 2 -> "Almost done"
        seconds < 60 -> "About ${seconds}s left"
        seconds < 3600 -> "About ${seconds / 60}m ${seconds % 60}s left"
        else -> "About ${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }
}

internal fun formatDuration(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000.0).roundToInt().coerceAtLeast(1)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) {
        "${value.roundToInt()} ${units[unit]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}

@Preview(name = "Download light")
@Preview(name = "Download dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Download large text", fontScale = 1.5f)
@Composable
private fun DownloadStatusCardPreview() {
    HydraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DownloadStatusCard(
                DownloadProgress(
                    path = "downloads/hydra-model.bin",
                    downloaded = 12_400_000,
                    total = 48_000_000,
                    elapsedMs = 8200,
                    bytesPerSecond = 1_512_000,
                ),
                running = true,
                failed = false,
            )
        }
    }
}
