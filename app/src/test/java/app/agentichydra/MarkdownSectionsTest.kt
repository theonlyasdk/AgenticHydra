package app.agentichydra

import org.junit.Assert.*
import org.junit.Test

class MarkdownSectionsTest {
    @Test fun extractsCodeWithLanguageAndKeepsSurroundingMarkdown() {
        val result=MarkdownSections.parse("# Heading\n\n```kotlin\nval answer = 42\n```\n\n**Done**")
        assertEquals(3,result.size);assertEquals("kotlin",result[1].language);assertEquals("val answer = 42",result[1].text);assertTrue(result[2].text.contains("**Done**"))
    }
    @Test fun rendersUnclosedStreamingFenceAsCodeWithoutDroppingTokens() {
        val result=MarkdownSections.parse("```python\nprint('hello')")
        assertEquals("python",result.single().language);assertEquals("print('hello')",result.single().text)
    }
    @Test fun supportsTildeFencesAndShorterInnerBackticks() {
        val result=MarkdownSections.parse("~~~~text\n```\ncode\n~~~~")
        assertEquals("text",result.single().language);assertEquals("```\ncode",result.single().text)
    }
    @Test fun plainMarkdownIsNotMistakenForCode() {
        val result=MarkdownSections.parse("Use `inline` code.\n- Item\n\n| A | B |\n|---|---|\n| 1 | 2 |")
        assertTrue(result.all{it.language==null});assertEquals(listOf("A","B"),result.last().table!!.first());assertEquals(listOf("1","2"),result.last().table!!.last())
    }
}
