package app.agentichydra

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

internal data class UsageTask(val context: Context, val id: String, val title: String)

internal data class UsageModel(val provider: String, val id: String) {
    val label get() = "${Provider.entries.firstOrNull { it.name == provider }?.label ?: provider} / $id"
}

internal data class TokenPrices(val input: BigDecimal, val output: BigDecimal) {
    fun estimate(inputTokens: Long, outputTokens: Long): BigDecimal =
        (input * inputTokens.toBigDecimal() + output * outputTokens.toBigDecimal()).movePointLeft(6)
}

internal data class UsageRow(
    val day: String,
    val taskId: String,
    val task: String,
    val model: UsageModel,
    val input: Long,
    val output: Long,
    val unreported: Long,
)

internal class UsageLedger(context: Context) {
    private val database = Database(context.applicationContext)
    private val preferences = context.getSharedPreferences("usage_prices", Context.MODE_PRIVATE)

    fun prices(model: UsageModel): TokenPrices? {
        val value = preferences.getString(priceKey(model), null) ?: return null
        val fields = value.split('|', limit = 2)
        return TokenPrices(fields[0].toBigDecimal(), fields[1].toBigDecimal())
    }

    fun setPrices(model: UsageModel, prices: TokenPrices?) {
        preferences.edit {
            if (prices == null) remove(priceKey(model))
            else putString(priceKey(model), "${prices.input.toPlainString()}|${prices.output.toPlainString()}")
        }
    }

    private fun priceKey(model: UsageModel) = "${model.provider}:${model.id}"

    fun read(): List<UsageRow> =
        database.readableDatabase.rawQuery(
            """
            SELECT day, task_id, task_title, provider, model,
                SUM(input_tokens), SUM(output_tokens), SUM(unreported), MAX(rowid)
            FROM requests
            GROUP BY day, task_id, provider, model
            ORDER BY day DESC, MAX(rowid) DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        UsageRow(
                            cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            UsageModel(cursor.getString(3), cursor.getString(4)),
                            cursor.getLong(5), cursor.getLong(6), cursor.getLong(7),
                        ),
                    )
                }
            }
        }

    fun close() = database.close()

    private class Database(context: Context) : SQLiteOpenHelper(context, "model_usage.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE requests (
                    day TEXT NOT NULL, task_id TEXT NOT NULL, task_title TEXT NOT NULL,
                    provider TEXT NOT NULL, model TEXT NOT NULL,
                    input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL, unreported INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        suspend fun record(task: UsageTask?, provider: Provider, model: String, response: JSONObject) {
            if (task == null || model.isBlank()) return
            val usage = response.optJSONObject("usage")
            val input = usage.tokenCount("prompt_tokens", "input_tokens")
            val output = usage.tokenCount("completion_tokens", "output_tokens")
            val values = ContentValues().apply {
                put("day", LocalDate.now().toString())
                put("task_id", task.id)
                put("task_title", task.title)
                put("provider", provider.name)
                put("model", model)
                put("input_tokens", input ?: 0L)
                put("output_tokens", output ?: 0L)
                put("unreported", if (input == null || output == null) 1L else 0L)
            }
            withContext(Dispatchers.IO) {
                try {
                    Database(task.context.applicationContext).use {
                        it.writableDatabase.insertOrThrow("requests", null, values)
                    }
                } catch (e: android.database.SQLException) {
                    Log.w("HydraUsage", "Could not record model usage", e)
                }
            }
        }

        private fun JSONObject?.tokenCount(vararg names: String): Long? =
            names.firstNotNullOfOrNull { name -> this?.optLong(name, -1)?.takeIf { it >= 0 } }
    }
}
