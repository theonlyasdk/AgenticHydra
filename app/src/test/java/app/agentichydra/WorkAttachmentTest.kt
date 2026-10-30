package app.agentichydra

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkAttachmentTest {
    @Test
    fun textAndImagesSurviveHistoryWhileBinaryDataIsOnlyMaterializedForRequests() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File(app.cacheDir, "notes.txt").apply { writeText("A UTF-8 note: café") }
        val staged = WorkAttachments.stage(app, Uri.fromFile(source), false)
        val directory = File(app.filesDir, "work-attachments").apply { mkdirs() }
        val image =
            File(directory, "fixture-image").apply {
                writeBytes(
                    Base64.decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=",
                        Base64.DEFAULT,
                    ),
                )
            }
        val imageAttachment =
            MessageAttachment("image", "pixel.png", "image/png", image.absolutePath, image.length().toInt())
        val message = Message("user", "Read these", attachments = listOf(staged, imageAttachment))
        val restored = Message.fromJson(message.json())
        assertEquals(message.attachments, restored.attachments)
        assertFalse(message.json().toString().contains("base64"))
        val stored = JSONObject().put("role", "user").put("content", message.text).put("_hydra_message_id", message.id)
        val wire = WorkAttachments.apiMessage(app, stored, restored.attachments)
        assertFalse(wire.has("_hydra_message_id"))
        val parts = wire.getJSONArray("content")
        assertTrue(parts.getJSONObject(0).getString("text").contains("café"))
        assertTrue(
            parts
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/png;base64,"),
        )
        assertTrue("Preparing the request must not mutate stored history", stored.has("_hydra_message_id"))
    }

    @Test
    fun taskRenameSurvivesReopeningWithoutChangingConversationIdentity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val record =
            WorkRecord(
                "Original",
                "Complete",
                "Done",
                emptyList(),
                emptyList(),
                id = "rename-me",
                messages = listOf(Message("user", "Start")),
            )
        app
            .getSharedPreferences(
                "work",
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .putString("records", JSONArray().put(record.json()).toString())
            .commit()
        val model = WorkModel(app)
        assertTrue(model.viewRecord(model.records.single()))
        model.renameConversation(record.id, "  A better task name  ")
        val restored = WorkModel(app).records.single()
        assertEquals(record.id, restored.id)
        assertEquals("A better task name", restored.title)
        assertEquals("A better task name", model.task)
    }
}
