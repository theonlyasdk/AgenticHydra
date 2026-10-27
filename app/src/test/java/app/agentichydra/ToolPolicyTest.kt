package app.agentichydra

import org.junit.Assert.*
import org.junit.Test

class ToolPolicyTest {
    @Test fun rejectsEscapingPaths() {
        listOf("../secret", "src/../../secret", "/etc/passwd", "src\\secret", "bad\u0000file").forEach { path ->
            assertTrue("Must reject $path",runCatching{ToolPolicy.parts(path)}.isFailure)
        }
    }
    @Test fun acceptsNormalRelativePathsAndRoot() {
        assertEquals(listOf("src","main.kt"),ToolPolicy.parts("./src/main.kt"))
        assertEquals(emptyList<String>(),ToolPolicy.parts(""))
    }
    @Test fun approvalModesHaveExplicitBoundaries() {
        listOf("read_file","list_files","write_file","run_command").forEach{tool->
            assertTrue(ToolPolicy.approval(ApprovalMode.EVERY,tool))
            assertFalse(ToolPolicy.approval(ApprovalMode.SESSION,tool))
        }
        assertFalse(ToolPolicy.approval(ApprovalMode.CHANGES,"read_file"))
        assertFalse(ToolPolicy.approval(ApprovalMode.CHANGES,"list_files"))
        assertTrue(ToolPolicy.approval(ApprovalMode.CHANGES,"write_file"))
        assertTrue(ToolPolicy.approval(ApprovalMode.CHANGES,"run_command"))
    }
}
