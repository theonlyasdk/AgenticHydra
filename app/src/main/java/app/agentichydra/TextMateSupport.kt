package app.agentichydra

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

internal object TextMateSupport {
    private var initialized = false
    private var currentDark: Boolean? = null

    @Synchronized
    fun prepare(
        context: Context,
        dark: Boolean,
    ) {
        if (initialized && currentDark == dark) return

        val files = FileProviderRegistry.getInstance()
        if (!initialized) files.addFileProvider(AssetsFileResolver(context.applicationContext.assets))

        val name = if (dark) "darcula" else "quietlight"
        val path = "textmate/$name.json"
        val stream = files.tryGetInputStream(path) ?: error("Missing $path")
        val source = IThemeSource.fromInputStream(stream, path, Charsets.UTF_8)
        val registry = ThemeRegistry.getInstance()
        runCatching {
            registry.loadTheme(ThemeModel(source, name).apply { isDark = dark })
        }
        registry.setTheme(name)

        if (!initialized) GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        initialized = true
        currentDark = dark
    }

    fun language(path: String): TextMateLanguage? {
        val scope = scopeFor(path) ?: return null
        return runCatching { TextMateLanguage.create(scope, false) }.getOrNull()
    }

    fun colorScheme(cs: ColorScheme): EditorColorScheme? =
        runCatching {
            TextMateColorScheme.create(ThemeRegistry.getInstance()).apply {
                setColor(EditorColorScheme.WHOLE_BACKGROUND, cs.surfaceContainerHigh.toArgb())
                setColor(EditorColorScheme.TEXT_NORMAL, cs.onSurface.toArgb())
                setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, cs.surfaceContainerHigh.toArgb())
                setColor(EditorColorScheme.LINE_NUMBER, cs.onSurfaceVariant.toArgb())
                setColor(EditorColorScheme.LINE_NUMBER_CURRENT, cs.primary.toArgb())
                setColor(EditorColorScheme.CURRENT_LINE, cs.surfaceContainerHighest.toArgb())
                setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, cs.primaryContainer.toArgb())
                setColor(EditorColorScheme.TEXT_SELECTED, cs.onPrimaryContainer.toArgb())
                setColor(EditorColorScheme.LINE_DIVIDER, cs.outlineVariant.toArgb())
                setColor(EditorColorScheme.SCROLL_BAR_THUMB, cs.onSurfaceVariant.toArgb())
                setColor(EditorColorScheme.SCROLL_BAR_TRACK, cs.surfaceContainerHigh.toArgb())
                setColor(EditorColorScheme.SELECTION_HANDLE, cs.primary.toArgb())
                setColor(EditorColorScheme.SELECTION_INSERT, cs.primary.toArgb())
            }
        }.getOrNull()

    private fun scopeFor(path: String): String? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "source.kotlin"
            "java" -> "source.java"
            "js", "jsx", "ts", "tsx" -> "source.js"
            "py" -> "source.python"
            "xml", "svg" -> "text.xml"
            "html", "htm" -> "text.html.basic"
            "md", "markdown" -> "text.html.markdown"
            "json" -> "source.js"
            "gradle" -> "source.java"
            else -> null
        }
}
