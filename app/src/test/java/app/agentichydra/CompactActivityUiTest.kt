package app.agentichydra

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import org.junit.Test

class CompactActivityUiTest:AppUiTestBase() {
    @Test fun compactRowsHideCompletionLabelsAndOpenDetailsInASheet(){
        val steps=listOf(
            WorkStep("list_files","{}","Done","src/       README.md",id="compact-list"),
            WorkStep("run_command","{\"command\":\"./gradlew assembleDebug --console=plain\"}","Running",id="compact-run"))
        controller.get().setContent{HydraTheme(darkTheme=true,dynamicColor=false){
            WorkChatScreen(WorkChatUiState("Refine the activity feed","Hydra",listOf(
                Message("user","Make the tool activity more compact."),
                Message("assistant","I’ll review the layout and check the build."))+
                steps.map{Message("tool",it.tool,id=it.id)},steps,running=true,status="Running",progress="Checking the updated layout…"),"",{},WorkChatActions()){Text("Project model")}
        }}
        compose.onNodeWithText("Done",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Arguments",substring=false).assertDoesNotExist()
        screenshot("compact-tools-dark")
        compose.onNodeWithText("List project files").performClick()
        compose.onNodeWithTag("work-tool-compact-list-details").assertIsDisplayed()
        compose.onNodeWithText("Result",substring=false).assertIsDisplayed()
        screenshot("compact-tool-details")
    }
}
