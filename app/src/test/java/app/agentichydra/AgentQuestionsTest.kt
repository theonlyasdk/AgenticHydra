package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionsTest {
    private fun question(type:String="single_choice")=JSONObject().put("id","scope").put("title","Choose a scope").put("question","What should I work on?")
        .put("type",type).put("options",JSONArray(listOf("Chat","Work")))
    @Test fun validatesChoicesAndReturnsAnswersInRequestOrder(){
        val input=JSONObject().put("questions",JSONArray().put(question()).put(question("yes_no").put("id","tests")))
        val request=QuestionTool.parse(input)
        assertFalse(QuestionTool.valid(request.questions.first(),null))
        assertFalse(QuestionTool.valid(request.questions.first(),JSONObject("""{"selected":["Chat","Work"]}""")))
        val drafts=JSONObject().put("scope",JSONObject("""{"selected":["Work"]}""")).put("tests",JSONObject("""{"selected":["No"]}"""))
        val result=JSONObject(QuestionTool.result(request,drafts.toString())).getJSONArray("answers")
        assertEquals("scope",result.getJSONObject(0).getString("id"))
        assertEquals("No",result.getJSONObject(1).getJSONArray("selected").getString(0))
    }
    @Test fun multipleChoicesTextOtherAndSkippedQuestionsHaveClearValidation(){
        val multi=AgentQuestion("m","Features","Select features",QuestionType.MULTIPLE,listOf("Search","Files"),allowOther=true)
        assertTrue(QuestionTool.valid(multi,JSONObject("""{"selected":["Search","Files"]}""")))
        assertFalse(QuestionTool.valid(multi,JSONObject("""{"selected":["Search","Search"]}""")))
        assertFalse(QuestionTool.valid(multi,JSONObject("""{"other":true,"text":""}""")))
        assertTrue(QuestionTool.valid(multi,JSONObject("""{"other":true,"text":"Device tools"}""")))
        val text=AgentQuestion("t","Notes","Anything else?",QuestionType.TEXT,emptyList(),required=false)
        assertTrue(QuestionTool.valid(text,null))
        assertFalse(QuestionTool.valid(text,JSONObject().put("text","x".repeat(1001))))
        assertEquals("Device tools",JSONObject(QuestionTool.result(AgentQuestionRequest(listOf(multi)),"""{"m":{"other":true,"text":"Device tools"}}""")).getJSONArray("answers").getJSONObject(0).getString("text"))
    }
    @Test fun rejectsMalformedRequestsAndQuestionsNeverBypassToolApproval(){
        assertTrue(runCatching{QuestionTool.parse(JSONObject().put("questions",JSONArray()))}.isFailure)
        assertTrue(runCatching{QuestionTool.parse(JSONObject().put("questions",JSONArray().put(question()).put(question())))}.isFailure)
        assertTrue(runCatching{QuestionTool.parse(JSONObject().put("questions",JSONArray().put(question("arbitrary"))))}.isFailure)
        ApprovalMode.entries.forEach{assertFalse(ToolPolicy.approval(it,"ask_user"))}
        assertTrue(ToolPolicy.approval(ApprovalMode.CHANGES,"set_flashlight"))
        assertTrue(ToolPolicy.approval(ApprovalMode.CHANGES,"get_location"))
        assertFalse(ToolPolicy.approval(ApprovalMode.CHANGES,"device_info"))
    }
}
