package app.agentichydra

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkModelThinkingTest {

    @Test
    fun initialDescriptionIsSetAndNotPlainUnderstandingRequest() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val work = WorkModel(app)

        val prompt = "Fix the NullPointerException in user profile"
        val expectedInitial = ThinkingMessages.initialDescription(prompt)

        assertTrue("Expected bug/fix intent sentence", expectedInitial.contains("issue") || expectedInitial.contains("fix"))
        assertNotEquals("Understanding your request…", expectedInitial)
    }

    @Test
    fun thinkingMessagesCycleSequenceIntervalIsConsistent() {
        assertEquals("Cycle interval should be 2.5s", 2500L, ThinkingMessages.CYCLE_INTERVAL_MS)
        val iterator = ThinkingMessages.shuffledSequence().iterator()
        val first = iterator.next()
        val second = iterator.next()
        val third = iterator.next()

        assertTrue(first.endsWith("…"))
        assertTrue(second.endsWith("…"))
        assertTrue(third.endsWith("…"))
        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertTrue(third.isNotBlank())
    }
}
