package app.agentichydra

import android.app.Activity
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ResponseRevealTest {
    @Test
    fun firstTextStartsFadingImmediatelyAndLaterChunksCannotResetTheTailDeadline() {
        val reveal = ResponseReveal()
        val first = SpannableString("new")
        reveal.update(first, true, 0, true, 0, 160)
        assertEquals(0f, first.alphaAt(0))
        assertTrue(reveal.tick(50, 160))
        assertTrue(first.alphaAt(0) in 0.01f..0.99f)

        val second = SpannableString("new word")
        reveal.update(second, true, 0, true, 100, 160)
        assertEquals(0f, second.alphaAt(5))
        val third = SpannableString("new word here")
        reveal.update(third, true, 0, true, 175, 160)
        assertTrue(reveal.tick(220, 160))
        assertTrue(third.alphaAt(5) in 0.01f..0.99f)
        assertEquals(0f, third.alphaAt(10))
        assertFalse(reveal.tick(430, 160))
        assertEquals(1f, third.alphaAt(10))
    }

    @Test
    fun attachedTextViewRevealsTextWithoutWaitingForAnotherUpdate() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val view = StreamingMarkdownView(activity)
        activity.setContentView(view)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertTrue(view.isAttachedToWindow)
        view.setText("answer", TextView.BufferType.SPANNABLE)
        view.updateReveal(streaming = true, cutoff = 0, animate = true, millis = 160)

        assertEquals(0f, (view.text as Spanned).alphaAt(0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        assertTrue((view.text as Spanned).alphaAt(0) > 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        assertEquals(1f, (view.text as Spanned).alphaAt(0))
    }

    @Test
    fun laterChunksDoNotDimPreviouslyRevealedTextAndCompletionReleasesTheTail() {
        val reveal = ResponseReveal()
        val first = SpannableString("hello")
        reveal.update(first, true, 0, true, 0, 100)
        reveal.tick(200, 100)

        val next = SpannableString("hello world")
        reveal.update(next, true, 5, true, 210, 100)
        assertEquals(1f, next.alphaAt(1))
        assertEquals(0f, next.alphaAt(7))
        reveal.update(next, false, next.length, true, 220, 100)
        assertFalse(reveal.tick(320, 100))
        assertEquals(1f, next.alphaAt(7))
    }

    @Test
    fun graphemesStayTogetherAndDisablingFadeClearsTheBuffer() {
        val reveal = ResponseReveal()
        reveal.update(SpannableString("a"), true, 0, true, 0, 160)
        val text = SpannableString("ae\u0301!")
        reveal.update(text, true, 2, true, 10, 160)
        reveal.tick(60, 160)
        assertEquals(0f, text.alphaAt(1))
        assertEquals(0f, text.alphaAt(2))
        reveal.update(text, true, 2, false, 61, 160)
        assertEquals(1f, text.alphaAt(1))
        assertEquals(1f, text.alphaAt(2))
    }

    private fun Spanned.alphaAt(index: Int) =
        getSpans(index, index + 1, FadeSpan::class.java).firstOrNull()?.alpha ?: 1f
}
