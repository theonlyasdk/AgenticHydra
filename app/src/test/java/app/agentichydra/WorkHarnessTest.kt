package app.agentichydra

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WorkHarnessTest {
    @Test fun fileToolsPreserveContentAndEnforceProjectBoundaries() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"project-tools").apply{mkdirs()}
        val tools=ProjectTools(context,DocumentFile.fromFile(root))
        fun run(name:String,json:String)=tools.execute(name,JSONObject(json))
        run("create_directory","""{"path":"src"}""")
        run("write_file","""{"path":"src/main.kt","content":"old old\n"}""")
        assertTrue(runCatching{run("replace_in_file","""{"path":"src/main.kt","old_text":"old","new_text":"new"}""")}.isFailure)
        assertEquals("old old\n",tools.read("src/main.kt"))
        run("replace_in_file","""{"path":"src/main.kt","old_text":"old","new_text":"new","replace_all":true}""")
        run("append_file","""{"path":"src/main.kt","content":"line two"}""")
        assertTrue(run("search_files","""{"path":"","query":"line two"}""").output.contains("src/main.kt:2:"))
        assertTrue(run("find_files","""{"path":"","pattern":"*.kt"}""").output.contains("src/main.kt"))
        run("copy_file","""{"source":"src/main.kt","destination":"copy.kt"}""")
        assertTrue(runCatching{run("copy_file","""{"source":"src/main.kt","destination":"copy.kt"}""")}.isFailure)
        run("move_file","""{"source":"copy.kt","destination":"moved.kt"}""")
        assertFalse(File(root,"copy.kt").exists())
        assertEquals(tools.read("src/main.kt"),tools.read("moved.kt"))
        assertTrue(run("file_info","""{"path":"moved.kt"}""").output.contains("bytes"))
        run("delete_file","""{"path":"moved.kt"}""")
        assertFalse(File(root,"moved.kt").exists())
        assertTrue(runCatching{run("delete_file","""{"path":"src"}""")}.isFailure)
        assertTrue(runCatching{run("write_file","""{"path":"../escape.txt","content":"bad"}""")}.isFailure)
    }

    @Test fun interruptedToolCallsArePairedBeforeTheNextUserTurn() {
        val history=JSONArray("""[{"role":"user","content":"Inspect"},{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"read_file","arguments":"{}"}},{"id":"b","type":"function","function":{"name":"read_file","arguments":"{}"}}]},{"role":"tool","tool_call_id":"a","content":"Read complete"}]""")
        val repaired=repairedWorkHistory(history.toString())
        assertEquals(4,repaired.length())
        assertEquals("b",repaired.getJSONObject(3).getString("tool_call_id"))
        assertEquals(repaired.toString(),repairedWorkHistory(repaired.toString()).toString())
        assertEquals(48,WorkTools.schema().length())
        assertEquals(48,WorkTools.names.distinct().size)
        listOf("append_file","replace_in_file","delete_file","move_file","copy_file","create_directory","git_status","git_diff").forEach{assertTrue(ToolPolicy.approval(ApprovalMode.CHANGES,it))}
    }

    @Test fun searchParsesSourceLinksAndWebAccessRejectsPrivateAddresses() {
        val parsed=parseSearchHtml("""<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fguide">Android guide</a><div class="result__snippet">Official documentation</div></div>""")
        assertTrue(parsed.contains("https://developer.android.com/guide"))
        assertTrue(parsed.contains("Official documentation"))
        listOf("127.0.0.1","192.168.1.2","10.0.0.1","169.254.169.254","100.64.0.1","::1","fd00::1").forEach{assertFalse(it,isPublicAddress(InetAddress.getByName(it)))}
        assertTrue(isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
}
