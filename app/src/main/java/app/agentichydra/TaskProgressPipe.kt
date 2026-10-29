package app.agentichydra

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live preview of the currently running work conversation, observed by [TaskService]
 * so the progress notification shows the chat title, the model's latest thought or
 * action, and the elapsed time.
 */
object TaskProgressPipe {
    data class Live(
        val conversationId: String? = null,
        val title: String = "",
        val headline: String = "",
        /** SystemClock.elapsedRealtime() reading taken when the run started. */
        val startedElapsed: Long = 0L,
        val running: Boolean = false,
    )

    private val seed = MutableStateFlow(Live())
    val live: StateFlow<Live> = seed

    fun post(live: Live) {
        seed.value = live
    }

    fun update(transform: (Live) -> Live) {
        seed.value = transform(seed.value)
    }
}
