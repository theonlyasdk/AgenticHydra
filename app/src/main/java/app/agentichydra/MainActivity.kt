package app.agentichydra

import android.os.Bundle
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        when(getSharedPreferences("appearance",MODE_PRIVATE).getString("mode","SYSTEM")) {
            "LIGHT"->setTheme(R.style.HydraStartingLight)
            "DARK"->setTheme(R.style.HydraStartingDark)
        }
        val splash=installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setOnExitAnimationListener { provider ->
            // Some hand-offs ship a splash view without an icon view; never crash on the cosmetic zoom.
            runCatching {
                if(!ValueAnimator.areAnimatorsEnabled())provider.remove()
                else {
                    provider.iconView.apply {
                        scaleX=1.25f;scaleY=1.25f
                        animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    provider.view.animate().alpha(0f).setStartDelay(140).setDuration(180)
                        .setInterpolator(DecelerateInterpolator()).withEndAction { provider.remove() }.start()
                }
            }.onFailure { provider.remove() }
        }
        enableEdgeToEdge()
        setContent { HydraTheme { HydraApp() } }
    }
}

@Composable fun HydraMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "Hydra, three paths coming together" }) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w*.5f,h*.83f); lineTo(w*.5f,h*.24f)
            moveTo(w*.5f,h*.65f); cubicTo(w*.24f,h*.65f,w*.17f,h*.46f,w*.17f,h*.18f)
            moveTo(w*.5f,h*.65f); cubicTo(w*.76f,h*.65f,w*.83f,h*.46f,w*.83f,h*.18f)
        }
        drawPath(path,color,style=Stroke(w*.065f,cap=StrokeCap.Round))
        for (x in listOf(.17f,.5f,.83f)) {
            val y=if(x==.5f) .24f else .18f
            drawLine(color,Offset(w*(x-.085f),h*(y+.075f)),Offset(w*x,h*y),w*.065f,StrokeCap.Round)
            drawLine(color,Offset(w*(x+.085f),h*(y+.075f)),Offset(w*x,h*y),w*.065f,StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatScreen(vm: HydraModel) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    var draft by rememberSaveable { mutableStateOf("") }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    var exportText by rememberSaveable { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = exportText
        exportText = ""
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open export file.")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                snackbar.showSnackbar("Conversation exported")
            } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Could not export conversation.") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                vm.attachment = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if(cursor.moveToFirst() && index >= 0) cursor.getString(index) else "attachment.txt"
                    } ?: "attachment.txt"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(131073)
                        var length = 0
                        while (length < buffer.size) {
                            val count = input.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        buffer.copyOf(length)
                    } ?: error("Could not open file")
                    require(bytes.size <= 131072) { "Choose a text or code file smaller than 128 KB." }
                    require(!bytes.contains(0.toByte())) { "Choose a text or code file." }
                    name to bytes.toString(Charsets.UTF_8)
                }
            } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Could not attach that file.") }
        }
    }
    ModalNavigationDrawer(drawerState=drawer, drawerContent={
        ModalDrawerSheet(drawerContainerColor=cs.surface) {
            Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically) {
                HydraMark(Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text("Your conversations",style=MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(onClick={ vm.newChat(); draft=""; scope.launch { drawer.close() } },modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth().heightIn(min=48.dp)) {
                Icon(Icons.Outlined.Add,null); Spacer(Modifier.width(8.dp)); Text("New chat")
            }
            Spacer(Modifier.height(24.dp))
            if(vm.conversations.isEmpty()) Text("Your conversations will appear here.\nThey’re saved on this device.",Modifier.padding(24.dp),color=cs.onSurfaceVariant)
            LazyColumn {
                items(vm.conversations,key={it.id}) { c ->
                    ConversationHistoryRow(ConversationRowUiState(c.title, c.id == vm.activeId),
                        onOpen = { vm.open(c); draft=""; scope.launch { drawer.close() } },
                        onRename = { renameTitle = c.title; renameTarget = c },
                        onExport = {
                            val conversation = vm.conversations.firstOrNull { it.id == c.id } ?: c
                            exportText = (if(vm.activeId==c.id) conversation.copy(messages=vm.messages) else conversation).exportMarkdown()
                            exporter.launch(c.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Conversation" } + ".md")
                        },
                        onDelete = { deleteTarget = c })
                }
            }
        }
    }) {
        Scaffold(containerColor=cs.background,snackbarHost={SnackbarHost(snackbar)},
            topBar={
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        HintIconButton("Open chat history",Icons.Outlined.Menu,onClick={scope.launch { drawer.open() }})
                        Spacer(Modifier.width(8.dp))
                        Text("hydra",style=MaterialTheme.typography.hydraTitle)
                        Spacer(Modifier.weight(1f))
                        HintIconButton("Start a new chat",Icons.AutoMirrored.Outlined.NoteAdd,onClick={vm.newChat();draft=""})
                    }
                }
            },bottomBar={
                Column(Modifier.imePadding().widthIn(max=840.dp).fillMaxWidth()) {
                    vm.error?.let { error ->
                        Surface(color=cs.errorContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
                            Row(Modifier.padding(start=16.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(error,Modifier.weight(1f),color=cs.onErrorContainer,style=MaterialTheme.typography.bodyMedium)
                                IconButton(onClick={vm.error=null}) { Icon(Icons.Outlined.Close,"Dismiss error",tint=cs.onErrorContainer) }
                            }
                        }
                    }
                    Surface(color=cs.surfaceContainer,shape=RoundedCornerShape(28.dp),shadowElevation=2.dp,modifier=Modifier.padding(horizontal=16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            vm.attachment?.let { attachment ->
                                InputChip(selected=true,onClick={},label={Text(attachment.first,maxLines=1,overflow=TextOverflow.Ellipsis)},
                                    trailingIcon={IconButton(onClick={vm.attachment=null},modifier=Modifier.size(48.dp)){Icon(Icons.Outlined.Close,"Remove attachment")}},modifier=Modifier.padding(start=8.dp))
                            }
                            TextField(value=draft,onValueChange={draft=it},placeholder={Text("Message Hydra",color=cs.onSurfaceVariant)},
                                modifier=Modifier.fillMaxWidth().heightIn(min=56.dp,max=176.dp),enabled=!vm.busy,
                                colors=TextFieldDefaults.colors(focusedContainerColor=cs.surfaceContainer,unfocusedContainerColor=cs.surfaceContainer,
                                    disabledContainerColor=cs.surfaceContainer,focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,disabledIndicatorColor=androidx.compose.ui.graphics.Color.Transparent),
                                textStyle=MaterialTheme.typography.bodyLarge)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={picker.launch(arrayOf("text/*","application/json","application/javascript","application/xml","application/octet-stream"))},enabled=!vm.busy) {
                                    Icon(Icons.Outlined.Add,"Attach a text or code file",tint=cs.onSurfaceVariant)
                                }
                                Box(Modifier.weight(1f)) { ComposerModelControl(vm) { showModels=true } }
                                FilledIconButton(onClick={if(vm.busy)vm.stop() else if(vm.send(draft))draft=""},enabled=vm.busy||draft.isNotBlank(),
                                    modifier=Modifier.size(48.dp),shape=CircleShape) {
                                    Icon(if(vm.busy)Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,if(vm.busy)"Stop response" else "Send message")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
                if(vm.messages.isEmpty()) {
                    ChatWelcome(rememberChatWelcome()) {draft=it}
                } else {
                    val clipboard=LocalClipboardManager.current
                    ChatTimeline(ChatTimelineUiState(vm.activeId,vm.messages,if(vm.busy)vm.messages.lastOrNull()?.id else null),
                        onCopy={message->clipboard.setText(AnnotatedString(message.text));scope.launch{snackbar.showSnackbar("Response copied")}},
                        onShare={message->
                            val share=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message.text)
                            runCatching{context.startActivity(Intent.createChooser(share,"Share response"))}
                                .onFailure{scope.launch{snackbar.showSnackbar("Couldn’t open sharing.")}}
                        },toolContent={message,_,_->vm.steps.firstOrNull{it.id==message.id}?.let{WorkToolEntry(it,false){}}},
                        trailingContent={
                            if(vm.busy&&vm.messages.lastOrNull()?.role=="tool")Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(12.dp))
                                ShimmerStatusText("Working…",style=MaterialTheme.typography.bodyLarge,maxLines=2)
                            }
                        },modifier=Modifier.widthIn(max=760.dp).fillMaxSize(),listState=listState,
                        messageActions=MessageLongPressActions(onCopy={message->clipboard.setText(AnnotatedString(message.text))},
                            onRevert={message->vm.revert(message.id)},
                            onRegenerate={message->vm.regenerate(message.id)}))
                }
            }
        }
    }
    if(showConnection) ConnectionSheet(vm,onDismiss={showConnection=false})
    if(showModels) ModelPickerSheet(vm,onConnections={showModels=false;showConnection=true},onDismiss={showModels=false})
    vm.pendingQuestions?.let{request->AgentQuestionSheet(request,{vm.answerQuestions(request.id,it)},{vm.answerQuestions(request.id,null)})}
    renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename conversation") },
            text = { OutlinedTextField(value = renameTitle, onValueChange = { renameTitle = it },
                label = { Text("Conversation name") }, singleLine = true, shape = MaterialTheme.shapes.medium) },
            confirmButton = { TextButton(enabled = renameTitle.isNotBlank(), onClick = { vm.renameConversation(target.id, renameTitle); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete conversation?") },
            text = { Text("“${target.title}” and its messages will be removed from this device.") },
            confirmButton = { TextButton(onClick = { if (vm.activeId == target.id) draft = ""; vm.deleteConversation(target.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
    }
}

@Composable internal fun Starter(icon: ImageVector,title: String,prompt: String,onChoose:(String)->Unit) {
    val cs=MaterialTheme.colorScheme
    Surface(onClick={onChoose(prompt)},color=cs.background,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
        Row(Modifier.padding(vertical=12.dp,horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(20.dp),tint=cs.primary);Spacer(Modifier.width(16.dp));MarqueeTitle(title,Modifier.weight(1f),surface=cs.background,style=MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.NorthEast,null,Modifier.size(16.dp),tint=cs.onSurfaceVariant)
        }
    }
}
