package app.agentichydra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleToolsTest {
    @Test
    fun durationsAndAbsoluteTimesAreBoundedAndRespectOffsets() {
        val now = ScheduleTools.parseTime("2026-10-27T12:00:00Z")
        assertEquals(2000L, ScheduleTools.waitMillis("wait_for", JSONObject().put("seconds", 2), now))
        assertEquals(
            30_000L,
            ScheduleTools.waitMillis("wait_until", JSONObject().put("until", "2026-10-27T17:30:30+05:30"), now),
        )
        assertEquals(0L, ScheduleTools.waitMillis("wait_until", JSONObject().put("until", "2026-10-27T11:00:00Z"), now))
        assertTrue(
            runCatching { ScheduleTools.waitMillis("wait_for", JSONObject().put("seconds", 301), now) }.isFailure,
        )
        assertTrue(runCatching { ScheduleTools.parseTime("2026-10-27T12:00:00") }.isFailure)
        assertEquals(now + 60_000, ScheduleTools.due(JSONObject().put("delay_seconds", 60), now))
        assertTrue(
            runCatching {
                ScheduleTools.due(JSONObject().put("delay_seconds", 60).put("run_at", "2026-10-28T12:00:00Z"), now)
            }.isFailure,
        )
        assertTrue(runCatching { ScheduleTools.due(JSONObject().put("run_at", "2026-10-26T12:00:00Z"), now) }.isFailure)
    }
}
