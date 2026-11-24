package app.agentichydra

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryPersistenceTest {
    private lateinit var app: Application

    @Before
    fun seed() {
        app = ApplicationProvider.getApplicationContext()
        val history =
            JSONArray().put(
                JSONObject()
                    .put("id", "chat-1")
                    .put("title", "Original")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "First prompt"))),
            )
        app
            .getSharedPreferences("hydra", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("provider", "GEMINI")
            .putString("history", history.toString())
            .commit()
        val record =
            JSONObject()
                .put("title", "Task")
                .put("status", "Completed")
                .put("summary", "Done")
                .put("steps", JSONArray())
                .put("changes", JSONArray())
        app
            .getSharedPreferences("work", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("records", JSONArray().put(record).put(record).toString())
            .commit()
    }

    @Test
    fun renamedTitleSurvivesActiveChatCheckpointsAndReload() {
        val model = HydraModel(app)
        model.open(model.conversations.single())
        model.renameConversation("chat-1", "  My renamed chat  ")
        model.stop()
        assertEquals("My renamed chat", HydraModel(app).conversations.single().title)
        model.renameConversation("chat-1", "   ")
        assertEquals("My renamed chat", model.conversations.single().title)
    }

    @Test
    fun deletingOpenConversationCannotBeRestoredByStopOrReload() {
        val model = HydraModel(app)
        model.open(model.conversations.single())
        model.deleteConversation("chat-1")
        model.stop()
        assertTrue(model.messages.isEmpty())
        assertNotEquals("chat-1", model.activeId)
        assertTrue(HydraModel(app).conversations.isEmpty())
    }

    @Test
    fun deletingAnIdenticalWorkRecordRemovesOnlyThatEntryAndPersists() {
        val model = WorkModel(app)
        await { !model.recordsLoading }
        model.deleteRecord(model.records.last())
        assertEquals(1, model.records.size)
        val prefs = app.getSharedPreferences("work", Context.MODE_PRIVATE)
        await { JSONArray(prefs.getString("records", "[]")).length() == 1 }
        val reopened = WorkModel(app)
        await { !reopened.recordsLoading }
        assertEquals(1, reopened.records.size)
        model.deleteRecord(model.records.single())
        await { JSONArray(prefs.getString("records", "[]")).length() == 0 }
        val empty = WorkModel(app)
        await { !empty.recordsLoading }
        assertTrue(empty.records.isEmpty())
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue(ready())
    }

    @Test
    fun exportIncludesFullConversationWithoutUiTruncation() {
        val conversation =
            Conversation(
                "export",
                "A title",
                listOf(Message("user", "Question"), Message("assistant", "```kotlin\nval x = 1\n```")),
            )
        assertEquals(
            "# A title\n\n## You\n\nQuestion\n\n## Hydra\n\n```kotlin\nval x = 1\n```\n",
            conversation.exportMarkdown(),
        )
    }
}
