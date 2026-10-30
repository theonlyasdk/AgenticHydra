package app.agentichydra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
abstract class AppUiTestBase {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var server: MockWebServer
    protected lateinit var controller: ActivityController<MainActivity>

    @Before
    fun launch() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse()
                        .setHeader(
                            "Content-Type",
                            "application/json",
                        ).setBody(
                            """{"data":[{"id":"test/alpha","owned_by":"test"},{"id":"test/beta","owned_by":"test"},{"id":"openai/gpt-oss-20b","owned_by":"openai"}]}""",
                        )
            }
        server.start()
        val app = ApplicationProvider.getApplicationContext<Context>()
        app
            .getSharedPreferences(
                "hydra",
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .putString("provider", "CUSTOM")
            .putString("endpoint", server.url("/v1").toString().trimEnd('/'))
            .commit()
        app
            .getSharedPreferences("work", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        (controller.get().application as HydraApplication).models[HydraModel::class.java].configure(
            Provider.CUSTOM,
            server.url("/v1").toString().trimEnd('/'),
            "",
            "",
        )
        compose.waitForIdle()
    }

    @After
    fun close() {
        (controller.get().application as HydraApplication).viewModelStore.clear()
        controller.get().viewModelStore.clear()
        controller.pause().stop().destroy()
        server.shutdown()
    }

    protected fun screenshot(name: String) {
        compose.waitForIdle()
        // PixelCopy waits on a hardware compositor unavailable in Robolectric.
        // Draw the top attached window (including modal sheets) using native graphics.
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
        val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val views = field.get(global) as List<android.view.View>
        val view = views.lastOrNull() ?: controller.get().window.decorView
        val bitmap =
            Bitmap.createBitmap(
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        view.draw(Canvas(bitmap))
        val file = File("build/reports/screenshots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

class ChatUiTest : AppUiTestBase() {
    @Test
    fun composerModelSearchFiltersAndSelects() {
        compose.onNodeWithTag("chat-welcome").assertIsDisplayed()
        compose.onNodeWithText("A little intelligence. A human in control.").assertDoesNotExist()
        screenshot("chat-light")
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search models").performTextInput("beta")
        compose.onNodeWithText("alpha").assertDoesNotExist()
        compose.onNode(hasText("beta") and hasText("test/beta")).performClick()
        screenshot("models-light")
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Search models").assertDoesNotExist()
        compose.onNodeWithText("beta").assertIsDisplayed()
    }
}

class WorkUiTest : AppUiTestBase() {
    @Test
    fun workAndUserNavigationOpenStructuredScreens() {
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("No tasks yet").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        screenshot("work-light")
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        val taskIntent =
            org.robolectric.Shadows
                .shadowOf(controller.get())
                .nextStartedActivity
        org.junit.Assert.assertEquals(WorkChatActivity::class.java.name, taskIntent.component?.className)
        org.junit.Assert.assertTrue(taskIntent.getBooleanExtra("newConversation", false))
        compose.onNodeWithText("User").performClick()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        compose.onNodeWithText("Tool approvals").assertIsDisplayed()
        screenshot("user-light")
    }
}

// Robolectric loops measuring dialog text fields with width qualifiers above 320dp:
// https://github.com/robolectric/robolectric/issues/8460
@Config(sdk = [34], qualifiers = "w320dp-h915dp-420dpi")
class ChatHistoryUiTest : AppUiTestBase() {
    @Test
    fun longPressRenamesAndDeletesConversation() {
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("hydra", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString(
                "history",
                """[{"id":"history-chat","title":"Activity recommendations","messages":[{"role":"user","text":"Original prompt"}]}]""",
            ).commit()
        val model = HydraModel(activity.application)
        activity.setContent { HydraTheme { ChatScreen(model) } }
        compose.onNodeWithContentDescription("Open chat history").performClick()
        compose.onNodeWithText("Activity recommendations").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Export").assertIsDisplayed()
        screenshot("history-menu-light")
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Conversation name").performTextReplacement("A new name")
        screenshot("history-rename-light")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("A new name").assertIsDisplayed()
        compose.onNodeWithText("A new name").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete conversation?").assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.onNodeWithText("A new name", substring = false).assertDoesNotExist()
        org.junit.Assert.assertTrue(model.conversations.isEmpty())
    }
}

class WorkHistoryUiTest : AppUiTestBase() {
    @Test
    fun workHistoryDeletesWithoutRemovingProjectFiles() {
        val activity = controller.get()
        activity
            .getSharedPreferences("work", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "records",
                """[{"title":"Inspect project","status":"Completed","summary":"Reviewed","steps":[],"changes":[]}]""",
            ).commit()
        val work = WorkModel(activity.application)
        val chat = (activity.application as HydraApplication).models[HydraModel::class.java]
        activity.setContent { HydraTheme { WorkScreen(chat, work) } }
        compose.onNodeWithText("Inspect project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.onNodeWithText("Export conversation").assertIsDisplayed()
        screenshot("work-history-options")
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("No tasks yet").assertIsDisplayed()
        org.junit.Assert.assertTrue(work.records.isEmpty())
    }
}

class ModelSheetMotionUiTest : AppUiTestBase() {
    @Test
    fun manualEntrySlidesAndDoneCommitsWithoutRedundantCloseButton() {
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Close models").assertDoesNotExist()
        compose.onNodeWithText("Enter model ID").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Model ID", substring = false).performTextInput("my/manual-model")
        compose.onNodeWithContentDescription("Use model ID").performClick()
        compose.mainClock.advanceTimeBy(1000)
        // The manual ID stays a draft; the live model is untouched until Done.
        org.junit.Assert.assertEquals(
            "",
            (controller.get().application as HydraApplication).models[HydraModel::class.java].model,
        )
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertEquals(
            "my/manual-model",
            (controller.get().application as HydraApplication).models[HydraModel::class.java].model,
        )
        compose.onNodeWithText("Search models").assertDoesNotExist()
    }
}

class ReasoningUiTest : AppUiTestBase() {
    @Test
    fun darkReasoningSliderDraftsSelectionAndDoneAppliesIt() {
        controller.get().setContent { HydraTheme(darkTheme = true) { HydraApp() } }
        compose.waitForIdle()
        screenshot("chat-dark")
        compose.onNodeWithText("Choose model").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("gpt-oss-20b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("gpt-oss-20b").performClick()
        compose.waitForIdle()
        compose
            .onNode(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo),
            ).performSemanticsAction(SemanticsActions.SetProgress) {
                it(3f)
            }
        compose.waitForIdle()
        val vm = (controller.get().application as HydraApplication).models[HydraModel::class.java]
        // Draft only: choosing effort does not rewrite the live setting before Done.
        org.junit.Assert.assertEquals("auto", vm.effort)
        screenshot("reasoning-dark")
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertEquals("high", vm.effort)
        compose.onNodeWithText("gpt-oss-20b").performClick()
        compose.waitForIdle()
        val slider = compose.onNodeWithTag("reasoning-control")
        slider.performTouchInput { swipe(centerRight, centerLeft) }
        compose.waitForIdle()
        org.junit.Assert.assertEquals("high", vm.effort)
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertEquals("auto", vm.effort)
        compose.onNodeWithText("gpt-oss-20b").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reasoning-control").performTouchInput { swipe(centerLeft, centerRight) }
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertEquals("high", vm.effort)
        compose.onNodeWithText("gpt-oss-20b").performClick()
        compose.waitForIdle()
        val pillBounds = compose.onNodeWithTag("reasoning-pill").fetchSemanticsNode().boundsInRoot
        val controlBounds = slider.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(
            "High pill must reach the final segment",
            pillBounds.center.x > controlBounds.left + controlBounds.width * .75f,
        )
        compose.onNodeWithText("Reasoning", substring = false).assertDoesNotExist()
        screenshot("reasoning-dark")
    }
}

class MarkdownUiTest : AppUiTestBase() {
    @Test
    fun markdownRendersRichContentAndCopyableCode() {
        controller.get().setContent {
            HydraTheme {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MarkdownBody(
                            "# Results\n\n**Three tests passed.** See [documentation](https://example.com).\n\n- First item\n- Second item\n\n| Check | Result |\n|---|---|\n| Build | Passed |\n\n```kotlin\nval answer = 42\nprintln(answer)\n```",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        screenshot("markdown-light")
    }
}
