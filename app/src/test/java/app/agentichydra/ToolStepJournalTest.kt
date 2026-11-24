package app.agentichydra

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolStepJournalTest {
    @Test
    fun interruptedExternalStepIsReportedAndCompletedStepIsIgnored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ToolStepJournal.begin(context, "pending-command", "run_command", null))
        assertNotNull(ToolStepJournal.begin(context, "committed-command", "run_command", null))
        assertNull(ToolStepJournal.begin(context, "read-only", "read_file", null))

        val recovery = ToolStepJournal.recoverAll(context, "committed-command").single()
        assertEquals("pending-command", recovery.step)
        assertTrue(recovery.needsReview)
        assertTrue(recovery.message.contains("before retrying"))
        assertTrue(ToolStepJournal.recoverAll(context, null).isEmpty())
    }

    @Test
    fun partialWriteRollsBackButUnrelatedChangesArePreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = createTempDirectory(context.cacheDir.toPath(), "recovery").toFile()
        val target = File(directory, "notes.txt")
        val root = DocumentFile.fromFile(directory)
        val project = Uri.fromFile(directory).toString()

        target.writeText("original text")
        val partial = checkNotNull(ToolStepJournal.begin(context, "partial", "write_file", project))
        partial.prepareWrite("notes.txt", DocumentFile.fromFile(target), "next text")
        target.writeText("next")
        assertFalse(checkNotNull(partial.recover { root }).needsReview)
        assertEquals("original text", target.readText())

        val conflict = checkNotNull(ToolStepJournal.begin(context, "conflict", "write_file", project))
        conflict.prepareWrite("notes.txt", DocumentFile.fromFile(target), "next text")
        target.writeText("an independent change")
        assertTrue(checkNotNull(conflict.recover { root }).needsReview)
        assertEquals("an independent change", target.readText())
    }
}
