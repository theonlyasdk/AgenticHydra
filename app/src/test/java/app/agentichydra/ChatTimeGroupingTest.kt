package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatTimeGroupingTest {
    @Test fun onlyTheFirstMessageAndMessagesAfterThirtyMinutesOfInactivityHaveDividers() {
        val base=1_700_000_000_000L
        fun message(id:String,role:String,minutes:Long)=Message(role,id,id=id,createdAt=base+minutes*60_000)
        val messages=listOf(message("first","user",0),message("reply","assistant",1),message("follow-up","user",5),
            message("tool","tool",12),message("nearby","user",35),message("new-session","user",65))
        assertEquals(setOf("first","new-session"),chatTimeMarkerIds(messages))
        assertTrue(chatTimeMarkerIds(listOf(Message("user","Legacy",createdAt=0))).isEmpty())
    }

    @Test fun legacyCancelledFailureIsNormalizedWithoutTheLargeCancellationMessage() {
        val record=JSONObject().put("id","legacy").put("title","A task").put("status","Failed").put("summary","")
            .put("steps",JSONArray()).put("changes",JSONArray()).put("messages",JSONArray()
                .put(Message("user","A request").json()).put(Message("notice","Canceled").json()))
        val migrated=WorkRecord.fromJson(record)
        assertEquals("Cancelled",migrated.status)
        assertEquals(1,migrated.messages.size)
    }
}
