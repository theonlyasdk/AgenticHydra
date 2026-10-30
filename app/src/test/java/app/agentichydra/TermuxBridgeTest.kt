package app.agentichydra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxBridgeTest {
    @Test
    fun resultOkMinusOneIsSuccessfulAndPreservesTheActualProcessExitCode() {
        val result =
            Bundle().apply {
                putInt("err", Activity.RESULT_OK)
                putInt("exitCode", 0)
                putString("stdout", "Termux round trip")
                putString("stderr", "")
            }
        val deferred = CompletableDeferred<TermuxResult>()
        TermuxBridge.pending["success-fixture"] = deferred
        TermuxResultReceiver().onReceive(
            ApplicationProvider.getApplicationContext<Context>(),
            Intent("app.agentichydra.RESULT.success-fixture").putExtra("result", result),
        )
        val parsed = runBlocking { deferred.await() }
        assertEquals(0, parsed.exitCode)
        assertEquals("Termux round trip", parsed.stdout)
        assertTrue(parsed.render().startsWith("Exit code: 0"))
        assertFalse(TermuxBridge.pending.containsKey("success-fixture"))
        result.putInt("exitCode", 7)
        result.putString("stderr", "A real command failure")
        assertEquals(7, parseTermuxResult(result).exitCode)
    }

    @Test
    fun internalErrorsAndMissingExitCodesAreNotReportedAsSuccessfulCommands() {
        val denied =
            Bundle().apply {
                putInt("err", 0)
                putString("errmsg", "External commands are disabled")
            }
        val failure = runCatching { parseTermuxResult(denied) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("External commands are disabled", failure?.message)
        assertTrue(runCatching { parseTermuxResult(Bundle().apply { putInt("err", Activity.RESULT_OK) }) }.isFailure)
    }
}
