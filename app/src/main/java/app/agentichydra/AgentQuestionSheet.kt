package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable fun AgentQuestionSheet(request:AgentQuestionRequest,onAnswer:(String)->Unit,onCancel:()->Unit) {
    var index by rememberSaveable(request.id){mutableIntStateOf(0)}
    var drafts by rememberSaveable(request.id){mutableStateOf("{}")}
    var submitted by remember(request.id){mutableStateOf<String?>(null)}
    var error by remember(request.id){mutableStateOf<String?>(null)}
    HydraBottomSheet(onDismiss={submitted?.let(onAnswer)?:onCancel()}){dismiss->
        val question=request.questions[index]
        val answer=remember(drafts,question.id){JSONObject(drafts).optJSONObject(question.id)?:JSONObject()}
        fun change(value:JSONObject){drafts=JSONObject(drafts).put(question.id,value).toString()}
        fun submit(){runCatching{QuestionTool.result(request,drafts)}.onSuccess{submitted=it;dismiss()}.onFailure{error=it.message}}
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){
                    Text("A question for you",style=MaterialTheme.typography.titleLarge)
                    Text("${index+1} of ${request.questions.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"Dismiss questions")}
            }
            LinearProgressIndicator(progress={ (index+1f)/request.questions.size },modifier=Modifier.fillMaxWidth())
            AnimatedContent(targetState=index,modifier=Modifier.weight(1f,fill=false),label="Question step",transitionSpec={
                val direction=if(targetState>initialState)1 else -1
                ((slideInHorizontally(spring(stiffness=650f)){it/8*direction}+fadeIn(spring(stiffness=650f))) togetherWith
                    (slideOutHorizontally(spring(stiffness=650f)){-it/8*direction}+fadeOut(spring(stiffness=650f)))).using(null)
            }){page->
                val q=request.questions[page]
                val value=JSONObject(drafts).optJSONObject(q.id)?:JSONObject()
                QuestionContent(q,value){updated->drafts=JSONObject(drafts).put(q.id,updated).toString()}
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(index>0)OutlinedButton(onClick={index--},modifier=Modifier.heightIn(min=48.dp)){Text("Back")}
                if(!question.required)TextButton(onClick={change(JSONObject());if(index<request.questions.lastIndex)index++ else submit()},modifier=Modifier.heightIn(min=48.dp)){Text("Skip")}
                Button(onClick={if(index<request.questions.lastIndex)index++ else submit()},
                    enabled=QuestionTool.valid(question,answer),modifier=Modifier.weight(1f).heightIn(min=48.dp)){
                    Text(if(index==request.questions.lastIndex)"Send answers"else "Next")
                }
            }
        }
    }
}

@Composable private fun QuestionContent(question:AgentQuestion,answer:JSONObject,onChange:(JSONObject)->Unit) {
    val cs=MaterialTheme.colorScheme
    val array=answer.optJSONArray("selected")?:JSONArray()
    val selected=(0 until array.length()).map{array.getString(it)}
    val other=answer.optBoolean("other",false)
    val text=answer.optString("text")
    Column(Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(question.title,style=MaterialTheme.typography.titleMedium)
        Text(question.prompt,style=MaterialTheme.typography.bodyLarge)
        if(!question.required)Text("Optional",style=MaterialTheme.typography.labelSmall,color=cs.onSurfaceVariant)
        if(question.type!=QuestionType.TEXT)Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            val options=question.options+if(question.allowOther)listOf("Write my own answer")else emptyList()
            options.forEachIndexed{position,option->
                val isOther=position==question.options.size
                val checked=if(isOther)other else option in selected
                Surface(onClick={
                    val multi=question.type==QuestionType.MULTIPLE
                    val next=if(isOther){if(multi)selected else emptyList()}else if(multi){if(checked)selected-option else selected+option}else listOf(option)
                    val nextOther=if(isOther)!checked else multi&&other
                    onChange(JSONObject().put("selected",JSONArray(next)).put("other",nextOther).put("text",if(nextOther)text else ""))
                },color=if(checked)cs.primaryContainer else cs.surfaceContainerHigh,shape=groupedRowShape(position,options.size),
                    modifier=Modifier.fillMaxWidth().semantics{role=if(question.type==QuestionType.MULTIPLE)Role.Checkbox else Role.RadioButton}) {
                    ListItem(headlineContent={Text(option)},leadingContent={
                        if(question.type==QuestionType.MULTIPLE)Checkbox(checked,onCheckedChange=null)else RadioButton(checked,onClick=null)
                    },colors=ListItemDefaults.colors(containerColor=if(checked)cs.primaryContainer else cs.surfaceContainerHigh))
                }
            }
        }
        if(question.type==QuestionType.TEXT||other)OutlinedTextField(text,{onChange(JSONObject(answer.toString()).put("text",it.take(1000)))},
            label={Text("Your answer")},minLines=2,maxLines=5,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth())
    }
}

@Preview(name="Question wizard light")
@Preview(name="Question wizard dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Question wizard large text",fontScale=1.5f)
@Composable private fun AgentQuestionPreview(){HydraTheme{AgentQuestionSheet(AgentQuestionRequest(listOf(
    AgentQuestion("approach","Choose an approach","Which part should I work on first?",QuestionType.SINGLE,listOf("Chat interface","Work tools","Settings")))) ,{},{})}}
