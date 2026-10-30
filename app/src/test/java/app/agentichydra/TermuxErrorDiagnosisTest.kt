package app.agentichydra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxErrorDiagnosisTest {
    @Test
    fun backgroundServiceBlockIsDiagnosedAsOffline() {
        val raw =
            "Not allowed to start service Intent { act=com.termux.RUN_COMMAND xflg=0x4 " +
                "cmp=com.termux/app.RunCommandService (has extras) }: app is in background uid null"
        val issue = diagnoseTermuxFailure(raw)
        requireNotNull(issue)
        assertEquals("Termux is offline", issue.title)
        assertEquals(TermuxIssueAction.OPEN_TERMUX, issue.action)
        assertTrue(issue.description.contains("background", true))
    }

    @Test
    fun allowExternalAppsViolationPointsAtTheSetupGuide() {
        val raw =
            "RUN_COMMAND Execution Intent Command requires `allow-external-apps` property to be set to " +
                "`true` in `/data/data/com.termux/files/home/.termux/termux.properties` file."
        val issue = diagnoseTermuxFailure(raw)
        requireNotNull(issue)
        assertEquals("External commands are disabled", issue.title)
        assertNull(issue.action)
    }

    @Test
    fun genericTermuxRejectionFallsBackToTheRawMessage() {
        val raw =
            "Termux could not execute this command (error 4). Check its Run Commands permission and " +
                "allow-external-apps setting."
        assertNull(diagnoseTermuxFailure(raw))
    }

    @Test
    fun timeoutsAndMissingBootstrapGetTheirOwnGuidance() {
        val timeout =
            diagnoseTermuxFailure(
                "Termux did not return a result within three minutes. The command may still be running; check Termux before retrying.",
            )
        assertEquals("Termux never answered", timeout?.title)
        assertEquals(TermuxIssueAction.OPEN_TERMUX, timeout?.action)
        val bootstrap = diagnoseTermuxFailure("File \"/data/data/com.termux/files/usr/bin/bash\" does not exist")
        assertEquals("Termux tools are missing", bootstrap?.title)
        assertEquals(TermuxIssueAction.INSTALL_GUIDE, bootstrap?.action)
    }

    @Test
    fun unknownFailuresAreNotInvented() {
        assertNull(diagnoseTermuxFailure("java.net.SocketTimeoutException: connect timed out"))
    }
}
