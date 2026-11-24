package app.agentichydra

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

/** Durable intent for a Work step. Opaque tools are recorded for review and never replayed. */
class ToolStepJournal private constructor(
    private val context: Context,
    private val file: AtomicFile,
    private val entry: JSONObject,
) {
    fun prepareWrite(path: String, existing: DocumentFile?, text: String) {
        val before = existing?.let(::readBytes)
        entry.put("path", path)
            .put("uri", existing?.uri?.toString() ?: JSONObject.NULL)
            .put("before", before?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
            .put("after", text)
        persist()
    }

    fun identifyFile(document: DocumentFile) {
        if (entry.isNull("uri")) {
            entry.put("uri", document.uri.toString())
            persist()
        }
    }

    fun complete() = file.delete()

    private fun persist() {
        val stream = file.startWrite()
        try {
            stream.write(entry.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun readBytes(document: DocumentFile): ByteArray {
        val bytes = ByteArray(262145)
        val count = context.contentResolver.openInputStream(document.uri)?.use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read == -1) break
                offset += read
            }
            offset
        } ?: error("Could not read the recovery file.")
        require(count < bytes.size) { "The file exceeds the rollback limit." }
        return bytes.copyOf(count)
    }

    /** Restores only the same document and only when its bytes match a journaled write. */
    internal fun recover(
        resolveProject: (Uri) -> DocumentFile? = { DocumentFile.fromTreeUri(context, it) },
    ): Recovery? {
        val step = entry.getString("step")
        val tool = entry.getString("tool")
        if (!entry.has("path")) {
            val review = entry.optBoolean("review")
            complete()
            return if (review) Recovery(step, "$tool may have made changes. Check its result before retrying.", true) else null
        }
        val path = entry.getString("path")
        try {
            val root = resolveProject(Uri.parse(entry.getString("project")))
            check(root?.canRead() == true && root.canWrite())
            var target: DocumentFile? = root
            ToolPolicy.parts(path).forEach { part -> target = target?.findFile(part) }
            val document = target
            val hadFile = !entry.isNull("before")
            if (document == null && !hadFile) {
                complete()
                return Recovery(step, "No partial file remained at $path.", false)
            }
            check(document != null && document.isFile && document.uri.toString() == entry.optString("uri"))
            val current = readBytes(document)
            val before = if (hadFile) Base64.decode(entry.getString("before"), Base64.NO_WRAP) else null
            if (before != null && current.contentEquals(before)) {
                complete()
                return Recovery(step, "$path was unchanged.", false)
            }
            val expected = if (entry.optBoolean("restoring")) {
                checkNotNull(before)
            } else {
                entry.getString("after").toByteArray(Charsets.UTF_8)
            }
            check(current.isPrefixOf(expected))
            if (before == null) {
                check(document.delete())
            } else {
                entry.put("restoring", true)
                persist()
                context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(before) }
                    ?: error("Could not restore the file.")
                check(readBytes(document).contentEquals(before))
            }
            complete()
            return Recovery(step, "Rolled back the interrupted write to $path.", false)
        } catch (_: Exception) {
            return Recovery(step, "Could not safely roll back $path. Check the file before retrying.", true)
        }
    }

    internal data class Recovery(val step: String, val message: String, val needsReview: Boolean)

    companion object {
        private val reversible = setOf("write_file", "append_file", "replace_in_file", "apply_diff", "copy_file")
        private val review = setOf("move_file", "delete_file", "create_directory", "download_file", "run_command")

        private fun directory(context: Context) = File(context.filesDir, "tool-journal")

        internal fun begin(context: Context, step: String, tool: String, project: String?): ToolStepJournal? {
            val needsReview = tool in review || (tool.startsWith("mcp_") && !ToolPolicy.isRead(tool))
            if (tool !in reversible && !needsReview) return null
            val directory = directory(context)
            check(directory.isDirectory || directory.mkdirs()) { "Could not open the tool journal." }
            val file = AtomicFile(File(directory, "$step.json"))
            val entry = JSONObject().put("step", step).put("tool", tool).put("project", project)
                .put("review", needsReview)
            return ToolStepJournal(context, file, entry).also { it.persist() }
        }

        internal fun recoverAll(context: Context, committedStep: String?): List<Recovery> =
            directory(context).listFiles().orEmpty()
                .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
                .map { File(it.parentFile, it.name.removeSuffix(".bak")) }
                .distinct()
                .mapNotNull { path ->
                    val file = AtomicFile(path)
                    try {
                        if (path.nameWithoutExtension == committedStep) {
                            file.delete()
                            null
                        } else {
                            ToolStepJournal(context, file, JSONObject(String(file.readFully(), Charsets.UTF_8))).recover()
                        }
                    } catch (_: Exception) {
                        Recovery(path.nameWithoutExtension, "Could not read an interrupted tool's recovery record.", true)
                    }
                }
    }
}

private fun ByteArray.isPrefixOf(other: ByteArray): Boolean =
    size <= other.size && indices.all { this[it] == other[it] }
