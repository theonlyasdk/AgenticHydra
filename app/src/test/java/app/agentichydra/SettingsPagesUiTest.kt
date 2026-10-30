package app.agentichydra

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

class SettingsPagesUiTest : AppUiTestBase() {
    @Test
    fun versionOpensDedicatedAboutActivityWithBackAndCredits() {
        compose.onNodeWithText("User", substring = false).performClick()
        compose.onNodeWithText("Version", substring = false).performScrollTo().performClick()
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
        assertEquals("about", intent.getStringExtra("page"))
        controller.pause().stop()
        val page = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        try {
            compose.onNodeWithText("theonlyasdk", substring = false).assertIsDisplayed()
            compose.onNodeWithText("© 2026", substring = false).assertIsDisplayed()
            screenshot("about-light")
            page.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    HydraTheme(darkTheme = true, dynamicColor = false) { SettingsPage("about") { page.get().finish() } }
                }
            }
            compose.onNodeWithText("theonlyasdk", substring = false).assertIsDisplayed()
            compose.onNodeWithText("© 2026", substring = false).assertIsDisplayed()
            screenshot("about-large-dark")
            compose.onNodeWithContentDescription("Back").performClick()
            assertTrue(page.get().isFinishing)
        } finally {
            page.pause().stop().destroy()
            controller
                .restart()
                .start()
                .resume()
                .visible()
        }
    }
}
