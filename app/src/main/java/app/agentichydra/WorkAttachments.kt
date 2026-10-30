package app.agentichydra

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class MessageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val bytes: Int,
) {
    val isImage get() = mimeType.startsWith("image/")

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("mime", mimeType)
            .put("localPath", localPath)
            .put("bytes", bytes)

    companion object {
        fun fromJson(o: JSONObject) =
            MessageAttachment(
                o.getString("id"),
                o.getString("name"),
                o.getString("mime"),
                o.getString("localPath"),
                o.optInt("bytes"),
            )
    }
}

object WorkAttachments {
    private val imageTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    private fun directory(context: Context) = File(context.filesDir, "work-attachments").apply { mkdirs() }

    private fun file(
        context: Context,
        attachment: MessageAttachment,
    ): File {
        val file = File(attachment.localPath).canonicalFile
        require(file.parentFile == directory(context).canonicalFile) { "Attachment path is invalid." }
        return file
    }

    fun stage(
        context: Context,
        uri: Uri,
        media: Boolean,
    ): MessageAttachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: if (media) "image/jpeg" else "text/plain"
        require(!media || mime in imageTypes) { "Choose a JPEG, PNG, WebP, or GIF image." }
        val name =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
                ?.replace(
                    Regex("[\\r\\n]"),
                    " ",
                )?.take(160) ?: uri.lastPathSegment?.substringAfterLast('/') ?: if (media) "Image" else "Attachment"
        val limit = if (media) 4 * 1024 * 1024 else 128 * 1024
        val data =
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(limit + 1)
                var length = 0
                while (length < buffer.size) {
                    val count = input.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    length += count
                }
                require(
                    length <= limit,
                ) {
                    if (media) {
                        "Choose an image smaller than 4 MB."
                    } else {
                        "Choose a text or code file smaller than 128 KB."
                    }
                }
                buffer.copyOf(length)
            } ?: error("Couldn’t open this attachment.")
        require(data.isNotEmpty()) { "This file is empty." }
        if (!media) decodeText(data)
        val id = UUID.randomUUID().toString()
        val target = File(directory(context), id)
        target.writeBytes(data)
        return MessageAttachment(id, name, if (media) mime else "text/plain", target.absolutePath, data.size)
    }

    private fun decodeText(data: ByteArray): String {
        require(!data.contains(0.toByte())) { "Choose a UTF-8 text or code file." }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (
            _: Exception,
        ) {
            error("Choose a UTF-8 text or code file.")
        }
    }

    fun remove(
        context: Context,
        attachment: MessageAttachment,
    ) {
        runCatching { file(context, attachment).delete() }
    }

    /** Materialize files only for the network request, keeping base64 out of saved history. */
    fun apiMessage(
        context: Context,
        message: JSONObject,
        attachments: List<MessageAttachment>,
    ): JSONObject {
        if (!message.has("_hydra_message_id")) return message
        val copy = JSONObject(message.toString())
        copy.remove("_hydra_message_id")
        val content =
            buildString {
                append(copy.optString("content"))
                attachments.filterNot { it.isImage }.forEach { attachment ->
                    append("\n\nAttached file: ").append(attachment.name).append("\n```\n")
                    append(decodeText(file(context, attachment).readBytes())).append("\n```")
                }
            }
        val images = attachments.filter { it.isImage }
        if (images.isEmpty()) {
            copy.put("content", content)
        } else {
            val parts =
                JSONArray().put(
                    JSONObject()
                        .put(
                            "type",
                            "text",
                        ).put("text", content.ifBlank { "Please inspect the attached image." }),
                )
            images.forEach { attachment ->
                val bytes = file(context, attachment).readBytes()
                require(bytes.size <= 4 * 1024 * 1024) { "Image exceeds the attachment limit." }
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:${attachment.mimeType};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                        ),
                    ),
                )
            }
            copy.put("content", parts)
        }
        return copy
    }
}
