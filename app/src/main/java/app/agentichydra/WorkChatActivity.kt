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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject

private fun openWork(context:Context,id:String?=null) {
    context.startActivity(Intent(context,WorkChatActivity::class.java).apply{if(id==null)putExtra("newConversation",true)else putExtra("conversationId",id)})
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun WorkScreen(chat:HydraModel,vm:WorkModel) {
    val cs=MaterialTheme.colorScheme
    val context=LocalContext.current
    var query by rememberSaveable{mutableStateOf("")}
    var delete by remember{mutableStateOf<WorkRecord?>(null)}
    var approvals by rememberSaveable{mutableStateOf(false)}
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)runCatching{vm.selectProject(uri)}.onFailure{vm.error=it.message}}
    val filtered=remember(vm.records,query){vm.records.filter{it.title.contains(query,true)||it.projectName.contains(query,true)}}
    Scaffold(containerColor=cs.background,topBar={TopAppBar(title={Text("Work",style=MaterialTheme.typography.titleLarge)},
        colors=TopAppBarDefaults.topAppBarColors(containerColor=cs.background),actions={IconButton(onClick={approvals=true}){Icon(Icons.Outlined.Shield,"Tool approvals")}})},
        floatingActionButton={ExtendedFloatingActionButton(onClick={openWork(context,if(vm.running)vm.activeId else null)},
            icon={Icon(if(vm.running)Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Add,null)},text={Text(if(vm.running)"Open running task"else "New task")})}) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max=840.dp).fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {
                    Surface(onClick={folder.launch(null)},enabled=!vm.running,color=cs.surfaceContainer,shape=MaterialTheme.shapes.large) {
                        ListItem(headlineContent={MarqueeTitle(vm.projectName,surface=cs.surfaceContainer)},leadingContent={Icon(Icons.Outlined.FolderOpen,null,tint=cs.primary)},
                            trailingContent={Icon(Icons.Outlined.UnfoldMore,null)},colors=ListItemDefaults.colors(containerColor=cs.surfaceContainer))
                    }
                }
                vm.error?.let{error->item{WorkError(error){vm.error=null}}}
                if(vm.records.isNotEmpty())item {
                    OutlinedTextField(query,{query=it},singleLine=true,label={Text("Search tasks")},leadingIcon={Icon(Icons.Outlined.Search,null)},
                        shape=MaterialTheme.shapes.extraLarge,modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Task conversations",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(vertical=8.dp))
                }
                if(filtered.isEmpty())item {
                    Column(Modifier.fillMaxWidth().padding(vertical=48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        HydraIllustration(Modifier.size(100.dp))
                        Text(if(query.isBlank())"No tasks yet"else "No matching tasks",style=MaterialTheme.typography.titleLarge)
                        Text(if(query.isBlank())"Start a task, then keep the conversation going here."else "Try a different title or project.",style=MaterialTheme.typography.bodyMedium)
                    }
                }
                items(filtered,key={it.id}) { record ->
                    Surface(color=cs.surfaceContainerLow,shape=MaterialTheme.shapes.large,
                        modifier=Modifier.fillMaxWidth().combinedClickable(onClick={openWork(context,record.id)},onLongClickLabel="Delete task conversation",onLongClick={delete=record})) {
                        ListItem(headlineContent={MarqueeTitle(record.title,surface=cs.surfaceContainerLow)},
                            supportingContent={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text(record.status,style=MaterialTheme.typography.labelMedium,color=if(record.status=="Failed")cs.error else cs.primary)
                                MarqueeTitle(record.projectName,surface=cs.surfaceContainerLow,style=MaterialTheme.typography.bodySmall)
                            }},leadingContent={Icon(Icons.Outlined.Terminal,null,tint=cs.primary)},
                            trailingContent={IconButton(onClick={delete=record},enabled=!(vm.running&&vm.activeId==record.id)){Icon(Icons.Outlined.DeleteOutline,"Delete task history")}},
                            colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerLow))
                    }
                }
            }
        }
    }
    if(approvals)ApprovalSheet(vm){approvals=false}
    delete?.let{record->AlertDialog(onDismissRequest={delete=null},title={Text("Delete task conversation?")},text={Text("“${record.title}” and its messages will be removed. Project files are kept.")},
        confirmButton={TextButton(onClick={vm.deleteRecord(record);delete=null}){Text("Delete")}},dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}})}
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
    val conversationId:String="new",val canSend:Boolean=true,val attachments:List<MessageAttachment> = emptyList(),val attaching:Boolean=false)

class WorkChatActions(val onSend:()->Unit={},val onStop:()->Unit={},val onBack:()->Unit={},val onProject:()->Unit={},val onFiles:()->Unit={},
    val onChanges:()->Unit={},val onApprovals:()->Unit={},val onConnections:()->Unit={},val onApprove:(String,Boolean)->Unit={_,_->},val onDismissError:()->Unit={},
    val onCopy:(Message)->Unit={},val onShare:(Message)->Unit={},val onAttachFile:()->Unit={},val onAttachMedia:()->Unit={},
    val onRemoveAttachment:(String)->Unit={},val onRename:()->Unit={})

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
    val active=vm.activeId==id
    val saved=vm.records.firstOrNull{it.id==id}
    val state=if(active)WorkChatUiState(vm.task.ifBlank{"New task"},vm.projectName,vm.messages,vm.steps,vm.pendingApproval?.id,vm.running,vm.status,vm.error,id,attachments=vm.draftAttachments,attaching=vm.attaching)
        else WorkChatUiState(saved?.title?:"Task unavailable",saved?.projectName?:"",saved?.messages.orEmpty(),saved?.steps.orEmpty(),status=saved?.status?:"Deleted",conversationId=id,canSend=!vm.running&&saved!=null)
    fun activate()=vm.activeId==id||saved?.let{vm.viewRecord(it)}==true
    LaunchedEffect(vm,id) {
        vm.toasts.collect{event->
            val visible=(context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)!=false
            if(event.conversationId==id&&visible)Toast.makeText(context,event.message,Toast.LENGTH_LONG).show()
        }
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)runCatching{vm.selectProject(uri)}.onFailure{vm.error=it.message}}
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null&&activate())vm.attach(uri,media=false)}
    val mediaPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null&&activate())vm.attach(uri,media=true)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val actions=WorkChatActions(onBack=onBack,onSend={
        if(activate()&&vm.followUp(draft,chat)){draft="";if(Build.VERSION.SDK_INT>=33)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}
    },onStop={if(vm.activeId==id)vm.stop()},onProject={if(!vm.running&&activate())folder.launch(null)},
        onFiles={if(activate()){vm.refreshFiles();files=true}},onChanges={if(activate())changes=true},
        onApprovals={approvals=true},onConnections={connections=true},onApprove=vm::approveStep,onDismissError={vm.error=null},
        onAttachFile={if(!vm.running&&activate())filePicker.launch(arrayOf("text/*","application/json","application/xml","application/javascript","application/octet-stream"))},
        onAttachMedia={if(!vm.running&&activate())mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
        onRemoveAttachment=vm::removeAttachment,onRename={renameTitle=state.title;rename=true},
        onCopy={clipboard.setText(AnnotatedString(it.text))},onShare={message->runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message.text),"Share response"))}})
    WorkChatScreen(state,draft,{draft=it},actions){ComposerModelControl(chat,enabled=!vm.running){models=true}}
    if(models)ModelPickerSheet(chat,onConnections={models=false;connections=true},onDismiss={models=false})
    if(connections)ConnectionSheet(chat){connections=false}
    if(approvals)ApprovalSheet(vm){approvals=false}
    if(files)WorkFilesSheet(vm){files=false}
    if(changes)WorkChangesSheet(vm.changes){changes=false}
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
        MarqueeTitle(state.project,style=MaterialTheme.typography.labelSmall,color=cs.onSurfaceVariant,surface=cs.background)
    }}},navigationIcon={IconButton(onClick=actions.onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to task history")}},
        actions={Box{
            IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Task options")}
            DropdownMenu(menu,{menu=false}) {
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
                        FilledIconButton(onClick=if(state.running)actions.onStop else actions.onSend,enabled=state.running||(state.canSend&&!state.attaching&&(draft.isNotBlank()||state.attachments.isNotEmpty())),modifier=Modifier.size(48.dp)) {
                            Icon(if(state.running)Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,if(state.running)"Stop task"else "Send task message")
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
                Spacer(Modifier.height(24.dp));TextButton(onClick=actions.onProject){Icon(Icons.Outlined.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("Choose project")}
            }else ChatTimeline(ChatTimelineUiState(state.conversationId,state.messages),actions.onCopy,actions.onShare,
                modifier=Modifier.widthIn(max=760.dp).fillMaxSize(),toolContent={message,position,count->
                    state.steps.firstOrNull{it.id==message.id}?.let{step->WorkToolEntry(step,state.pendingId==step.id,position,count){allow->actions.onApprove(step.id,allow)}}
                },trailingContent={
                    if(state.running&&state.pendingId==null)Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(12.dp));ShimmerStatusText(if(state.status=="Thinking")"Thinking…"else "Working…")
                    }else if(!state.running)Text("${state.status}\u2002•\u2002Performed ${state.steps.size} action${if(state.steps.size==1)""else "s"}",style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
                })
        }
    }
}

@Composable fun WorkToolEntry(step:WorkStep,needsApproval:Boolean,position:Int=0,count:Int=1,onApprove:(Boolean)->Unit) {
    val cs=MaterialTheme.colorScheme
    var expanded by rememberSaveable(step.id){mutableStateOf(false)}
    val args=remember(step.arguments){runCatching{JSONObject(step.arguments)}.getOrDefault(JSONObject())}
    val title=remember(step.tool,step.arguments){toolLabel(step.tool,args)}
    val icon=when(step.tool){"web_search","fetch_url"->Icons.Outlined.Public;"run_command","git_status","git_diff"->Icons.Outlined.Terminal;"find_files","search_files"->Icons.Outlined.Search;"write_file","append_file","replace_in_file"->Icons.Outlined.Edit;else->Icons.Outlined.Description}
    Surface(color=cs.surfaceContainerHigh,shape=groupedRowShape(position,count),modifier=Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio=1f,stiffness=600f)).testTag("work-tool-${step.id}")) {
      Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(onClick={expanded=!expanded},color=cs.surfaceContainerHigh) {
            ListItem(headlineContent={MarqueeTitle(title,surface=cs.surfaceContainerHigh,style=MaterialTheme.typography.bodyMedium)},
                supportingContent={Text(if(needsApproval)"Needs your approval"else step.state,style=MaterialTheme.typography.labelSmall,color=if(step.state=="Failed")cs.error else cs.onSurfaceVariant)},
                leadingContent={Icon(icon,null,tint=cs.primary)},trailingContent={if(step.state=="Running")CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)},
                colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerHigh))
        }
        if(needsApproval)Surface(color=cs.surfaceContainer,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Shield,null,tint=cs.primary);Spacer(Modifier.width(8.dp));Text("Allow this action?",style=MaterialTheme.typography.titleSmall)}
                WorkToolText(step.arguments.take(6000))
                if(step.tool in listOf("run_command","git_status","git_diff"))Text("Runs in Termux with its file and network access.",style=MaterialTheme.typography.bodySmall)
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Button(onClick={onApprove(true)},shape=groupedRowShape(0,2),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Allow once")}
                    FilledTonalButton(onClick={onApprove(false)},shape=groupedRowShape(1,2),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Decline")}
                }
            }
        }else if(expanded) {
            val details=listOf("Arguments" to step.arguments.take(6000))+
                if(step.output.isNotBlank())listOf("Result" to step.output)else emptyList()
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp).padding(bottom=12.dp).testTag("work-tool-${step.id}-details"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                details.forEachIndexed{index,(label,text)->
                    Surface(color=cs.surfaceContainer,shape=groupedRowShape(index,details.size),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(label,style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
                            WorkToolText(text)
                        }
                    }
                }
            }
        }
    }
    }
}

private fun toolLabel(name:String,args:JSONObject):String {
    val path=args.optString("path")
    return when(name) {
        "list_files"->"List ${path.ifBlank{"project files"}}"
        "read_file"->"Read $path"
        "write_file"->"Write $path"
        "run_command"->args.optString("command").ifBlank{"Run command"}
        "search_files"->"Search project: ${args.optString("query")}"
        "find_files"->"Find ${args.optString("pattern")}"
        "file_info"->"Inspect $path"
        "create_directory"->"Create directory $path"
        "append_file"->"Append to $path"
        "replace_in_file"->"Edit $path"
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

@Composable private fun WorkToolText(text:String){SelectionContainer{Text(text,style=MaterialTheme.typography.bodySmall,
    modifier=Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp),softWrap=false)}}
@Composable private fun WorkError(text:String,onDismiss:()->Unit){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.large){Row(Modifier.padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium);IconButton(onClick=onDismiss){Icon(Icons.Outlined.Close,"Dismiss error")}}}}

@Composable private fun WorkFilesSheet(vm:WorkModel,onDismiss:()->Unit) {
    HydraBottomSheet(onDismiss=onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Text("Project files",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(8.dp))
            if(vm.browserPath.isNotEmpty())TextButton(onClick=vm::parentFolder){Icon(Icons.Outlined.KeyboardArrowUp,null);Spacer(Modifier.width(8.dp));MarqueeTitle(vm.browserPath,surface=MaterialTheme.colorScheme.surfaceContainerLow,style=MaterialTheme.typography.labelLarge)}
            LazyColumn(Modifier.heightIn(max=480.dp)) {
                if(vm.files.isEmpty())item{Text("No files to display. Choose a project folder first.",Modifier.padding(16.dp))}
                items(vm.files){path->TextButton(onClick={if(path.startsWith('/'))vm.enterFolder(path.removePrefix("/"))else vm.browse((vm.browserPath.takeIf{it.isNotEmpty()}?.plus("/")?:"")+path)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){MarqueeTitle(path.removePrefix("/"),surface=MaterialTheme.colorScheme.surfaceContainerLow)}}
            }
        }
    }
    vm.selectedFile?.let{(path,text)->HydraBottomSheet(onDismiss={vm.selectedFile=null}){_->Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom=24.dp)){MarqueeTitle(path,style=MaterialTheme.typography.titleLarge,surface=MaterialTheme.colorScheme.surfaceContainerLow);WorkToolText(text)}}}
}
@Composable private fun WorkChangesSheet(changes:List<FileChange>,onDismiss:()->Unit) {
    HydraBottomSheet(onDismiss=onDismiss){_->
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)) {
            Text("Changes",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(8.dp))
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
