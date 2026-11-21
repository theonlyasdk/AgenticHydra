package app.agentichydra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlProgressTest {
    @Test
    fun parsesAFinishedCurlProgressLine() {
        val output = "100 48.0M  100 48.0M    0     0  1512k      0  0:00:32  0:00:32 --:--:-- 1800k"
        val progress = requireNotNull(parseCurlProgress(output))
        assertEquals(50_331_648L, progress.downloaded)
        assertEquals(50_331_648L, progress.total)
        assertEquals(1_843_200L, progress.bytesPerSecond)
        assertEquals(32_000L, progress.elapsedMs)
    }

    @Test
    fun takesTheLatestUpdateFromCarriageReturnSeparatedMeter() {
        val output =
            "  0     0    0     0    0     0      0      0 --:--:-- --:--:-- --:--:--     0\r" +
                " 45 48.0M   45 21.6M    0     0  1512k      0  0:00:32  0:00:14  0:00:18 1800k\r" +
                "100 48.0M  100 48.0M    0     0  1512k      0  0:00:32  0:00:32 --:--:-- 1800k"
        val progress = requireNotNull(parseCurlProgress(output))
        assertEquals(50_331_648L, progress.downloaded)
        assertEquals(32_000L, progress.elapsedMs)
    }

    @Test
    fun parsesAPartialTransferWithUnknownTotal() {
        val output = "  0  0    0  1024k    0     0   512k      0 --:--:--  0:00:02 --:--:--  512k"
        val progress = requireNotNull(parseCurlProgress(output))
        assertEquals(1_048_576L, progress.downloaded)
        assertEquals(0L, progress.total)
        assertEquals(2_000L, progress.elapsedMs)
    }

    @Test
    fun ignoresNonCurlOutputAndHeaderOnlyMeters() {
        assertNull(parseCurlProgress("Exit code: 0\nhello world\n"))
        assertNull(
            parseCurlProgress(
                "  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current\n" +
                    "                                 Dload  Upload   Total   Spent    Left  Speed\n",
            ),
        )
    }

    @Test
    fun namesTheTransferFromOutputFlagOrUrl() {
        assertEquals("model.bin", curlTargetName("curl -o model.bin https://example.com/files/model.bin"))
        assertEquals("data.zip", curlTargetName("curl --output out/data.zip https://example.com/data.zip"))
        assertEquals("archive.tar.gz", curlTargetName("curl https://example.com/files/archive.tar.gz"))
        assertEquals("download", curlTargetName("curl -L 'https://example.com/download?id=5'"))
        assertEquals("x", curlTargetName("curl -o - https://example.com/x"))
    }
}

class HttpErrorDetailTest {
    @Test
    fun extractsTheServerMessageFromJson() {
        assertEquals("Model not found", httpErrorDetail("""{"error":{"message":"Model not found","type":"invalid_request_error"}}"""))
        assertEquals("Something went wrong", httpErrorDetail("""{"message":"Something went wrong"}"""))
        assertEquals("plain string error", httpErrorDetail("""{"error":"plain string error"}"""))
        assertNull(httpErrorDetail(null))
        assertNull(httpErrorDetail("   "))
    }

    @Test
    fun describesFailuresWithTheServerWordsAndAReason() {
        val notFound = describeHttpFailure("Website request failed", 404, """{"error":{"message":"Model not found"}}""")
        assertTrue(notFound.contains("Model not found"))
        assertTrue(notFound.contains("HTTP 404"))
        assertTrue(notFound.contains("doesn't exist"))

        val badGateway =
            describeHttpFailure(
                "Download failed",
                502,
                "<html><head><title>502 Bad Gateway</title></head><body><h1>502 Bad Gateway</h1><hr>nginx</body></html>",
            )
        assertTrue(badGateway.contains("Bad Gateway"))
        assertTrue(badGateway.contains("HTTP 502"))
        assertTrue(badGateway.contains("upstream server"))

        val unavailable = describeHttpFailure("Download failed", 503, null)
        assertTrue(unavailable.contains("Service unavailable"))
        assertTrue(unavailable.contains("temporarily unavailable"))
    }
}