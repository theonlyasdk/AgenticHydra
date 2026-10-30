package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MarkdownLayoutUiTest : AppUiTestBase() {
    @Test
    fun themedHeadingsInlineCodeAndNaturalWidthTablesRenderAtLargeFontScale() {
        var dark by mutableStateOf(false)
        controller.get().setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HydraTheme(darkTheme = dark, dynamicColor = false) {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            MarkdownBody(
                                "# Project notes\n\nA line with `inline code` and normal text.\n\n## Next steps\n\nKeep the line spacing consistent.\n\n| Name | Description |\n|---|---|\n| `response_duration` | A naturally wide table column with enough text to require horizontal scrolling |\n| `response_speed` | An evenly spaced second row |",
                            )
                        }
                    }
                }
            }
        }
        val table = compose.onNodeWithTag("markdown-table")
        table.assertIsDisplayed()
        val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("The table must preserve natural width instead of fitting its columns", range.maxValue() > 0f)
        screenshot("markdown-large-light")
        table.performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        assertTrue(range.value() > 0f)
        compose.runOnIdle { dark = true }
        screenshot("markdown-large-dark-scrolled")
    }
}
