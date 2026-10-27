package app.agentichydra

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable internal fun TaskSchedulesSheet(schedules:TaskSchedules,onDismiss:()->Unit) {
    val context=LocalContext.current
    HydraBottomSheet(onDismiss=onDismiss){dismiss->
        TaskScheduleContent(schedules.records,{schedules.cancel(it)},{id->context.startActivity(Intent(context,WorkChatActivity::class.java).putExtra("conversationId",id))},dismiss)
    }
}

@Composable private fun TaskScheduleContent(records:List<ScheduledTask>,onCancel:(String)->Unit,onOpen:(String)->Unit,onDismiss:()->Unit) {
    val cs=MaterialTheme.colorScheme;val context=LocalContext.current
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(Icons.Outlined.Schedule,null,tint=cs.primary)
                Text("Scheduled tasks",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                IconButton(onClick=onDismiss){Icon(Icons.Outlined.Close,"Close scheduled tasks")}
            }
            Text("Android may run tasks later to conserve battery. Pending approvals still need your response.",style=MaterialTheme.typography.bodySmall,color=cs.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(records.isEmpty())item{Text("No scheduled tasks. Ask the agent to schedule a follow-up.",Modifier.padding(vertical=24.dp))}
                items(records,key={it.id}){record->
                    Surface(color=cs.surfaceContainerHigh,shape=MaterialTheme.shapes.large){
                        ListItem(headlineContent={MarqueeTitle(record.title,surface=cs.surfaceContainerHigh)},supportingContent={Column{
                            Text(record.state,style=MaterialTheme.typography.labelMedium)
                            Text(DateFormat.getMediumDateFormat(context).format(Date(record.runAt))+" · "+DateFormat.getTimeFormat(context).format(Date(record.runAt)),style=MaterialTheme.typography.bodySmall)
                            if(record.intervalSeconds>0)Text("Repeats every ${record.intervalSeconds/60} min · ${record.remaining} runs left",style=MaterialTheme.typography.labelSmall)
                            if(record.error.isNotBlank())Text(record.error,style=MaterialTheme.typography.bodySmall,color=cs.error)
                        }},trailingContent={Row{
                            IconButton(onClick={onOpen(record.conversationId)}){Icon(Icons.Outlined.ChatBubbleOutline,"Open scheduled conversation")}
                            IconButton(onClick={onCancel(record.id)},enabled=record.state in setOf("Scheduled","Running")){Icon(Icons.Outlined.Cancel,"Cancel scheduled task")}
                        }},colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerHigh))
                    }
                }
            }
        }
}

@Preview(name="Schedules light")
@Preview(name="Schedules dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Schedules large text",fontScale=1.5f)
@Composable private fun TaskSchedulePreview(){HydraTheme{Surface{TaskScheduleContent(listOf(
    ScheduledTask("preview","Review the build","Review the result.","task",System.currentTimeMillis()+3600000,"{}",3600,3)),{},{},{})}}}
