package app.agentichydra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageLedgerTest {
    @Test
    fun aggregatesTokenCountsAndRetainsMissingUsageSeparately() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val task = UsageTask(context, "task-id", "Review")
        UsageLedger.record(task, Provider.CUSTOM, "model", JSONObject("""{"usage":{"prompt_tokens":900,"completion_tokens":200}}"""))
        UsageLedger.record(task.copy(title = "Review notes"), Provider.CUSTOM, "model", JSONObject("""{"usage":{"input_tokens":100,"output_tokens":50}}"""))
        UsageLedger.record(task.copy(title = "Review notes"), Provider.CUSTOM, "model", JSONObject())
        UsageLedger.record(task, Provider.NIM, "model", JSONObject("""{"usage":{"prompt_tokens":20}}"""))

        val ledger = UsageLedger(context)
        try {
            val rows = ledger.read().filter { it.taskId == task.id }
            val custom = rows.single { it.model.provider == Provider.CUSTOM.name }
            val nim = rows.single { it.model.provider == Provider.NIM.name }
            assertEquals("Review notes", custom.task)
            assertEquals(1000L, custom.input)
            assertEquals(250L, custom.output)
            assertEquals(1L, custom.unreported)
            assertEquals(20L, nim.input)
            assertEquals(1L, nim.unreported)

            val rates = TokenPrices(BigDecimal("2"), BigDecimal("8"))
            ledger.setPrices(custom.model, rates)
            assertEquals(0, BigDecimal("0.004").compareTo(ledger.prices(custom.model)!!.estimate(custom.input, custom.output)))
            assertNull(ledger.prices(nim.model))
            ledger.setPrices(custom.model, null)
            assertNull(ledger.prices(custom.model))
        } finally {
            ledger.close()
        }
    }
}
