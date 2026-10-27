package app.agentichydra

import android.content.Intent
import androidx.compose.ui.test.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals

@Config(sdk=[34],qualifiers="w320dp-h915dp-420dpi")
class WorkActivityUiTest:AppUiTestBase() {
    @Test fun dedicatedTaskActivityOpensWithAComposerAndReturnsToHistory() {
        controller.pause().stop()
        val intent=Intent(controller.get(),WorkChatActivity::class.java).putExtra("newConversation",true)
        val task=Robolectric.buildActivity(WorkChatActivity::class.java,intent).setup()
        try {
            compose.onNodeWithText("What are we working on?").assertIsDisplayed()
            compose.onNodeWithText("Reply to Hydra").assertIsDisplayed()
            compose.onNodeWithContentDescription("Add attachment or change directory").performClick()
            compose.onNodeWithText("Attach file").assertIsDisplayed()
            compose.onNodeWithText("Attach media").assertIsDisplayed()
            screenshot("work-attachment-menu")
            compose.onNodeWithText("Attach file").performClick()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,org.robolectric.Shadows.shadowOf(task.get()).nextStartedActivityForResult.intent.action)
            compose.onNodeWithContentDescription("Add attachment or change directory").performClick()
            compose.onNodeWithText("Attach media").performClick()
            val media=org.robolectric.Shadows.shadowOf(task.get()).nextStartedActivityForResult.intent
            assertTrue(media.action=="android.provider.action.PICK_IMAGES"||media.action==Intent.ACTION_OPEN_DOCUMENT)
            compose.onNodeWithContentDescription("Add attachment or change directory").performClick()
            compose.onNodeWithText("Change directory").performClick()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,org.robolectric.Shadows.shadowOf(task.get()).nextStartedActivityForResult.intent.action)
            compose.onNodeWithContentDescription("Rename task").performClick()
            compose.onNodeWithText("Task name").performTextReplacement("A renamed task")
            compose.onNodeWithText("Rename",substring=false).performClick()
            assertEquals("A renamed task",(task.get().application as HydraApplication).models[WorkModel::class.java].task)
            screenshot("work-chat-activity")
            compose.onNodeWithContentDescription("Back to task history").performClick()
            assertTrue(task.get().isFinishing)
        }finally {
            task.pause().stop().destroy()
            controller.restart().start().resume().visible()
        }
    }
}
