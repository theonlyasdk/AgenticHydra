package app.agentichydra

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector

private fun openWork(context:Context,id:String?=null) {
    context.startActivity(Intent(context,WorkChatActivity::class.java).apply{if(id==null)putExtra("newConversation",true)else putExtra("conversationId",id)})
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun WorkScreen(chat:HydraModel,vm:WorkModel) {
    val cs=MaterialTheme.colorScheme
    val context=LocalContext.current
    var query by rememberSaveable{mutableStateOf("")}
    var options by remember{mutableStateOf<WorkRecord?>(null)}
    var selection by rememberSaveable{mutableStateOf(emptySet<String>() )}
    var multiOptions by rememberSaveable{mutableStateOf(false)}
    var rename by remember{mutableStateOf<WorkRecord?>(null)}
    var title by rememberSaveable{mutableStateOf("")}
    var approvals by rememberSaveable{mutableStateOf(false)}
    var scheduled by rememberSaveable{mutableStateOf(false)}
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)runCatching{vm.selectProject(uri)}.onFailure{vm.error=it.message}}
    val filtered=remember(vm.records,query){vm.records.filter{it.title.contains(query,true)||it.projectName.contains(query,true)}}
    val selectionCount=selection.size
    Scaffold(containerColor=cs.background,topBar={TopAppBar(
        title={if(selectionCount==0)Text("Work",style=MaterialTheme.typography.titleLarge) else Text("${selectionCount} selected",style=MaterialTheme.typography.titleMedium)},
        colors=TopAppBarDefaults.topAppBarColors(containerColor=cs.background),actions={
            if(selectionCount>0){
                HintIconButton("Selected task options",Icons.Outlined.MoreHoriz,onClick={multiOptions=true})
                HintIconButton("Clear selection",Icons.Outlined.Clear,onClick={selection=emptySet()})
            }else{
            HintIconButton("Scheduled tasks",Icons.Outlined.Schedule,onClick={scheduled=true})
            HintIconButton("Tool approvals",Icons.Outlined.Shield,onClick={approvals=true})
            }})},
        floatingActionButton={ExtendedFloatingActionButton(onClick={openWork(context,if(vm.running)vm.activeId else null)},
            icon={Icon(if(vm.running)Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Add,null)},text={Text(if(vm.running)"Open running task"else "New task")})}) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max=840.dp).fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {
                    DockedSearchBar(inputField={SearchBarDefaults.InputField(query=query,onQueryChange={query=it},onSearch={},expanded=false,onExpandedChange={},
                        placeholder={Text("Search tasks")},leadingIcon={Icon(Icons.Outlined.Search,null)})},expanded=false,onExpandedChange={},modifier=Modifier.fillMaxWidth()){}
                }
                vm.error?.let{error->item{WorkError(error){vm.error=null}}}
                if(filtered.isEmpty())item {
                    Box(Modifier.fillParentMaxHeight().fillMaxWidth(),contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            HydraIllustration(Modifier.size(100.dp))
                            Text(if(query.isBlank())"No tasks yet"else "No matching tasks",style=MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                items(filtered,key={it.id}) { record ->
                    val active=vm.running&&vm.activeId==record.id
                    val background=record.status=="Running"
                    val selected=record.id in selection
                    Surface(color=if(selected)cs.primaryContainer else cs.surfaceContainerLow,shape=MaterialTheme.shapes.large,
                        modifier=Modifier.fillMaxWidth().combinedClickable(
                            onClick={if(selection.isNotEmpty()){if(selected){selection-=record.id}else{selection+=record.id}}else openWork(context,record.id)},
                            onLongClick={if(selection.isNotEmpty()){if(selected){selection-=record.id}else{selection+=record.id}}else options=record},
                            onLongClickLabel="Task options")) {
                        ListItem(headlineContent={MarqueeTitle(record.title,surface=cs.surfaceContainerLow)},
                            supportingContent={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                MarqueeTitle(projectSubtitle(record.projectName,record.projectUri!=null,active),surface=cs.surfaceContainerLow,style=MaterialTheme.typography.bodySmall)
                                when {
                                    active->WorkProgressLabel(vm.progress.ifBlank{"Thinking…"},cs.surfaceContainerLow)
                                    background->WorkProgressLabel(if(vm.engine.isRunning(record.id))vm.engine.progressFor(record.id).ifBlank{"Working…"}else "Working…",cs.surfaceContainerLow)
                                    else->Text(record.status,style=MaterialTheme.typography.labelSmall,color=if(record.status=="Failed")cs.error else cs.onSurfaceVariant)
                                }
                            }},leadingContent={when{selected->Icon(Icons.Outlined.Check,"",Modifier.size(24.dp));active||background->CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);else->Icon(Icons.Outlined.Terminal,null,tint=cs.primary)}},
                            trailingContent={IconButton(onClick={if(selection.isNotEmpty())multiOptions=true else options=record}){Icon(Icons.Outlined.MoreVert,"Task options")}},
                            colors=ListItemDefaults.colors(containerColor=if(selected)cs.primaryContainer else cs.surfaceContainerLow))
                    }
                }
            }
        }
    }
    options?.let{record->HydraBottomSheet(onDismiss={options=null}){dismiss->
        var confirm by remember{mutableStateOf(false)}
        LaunchedEffect(record.id,confirm){if(confirm){delay(3500);confirm=false}}
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=20.dp),verticalAlignment=Alignment.CenterVertically){MarqueeTitle(record.title,style=MaterialTheme.typography.headlineSmall,surface=cs.surfaceContainerLow,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    TaskOptionRow(0,5,Icons.Outlined.ChatBubbleOutline,"Open conversation"){dismiss();openWork(context,record.id)}
                    TaskOptionRow(1,5,Icons.Outlined.Edit,"Rename"){dismiss();title=record.title;rename=record}
                    TaskOptionRow(2,5,Icons.Outlined.FolderOpen,"Change directory",enabled=!vm.running){dismiss();if(vm.viewRecord(record))folder.launch(null)}
                    TaskOptionRow(3,5,Icons.Outlined.Share,"Export conversation"){dismiss();context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT,exportWorkRecord(record)),"Export task"))}
                    TaskOptionRow(4,5,Icons.Outlined.DeleteOutline,"Delete",enabled=!(vm.running&&vm.activeId==record.id)&&!vm.engine.isRunning(record.id),
                        danger=!confirm,confirmDelete=confirm){
                        if(!confirm){confirm=true}else{confirm=false;vm.deleteRecord(record);dismiss()}
                    }
            }
        }
    }}
    if(multiOptions&&selection.isNotEmpty())taskMultiOptionsSheet(vm,selection,selectAll={selection=vm.records.map{it.id}.toSet()},clearSelection={selection=emptySet()},onDismiss={multiOptions=false;selection=emptySet()})
    if(approvals)ApprovalSheet(vm){approvals=false}
    if(scheduled)TaskSchedulesSheet((context.applicationContext as HydraApplication).schedules){scheduled=false}
    rename?.let{record->
        val renameFocus=remember{FocusRequester()}
        val keyboard=LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit){renameFocus.requestFocus();keyboard?.show()}
        AlertDialog(onDismissRequest={rename=null},title={Text("Rename task")},text={OutlinedTextField(title,{title=it},label={Text("Task name")},singleLine=true,modifier=Modifier.focusRequester(renameFocus))},
            confirmButton={TextButton(onClick={vm.renameConversation(record.id,title);rename=null},enabled=title.isNotBlank()){Text("Rename")}},dismissButton={TextButton(onClick={rename=null}){Text("Cancel")}})}
}

internal fun projectSubtitle(name:String,selected:Boolean,running:Boolean)=if(!selected)"Choose a project"else "${if(running)"Running in"else "Inside"} $name"

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TaskOptionRow(index:Int,total:Int,icon:ImageVector,label:String,enabled:Boolean=true,
    danger:Boolean=false,confirmDelete:Boolean=false,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val rowColor by animateColorAsState(if(confirmDelete)cs.errorContainer else cs.surfaceContainerHigh,
        animationSpec=spring(dampingRatio=.9f,stiffness=380f),label="TaskOptionColor")
    Surface(onClick=onClick,enabled=enabled,color=rowColor,
        shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
        AnimatedContent(targetState=confirmDelete,transitionSpec={
            ((slideInVertically(spring(dampingRatio=.9f,stiffness=350f)){it}+fadeIn(spring())) togetherWith
                (slideOutVertically(spring(dampingRatio=.9f,stiffness=350f)){-it}+fadeOut(spring()))).using(null)
        },label="TaskOptionLabel"){confirmed->
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(confirmed)Icons.Outlined.CheckCircleOutline else icon,label,
                    Modifier.size(if(confirmed)24.dp else 26.dp),tint=if(confirmed)cs.onSecondaryContainer else cs.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(label,
                    style=MaterialTheme.typography.titleMedium,
                    color=if(danger)cs.error else cs.onSurface)
            }
        }
    }
}
@Composable private fun taskMultiOptionsSheet(vm:WorkModel,selection:Set<String>,selectAll:()->Unit,clearSelection:()->Unit,onDismiss:()->Unit) {
    val cs=MaterialTheme.colorScheme
    HydraBottomSheet(onDismiss=onDismiss){dismiss->
        var confirm by remember{mutableStateOf(false)}
        LaunchedEffect(selection,confirm){if(confirm){delay(3500);confirm=false}}
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Outlined.Checklist,null,tint=cs.primary);Spacer(Modifier.width(12.dp))
                Text("${selection.size} selected",style=MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    TaskOptionRow(0,3,Icons.Outlined.SelectAll,"Select all"){selectAll();dismiss()}
                    TaskOptionRow(1,3,Icons.Outlined.DeleteOutline,"Delete selected",danger=!confirm,confirmDelete=confirm){
                        if(!confirm){confirm=true}else{
                            confirm=false;vm.deleteRecords(selection);clearSelection();dismiss()}
                    }
                    TaskOptionRow(2,3,Icons.Outlined.Clear,"Clear selection"){dismiss();clearSelection()}
            }
        }
    }
}
@Composable private fun WorkProgressLabel(text:String,surface:androidx.compose.ui.graphics.Color) {
    val scroll=rememberScrollState()
    val direction=androidx.compose.ui.platform.LocalLayoutDirection.current
    LaunchedEffect(text){scroll.scrollTo(0)}
    Box(Modifier.fillMaxWidth().horizontalScrollFadingEdges(scroll,surface,direction).horizontalScroll(scroll)) {
        ShimmerStatusText(text,style=MaterialTheme.typography.labelSmall,maxLines=1)
    }
}
internal fun exportWorkRecord(record:WorkRecord)="# ${record.title}\n\n"+record.messages.joinToString("\n\n"){message->
    if(message.role=="tool")record.steps.firstOrNull{it.id==message.id}?.let{"### ${it.tool}\n\n```json\n${it.arguments}\n```\n\n```text\n${it.output}\n```"}.orEmpty()
    else "### ${message.role.replaceFirstChar{it.uppercase()}}\n\n${message.text}"
}

class WorkChatActivity:ComponentActivity() {
    private var conversationId=""
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        val application=application as HydraApplication
        val work=application.models[WorkModel::class.java]
        val chat=application.models[HydraModel::class.java]
        val requested=savedInstanceState?.getString("conversationId")?:intent.getStringExtra("conversationId")
        if(requested!=null)work.records.firstOrNull{it.id==requested}?.let{work.viewRecord(it)}
        else if(savedInstanceState==null&&intent.getBooleanExtra("newConversation",false))work.newThread()
        conversationId=requested?:work.activeId
        setContent { HydraTheme { WorkChatRoute(chat,work,conversationId){finish()} } }
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("conversationId",conversationId);super.onSaveInstanceState(outState)}
}

data class WorkChatUiState(val title:String="New task",val project:String="Choose a project",val messages:List<Message> = emptyList(),
    val steps:List<WorkStep> = emptyList(),val pendingId:String?=null,val running:Boolean=false,val status:String="Ready",val error:String?=null,
    val conversationId:String="new",val canSend:Boolean=true,val attachments:List<MessageAttachment> = emptyList(),val attaching:Boolean=false,val progress:String="",val hasProject:Boolean=true,val streamingResponseId:String?=null,
    val activeTasks:Int=0)

class WorkChatActions(val onSend:()->Unit={},val onResume:()->Unit={},val onStop:()->Unit={},val onBack:()->Unit={},val onProject:()->Unit={},val onFiles:()->Unit={},
    val onChanges:()->Unit={},val onApprovals:()->Unit={},val onConnections:()->Unit={},val onApprove:(String,Boolean)->Unit={_,_->},val onDismissError:()->Unit={},
    val onCopy:(Message)->Unit={},val onShare:(Message)->Unit={},val onAttachFile:()->Unit={},val onAttachMedia:()->Unit={},
    val onRemoveAttachment:(String)->Unit={},val onRename:()->Unit={},val onActiveTasks:()->Unit={})

@Composable private fun WorkChatRoute(chat:HydraModel,vm:WorkModel,id:String,onBack:()->Unit) {
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    var draft by rememberSaveable(id){mutableStateOf("")}
    var models by rememberSaveable{mutableStateOf(false)}
    var connections by rememberSaveable{mutableStateOf(false)}
    var approvals by rememberSaveable{mutableStateOf(false)}
    var files by rememberSaveable{mutableStateOf(false)}
    var changes by rememberSaveable{mutableStateOf(false)}
    var rename by rememberSaveable{mutableStateOf(false)}
    var renameTitle by rememberSaveable{mutableStateOf("")}
    var tasks by remember{mutableStateOf(false)}
    val active=vm.activeId==id
    val saved=vm.records.firstOrNull{it.id==id}
    val engineRun=vm.engine.isRunning(id)
    val state=if(active)WorkChatUiState(vm.task.ifBlank{"New task"},vm.projectName,vm.messages,vm.steps,vm.pendingApproval?.id,vm.running,vm.status,vm.error,id,canSend=!engineRun,attachments=vm.draftAttachments,attaching=vm.attaching,progress=if(engineRun)vm.engine.progressFor(id)else vm.progress,hasProject=vm.projectUri!=null,streamingResponseId=vm.streamingId,activeTasks=vm.engine.active.size)
        else WorkChatUiState(saved?.title?:"Task unavailable",saved?.projectName?:"",saved?.messages.orEmpty(),saved?.steps.orEmpty(),status=saved?.status?:"Deleted",conversationId=id,canSend=!vm.running&&saved!=null&&!engineRun,hasProject=saved?.projectUri!=null,progress=vm.engine.progressFor(id),activeTasks=vm.engine.active.size)
    fun activate()=vm.activeId==id||saved?.let{vm.viewRecord(it)}==true
    LaunchedEffect(vm,id) {
        vm.toasts.collect{event->
            val visible=(context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)!=false
            if(event.conversationId==id&&visible)Toast.makeText(context,event.message,Toast.LENGTH_LONG).show()
        }
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)runCatching{vm.selectProject(uri)}.onFailure{vm.error=it.message}}
    var askedForProject by rememberSaveable(id){mutableStateOf(false)}
    LaunchedEffect(id) {
        if(active&&vm.projectUri==null&&vm.messages.isEmpty()&&!askedForProject){askedForProject=true;folder.launch(null)}
    }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null&&activate())vm.attach(uri,media=false)}
    val mediaPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null&&activate())vm.attach(uri,media=true)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val actions=WorkChatActions(onBack=onBack,onSend={
        if(activate()&&vm.followUp(draft,chat)){draft="";if(Build.VERSION.SDK_INT>=33)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}
    },onResume={
        if(activate()&&vm.resume(chat)){if(Build.VERSION.SDK_INT>=33)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}
    },onStop={if(vm.activeId==id)vm.stop()},onProject={if(!vm.running&&activate())folder.launch(null)},
        onFiles={if(activate()){vm.refreshFiles();files=true}},onChanges={if(activate())changes=true},
        onApprovals={approvals=true},onConnections={connections=true},onApprove=vm::approveStep,onDismissError={vm.error=null},
        onAttachFile={if(!vm.running&&activate())filePicker.launch(arrayOf("text/*","application/json","application/xml","application/javascript","application/octet-stream"))},
        onAttachMedia={if(!vm.running&&activate())mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
        onRemoveAttachment=vm::removeAttachment,onRename={renameTitle=state.title;rename=true},onActiveTasks={tasks=true},
        onCopy={clipboard.setText(AnnotatedString(it.text))},onShare={message->runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message.text),"Share response"))}})
    WorkChatScreen(state,draft,{draft=it},actions){ComposerModelControl(chat,enabled=!vm.running){models=true}}
    if(models)ModelPickerSheet(chat,onConnections={models=false;connections=true},onDismiss={models=false})
    if(connections)ConnectionSheet(chat){connections=false}
    if(approvals)ApprovalSheet(vm){approvals=false}
    if(files)WorkFilesSheet(vm){files=false}
    if(changes)WorkChangesSheet(vm.changes){changes=false}
    if(tasks)ActiveTasksSheet(vm.engine,
        onOpen={openId->context.startActivity(Intent(context,WorkChatActivity::class.java).putExtra("conversationId",openId))},
        onStop=vm.engine::stop,onDismiss={tasks=false})
    if(active){
        vm.pendingQuestions?.let{request->AgentQuestionSheet(request,{vm.answerQuestions(request.id,it)},{vm.answerQuestions(request.id,null)})}
        DeviceActionHost(vm)
    }
    if(rename)AlertDialog(onDismissRequest={rename=false},title={Text("Rename task")},text={
        OutlinedTextField(renameTitle,{renameTitle=it},label={Text("Task name")},singleLine=true,shape=MaterialTheme.shapes.medium)
    },confirmButton={TextButton(onClick={vm.renameConversation(id,renameTitle);rename=false},enabled=renameTitle.isNotBlank()){Text("Rename")}},
        dismissButton={TextButton(onClick={rename=false}){Text("Cancel")}})
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun WorkChatScreen(state:WorkChatUiState,draft:String,onDraft:(String)->Unit,actions:WorkChatActions,modelControl:@Composable ()->Unit) {
    val cs=MaterialTheme.colorScheme
    var menu by remember{mutableStateOf(false)}
    var attachmentMenu by remember{mutableStateOf(false)}
    Scaffold(containerColor=cs.background,topBar={TopAppBar(title={Surface(onClick=actions.onRename,color=cs.background,shape=MaterialTheme.shapes.medium,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription="Rename task"}){Column{
        MarqueeTitle(state.title,style=MaterialTheme.typography.titleMedium,surface=cs.background)
        MarqueeTitle(projectSubtitle(state.project,state.hasProject,state.running),style=MaterialTheme.typography.labelSmall,color=cs.onSurfaceVariant,surface=cs.background)
    }}},navigationIcon={HintIconButton("Back to task history",Icons.AutoMirrored.Outlined.ArrowBack,onClick=actions.onBack)},
        actions={Box{
            HintIconButton("Task options",Icons.Outlined.MoreVert,onClick={menu=true})
            HydraOverflowMenu(menu,{menu=false},headerText=state.title) {
                DropdownMenuItem(text={Text(if(state.activeTasks==1)"1 active task"else "${state.activeTasks} active tasks")},
                    leadingIcon={Icon(Icons.Outlined.AddTask,null)},enabled=state.activeTasks>0,onClick={menu=false;actions.onActiveTasks()})
                DropdownMenuItem(text={Text("Project files")},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},enabled=state.canSend,onClick={menu=false;actions.onFiles()})
                DropdownMenuItem(text={Text("Changes")},leadingIcon={Icon(Icons.Outlined.Difference,null)},enabled=state.canSend,onClick={menu=false;actions.onChanges()})
                DropdownMenuItem(text={Text("Tool approvals")},leadingIcon={Icon(Icons.Outlined.Shield,null)},onClick={menu=false;actions.onApprovals()})
                DropdownMenuItem(text={Text("Connections")},leadingIcon={Icon(Icons.Outlined.Hub,null)},enabled=!state.running,onClick={menu=false;actions.onConnections()})
            }
        }},colors=TopAppBarDefaults.topAppBarColors(containerColor=cs.background))},bottomBar={
        Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp).widthIn(max=840.dp).fillMaxWidth()) {
            state.error?.let{WorkError(it,actions.onDismissError);Spacer(Modifier.height(8.dp))}
            Surface(color=cs.surfaceContainer,shape=MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(8.dp)) {
                    if(state.attaching)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.attachments.isNotEmpty())FlowRow(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        state.attachments.forEach{attachment->InputChip(selected=false,onClick={actions.onRemoveAttachment(attachment.id)},
                            label={MarqueeTitle(attachment.name,Modifier.widthIn(max=180.dp),surface=cs.surfaceContainer,style=MaterialTheme.typography.labelLarge)},
                            leadingIcon={Icon(if(attachment.isImage)Icons.Outlined.Image else Icons.Outlined.AttachFile,null)},trailingIcon={Icon(Icons.Outlined.Close,null)},
                            modifier=Modifier.semantics{contentDescription="Remove ${attachment.name}"})}
                    }
                    TextField(draft,onDraft,placeholder={Text("Reply to Hydra")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp,max=176.dp),
                        textStyle=MaterialTheme.typography.bodyLarge,colors=TextFieldDefaults.colors(focusedContainerColor=cs.surfaceContainer,unfocusedContainerColor=cs.surfaceContainer,
                            focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick={attachmentMenu=true},enabled=!state.running&&state.canSend){Icon(Icons.Outlined.Add,"Add attachment or change directory")}
                            DropdownMenu(attachmentMenu,{attachmentMenu=false}) {
                                DropdownMenuItem(text={Text("Attach file")},leadingIcon={Icon(Icons.Outlined.AttachFile,null)},onClick={attachmentMenu=false;actions.onAttachFile()})
                                DropdownMenuItem(text={Text("Attach media")},leadingIcon={Icon(Icons.Outlined.Image,null)},onClick={attachmentMenu=false;actions.onAttachMedia()})
                                DropdownMenuItem(text={Text("Change directory")},leadingIcon={Icon(Icons.Outlined.CreateNewFolder,null)},onClick={attachmentMenu=false;actions.onProject()})
                            }
                        }
                        Box(Modifier.weight(1f)){modelControl()}
                        FilledIconButton(onClick=if(state.running)actions.onStop else if(!state.running&&state.status=="Cancelled"&&draft.isBlank()&&state.attachments.isEmpty())actions.onResume else actions.onSend,enabled=state.running||(state.canSend&&!state.attaching&&(draft.isNotBlank()||state.attachments.isNotEmpty()||(!state.running&&state.status=="Cancelled"&&draft.isBlank()&&state.attachments.isEmpty()))),modifier=Modifier.size(48.dp)) {
                            Icon(if(state.running)Icons.Outlined.Stop else if(!state.running&&state.status=="Cancelled"&&draft.isBlank()&&state.attachments.isEmpty())Icons.Outlined.PlayArrow else Icons.Outlined.ArrowUpward,if(state.running)"Stop task"else if(!state.running&&state.status=="Cancelled"&&draft.isBlank()&&state.attachments.isEmpty())"Resume task"else "Send task message")
                        }
                    }
                }
            }
            if(!state.canSend)Text("Another task is running. You can browse this conversation while it finishes.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=8.dp))
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            if(state.messages.isEmpty())Column(Modifier.widthIn(max=600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                HydraIllustration(Modifier.size(112.dp));Spacer(Modifier.height(24.dp))
                Text("What are we working on?",style=MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp));Text("Inspect a project, make a change, or research an idea. You can keep refining it here.",style=MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp));TextButton(onClick=actions.onProject){Icon(Icons.Outlined.FolderOpen,null);Spacer(Modifier.width(8.dp));Text(if(state.hasProject)state.project else "Choose project")}
            }else ChatTimeline(ChatTimelineUiState(state.conversationId,state.messages,state.streamingResponseId),actions.onCopy,actions.onShare,
                modifier=Modifier.widthIn(max=760.dp).fillMaxSize(),toolContent={message,_,_->
                    state.steps.firstOrNull{it.id==message.id}?.let{step->WorkToolEntry(step,state.pendingId==step.id){allow->actions.onApprove(step.id,allow)}}
                },trailingContent={
                    if((state.running||state.status=="Running"||state.status=="Thinking")&&state.pendingId==null&&state.streamingResponseId==null)Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(12.dp));ShimmerStatusText(state.progress.ifBlank{if(state.status=="Thinking")"Thinking"else "Working…"},style=MaterialTheme.typography.bodyLarge,maxLines=2)
                    }else if(!state.running&&state.status!="Running"&&state.status!="Thinking")Text("${state.status}\u2002•\u2002Performed ${state.steps.size} action${if(state.steps.size==1)""else "s"}",style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
                })
        }
    }
}

@Composable fun WorkToolEntry(step:WorkStep,needsApproval:Boolean,onApprove:(Boolean)->Unit) {
    val cs=MaterialTheme.colorScheme
    var details by rememberSaveable(step.id){mutableStateOf(false)}
    val args=remember(step.arguments){runCatching{JSONObject(step.arguments)}.getOrDefault(JSONObject())}
    val title=remember(step.tool,step.arguments){toolLabel(step.tool,args)}
    val icon=when(step.tool){"web_search","web_search_batch","fetch_url","find_in_page"->Icons.Outlined.Public;"ask_user"->Icons.Outlined.QuestionAnswer;in DeviceTools.names->Icons.Outlined.Smartphone;"run_command","git_status","git_diff"->Icons.Outlined.Terminal;"read_image"->Icons.Outlined.Image;"find_files","search_files","list_files_recursive"->Icons.Outlined.Search;"write_file","append_file","replace_in_file","apply_diff"->Icons.Outlined.Edit;"start_task","read_task_output"->Icons.Outlined.AddTask;else->Icons.Outlined.Description}
    Column(Modifier.fillMaxWidth().testTag("work-tool-${step.id}")) {
        Surface(onClick={details=true},color=cs.background,shape=MaterialTheme.shapes.extraSmall) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(step.state=="Running") {
                    val pulse by rememberInfiniteTransition(label="Tool activity").animateFloat(.8f,1f,
                        infiniteRepeatable(tween(700),RepeatMode.Reverse),label="Tool icon pulse")
                    Icon(icon,null,Modifier.size(20.dp).graphicsLayer{scaleX=pulse;scaleY=pulse;alpha=pulse},tint=cs.primary)
                    ShimmerStatusText(title,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1)
                }else {
                    Icon(icon,null,Modifier.size(20.dp),tint=if(step.state=="Failed")cs.error else cs.onSurfaceVariant)
                    MarqueeTitle(title,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,surface=cs.background,
                        color=if(step.state=="Failed")cs.error else cs.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore,"View tool details",Modifier.size(16.dp),tint=cs.onSurfaceVariant)
            }
        }
        if(needsApproval)Surface(color=cs.surfaceContainer,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Shield,null,tint=cs.primary);Spacer(Modifier.width(8.dp));Text("Allow this action?",style=MaterialTheme.typography.titleSmall)}
                WorkToolText(step.arguments.take(6000))
                if(step.tool in listOf("run_command","git_status","git_diff"))Text("Runs in Termux with its file and network access.",style=MaterialTheme.typography.bodySmall)
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Button(onClick={onApprove(true)},shape=groupedRowShape(0,2),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Allow once")}
                    FilledTonalButton(onClick={onApprove(false)},shape=groupedRowShape(1,2),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Decline")}
                }
            }
        }
    }
    if(details)HydraBottomSheet(onDismiss={details=false}){dismiss->
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp).testTag("work-tool-${step.id}-details")) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(icon,null,Modifier.size(20.dp),tint=cs.primary)
                Spacer(Modifier.width(12.dp))
                Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"Close tool details")}
            }
            Text(step.state,style=MaterialTheme.typography.labelMedium,color=if(step.state=="Failed")cs.error else cs.onSurfaceVariant)
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Arguments",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=16.dp))
                WorkToolText(step.arguments)
                if(step.output.isNotBlank()){Text("Result",style=MaterialTheme.typography.titleSmall);WorkToolText(step.output)}
            }
        }
    }
}

internal fun commandActivityLabel(command:String):String {
    val commandName=command.trim().substringBefore(' ').substringAfterLast('/').lowercase()
    return when {
        command.trim().startsWith("git status")->"Check Git status"
        command.trim().startsWith("git diff")->"Review project changes"
        commandName=="git"->"Work with the repository"
        commandName in setOf("gradle","gradlew","gradlew.bat")->"Build and check the project"
        commandName in setOf("ls","find","pwd","tree")->"Explore project files"
        commandName in setOf("rg","grep")->"Search the project"
        commandName in setOf("cat","head","tail","sed")->"Inspect project content"
        commandName in setOf("pytest","jest")||command.contains(" test")->"Run project checks"
        else->"Run a project command"
    }
}

internal fun toolLabel(name:String,args:JSONObject):String {
    val path=args.optString("path").substringAfterLast('/')
    return when(name) {
        "ask_user"->"Ask a few questions"
        "web_search_batch"->"Research the web"
        "find_in_page"->"Find relevant passages"
        "get_time"->"Check the current time"
        "wait_for","wait_until"->"Wait before continuing"
        "schedule_task"->"Schedule a follow-up"
        "start_task"->"Start background task: ${args.optString("title")}"
        "read_task_output"->"Check a background task"
        "list_scheduled_tasks"->"Check scheduled tasks"
        "update_scheduled_task"->"Update a schedule"
        "cancel_scheduled_task"->"Cancel a schedule"
        in DeviceTools.names->name.replace('_',' ').replaceFirstChar{it.uppercase()}
        "list_files"->"List ${path.ifBlank{"project files"}}"
        "list_files_recursive"->"List files recursively in ${path.ifBlank{"project"}}"
        "read_file"->"Read $path"
        "write_file"->"Write $path"
        "run_command"->commandActivityLabel(args.optString("command"))
        "search_files"->"Search project: ${args.optString("query")}"
        "find_files"->"Find ${args.optString("pattern")}"
        "file_info"->"Inspect $path"
        "create_directory"->"Create directory $path"
        "append_file"->"Append to $path"
        "replace_in_file"->"Edit $path"
        "apply_diff"->"Edit $path"
        "read_image"->"Read image $path"
        "delete_file"->"Delete $path"
        "move_file"->"Move ${args.optString("source")} → ${args.optString("destination")}"
        "copy_file"->"Copy ${args.optString("source")} → ${args.optString("destination")}"
        "web_search"->"Search the web: ${args.optString("query")}"
        "fetch_url"->"Read ${args.optString("url")}"
        "git_status"->"Check Git status"
        "git_diff"->"Inspect Git diff"
        else->name.replace('_',' ')
    }
}

@Composable private fun WorkToolText(text:String){SelectionContainer{Text(text,style=MaterialTheme.typography.code,
    modifier=Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp),softWrap=false)}}
@Composable private fun WorkError(text:String,onDismiss:()->Unit){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.large){Row(Modifier.padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium);IconButton(onClick=onDismiss){Icon(Icons.Outlined.Close,"Dismiss error")}}}}

@Composable private fun WorkFilesSheet(vm:WorkModel,onDismiss:()->Unit) {
    HydraBottomSheet(onDismiss=onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.FolderOpen,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text("Project files",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))}
            if(vm.browserPath.isNotEmpty())TextButton(onClick=vm::parentFolder){Icon(Icons.Outlined.KeyboardArrowUp,null);Spacer(Modifier.width(8.dp));MarqueeTitle(vm.browserPath,surface=MaterialTheme.colorScheme.surfaceContainerLow,style=MaterialTheme.typography.labelLarge)}
            LazyColumn(Modifier.heightIn(max=480.dp)) {
                if(vm.files.isEmpty())item{Text("No files to display. Choose a project folder first.",Modifier.padding(16.dp))}
                items(vm.files){path->TextButton(onClick={if(path.startsWith('/'))vm.enterFolder(path.removePrefix("/"))else vm.browse((vm.browserPath.takeIf{it.isNotEmpty()}?.plus("/")?:"")+path)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){MarqueeTitle(path.removePrefix("/"),surface=MaterialTheme.colorScheme.surfaceContainerLow)}}
            }
        }
    }
    vm.selectedFile?.let{(path,text)->HydraBottomSheet(onDismiss={vm.selectedFile=null}){_->Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Description,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));MarqueeTitle(path,style=MaterialTheme.typography.titleLarge,surface=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.weight(1f))};Spacer(Modifier.height(16.dp));WorkToolText(text)}}}
}
@Composable private fun WorkChangesSheet(changes:List<FileChange>,onDismiss:()->Unit) {
    HydraBottomSheet(onDismiss=onDismiss){_->
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Difference,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text("Changes",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))}
            LazyColumn(Modifier.heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(changes.isEmpty())item{Text("No recorded file changes in this task.",Modifier.padding(16.dp))}
                items(changes){change->Column{MarqueeTitle(change.path,style=MaterialTheme.typography.titleMedium,surface=MaterialTheme.colorScheme.surfaceContainerLow);Text("Before",style=MaterialTheme.typography.labelMedium);WorkToolText(change.before.ifEmpty{"New file"});HorizontalDivider();Text("After",style=MaterialTheme.typography.labelMedium);WorkToolText(change.after.ifEmpty{"Empty or removed file"})}}
            }
        }
    }
}

@Preview(name="Task chat light")
@Preview(name="Task chat dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Task chat large text",fontScale=1.5f)
@Composable private fun TaskChatPreview() {
    val step=WorkStep("replace_in_file","{\"path\":\"app/MainActivity.kt\",\"old_text\":\"old label\",\"new_text\":\"new label\"}",id="preview-tool")
    HydraTheme{WorkChatScreen(WorkChatUiState("Improve the project’s startup","Agentic Hydra",listOf(Message("user","Inspect the app and improve startup."),Message("assistant","I’ll inspect the project, then make a focused change."),Message("tool","replace_in_file",id=step.id)),listOf(step),step.id,true,"Needs approval"),"",{},WorkChatActions()){TextButton(onClick={}){Text("Project model")}}}
}
