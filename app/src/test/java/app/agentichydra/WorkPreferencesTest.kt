package app.agentichydra

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WorkPreferencesTest {
    @Test fun fullAccessIsRememberedButOneTurnApprovalDoesNotSurviveRestart() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("work",Context.MODE_PRIVATE).edit().clear().commit()
        WorkModel(app).mode=ApprovalMode.FULL
        assertEquals(ApprovalMode.FULL,WorkModel(app).mode)
        WorkModel(app).mode=ApprovalMode.EVERY
        assertEquals(ApprovalMode.EVERY,WorkModel(app).mode)
        WorkModel(app).mode=ApprovalMode.SESSION
        assertEquals(ApprovalMode.CHANGES,WorkModel(app).mode)
    }

    @Test fun savedTasksKeepTheirFolderAndNewTasksReuseLastChosenFolder() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val a=WorkRecord("Project A","Complete","",emptyList(),emptyList(),projectUri="content://projects/a",projectName="A",directory="/storage/emulated/0/A")
        val b=WorkRecord("Project B","Complete","",emptyList(),emptyList(),projectUri="content://projects/b",projectName="B",directory="/storage/emulated/0/B")
        app.getSharedPreferences("work",Context.MODE_PRIVATE).edit().clear()
            .putString("project",b.projectUri).putString("projectName",b.projectName).putString("directory",b.directory)
            .putString("records",JSONArray().put(a.json()).put(b.json()).toString()).commit()
        val work=WorkModel(app)
        assertTrue(work.viewRecord(work.records.first{it.id==a.id}))
        assertEquals(a.projectUri,work.projectUri)
        assertTrue(work.newThread())
        assertEquals(b.projectUri,work.projectUri)
        assertEquals(b.directory,work.directory)
        assertEquals(a.projectUri,work.records.first{it.id==a.id}.projectUri)
    }

    @Test fun listingAndActivityLabelsStayReadableWithoutExposingWholeCommands() {
        assertEquals("src/       README.md",formatProjectListing(listOf("src" to true,"README.md" to false)))
        assertEquals("Directory is empty.",formatProjectListing(emptyList()))
        assertEquals("Review project changes",commandActivityLabel("git diff --no-ext-diff -- app/src/main"))
        assertEquals("Run a project command",commandActivityLabel("curl https://example.com/very/long/path"))
    }
}
