package app.agentichydra

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConfigFrameTest {
    /** Real bundles are mostly short URLs and keys, so the filler forces the code to need several frames. */
    private val filler = Base64.getEncoder().encodeToString(Random(7).nextBytes(3000))

    private val bundle = """{"kind":"hydra.config","version":1,"keys":{"NIM":"$filler"}}"""

    @Test
    fun framesReassembleIntoTheBundleTheyCameFrom() {
        val frames = configQrFrames(bundle)
        assertTrue("Expected several frames, got ${frames.size}", frames.size > 1)
        val collector = ConfigFrameCollector()
        var progress: ConfigFrameProgress? = null
        frames.shuffled().forEach { progress = collector.accept(it) ?: progress }
        assertEquals(bundle, progress?.bundle)
    }

    @Test
    fun aPartialScanReportsCountsWithoutInventingABundle() {
        val frames = configQrFrames(bundle)
        val progress = ConfigFrameCollector().accept(frames.first())
        assertEquals(1, progress?.frames)
        assertEquals(frames.size, progress?.total)
        assertNull(progress?.bundle)
    }

    @Test
    fun foreignCodesAreIgnoredRatherThanCounted() {
        assertNull(ConfigFrameCollector().accept("https://example.com/a-page"))
        assertNull(ConfigFrameCollector().accept(Base64.getEncoder().encodeToString("hello".toByteArray())))
    }
}
