package app.agentichydra

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateTest {
    @Test
    fun parseVersionSplitsDottedVersions() {
        assertEquals(listOf(1, 4, 0), parseVersion("1.4.0"))
        assertEquals(listOf(1, 5), parseVersion("v1.5"))
        assertEquals(listOf(1, 0), parseVersion(" 1.0 "))
    }

    @Test
    fun isNewerVersionComparesDottedVersions() {
        assertTrue(isNewerVersion("1.5.0", "1.4.0"))
        assertTrue(isNewerVersion("1.4.1", "1.4.0"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertFalse(isNewerVersion("1.4.0", "1.4.0"))
        assertFalse(isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(isNewerVersion("1.3.9", "1.4.0"))
    }

    @Test
    fun fetchLatestReleaseParsesReleaseAndPrefersApkAssets() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "tag_name": "v1.5.0",
                      "draft": false,
                      "prerelease": false,
                      "assets": [
                        {"name": "SHA256SUMS", "browser_download_url": "https://example.com/SHA256SUMS"},
                        {"name": "Agentic-Hydra-release.apk", "browser_download_url": "https://example.com/hydra.apk"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val info = fetchLatestRelease(OkHttpClient(), server.url("/releases/latest").toString())
        server.shutdown()
        assertNotNull(info)
        assertEquals("v1.5.0", info!!.tagName)
        assertEquals("1.5.0", info.versionName)
        assertEquals("Agentic-Hydra-release.apk", info.assetName)
        assertEquals("https://example.com/hydra.apk", info.downloadUrl)
        assertEquals("https://example.com/SHA256SUMS", info.checksumUrl)
    }

    @Test
    fun fetchLatestReleaseReturnsNullForDraftsAndPrereleases() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody("""{"tag_name": "v1.5.0", "draft": true, "prerelease": false, "assets": []}"""),
        )
        val info = fetchLatestRelease(OkHttpClient(), server.url("/releases/latest").toString())
        server.shutdown()
        assertNull(info)
    }

    @Test
    fun expectedSha256ParsesSumsLine() {
        val sums = "6D8F11AF9E507EC187D013C44AAD9C16181201AACB184E51EFD44A5EE6CB7706  Agentic-Hydra-release.apk\n"
        assertEquals(
            "6d8f11af9e507ec187d013c44aad9c16181201aacb184e51efd44a5ee6cb7706",
            expectedSha256(sums, "Agentic-Hydra-release.apk"),
        )
        assertNull(expectedSha256(sums, "other.apk"))
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        val file = File.createTempFile("hydra-sha256", ".bin")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }
}
