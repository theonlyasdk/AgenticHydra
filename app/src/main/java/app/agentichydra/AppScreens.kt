package app.agentichydra

import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable fun HydraApp() {
    val application=LocalContext.current.applicationContext as HydraApplication
    val chat=application.models[HydraModel::class.java]; val work=application.models[WorkModel::class.java]
    var destination by rememberSaveable {mutableStateOf("Chat")}
    // Preserve the bar in composition so Material animates each selection change.
    Scaffold(contentWindowInsets=WindowInsets(0),bottomBar={HydraNavigation(destination){destination=it}}){padding->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when(destination){"Work"->WorkScreen(chat,work);"User"->UserScreen(chat,work);else->ChatScreen(chat)}
        }
    }
}
@Composable private fun HydraNavigation(selected:String,onNavigate:(String)->Unit) {
    NavigationBar(containerColor=MaterialTheme.colorScheme.background,tonalElevation=0.dp) {
        listOf("Chat" to Icons.Outlined.ChatBubbleOutline,"Work" to Icons.Outlined.Terminal,"User" to Icons.Outlined.PersonOutline).forEach{(label,icon)->
            NavigationBarItem(selected=selected==label,onClick={onNavigate(label)},icon={Icon(icon,null)},label={Text(label)},colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.primaryContainer))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ApprovalSheet(work:WorkModel,onDismiss:()->Unit){
    val cs=MaterialTheme.colorScheme;var confirm by remember{mutableStateOf(false)}
    HydraBottomSheet(onDismiss=onDismiss,containerColor=cs.surfaceContainer){ dismiss ->
      Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Tool approvals",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(8.dp))
        ToolApprovalOptions(ToolApprovalsUiState(work.mode,!work.running)){mode->if(mode==ApprovalMode.SESSION)confirm=true else{work.mode=mode;dismiss()}}
        Spacer(Modifier.height(16.dp))
      }
      if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Allow tools for the next task?")},text={Text("The agent can write files and run commands without further approval. Shell commands inherit Termux’s file and network access. Approval resets after the task.")},confirmButton={TextButton(onClick={work.mode=ApprovalMode.SESSION;confirm=false;dismiss()}){Text("Allow")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UserScreen(chat:HydraModel,work:WorkModel){
    val cs=MaterialTheme.colorScheme;val context=LocalContext.current
    val appearanceState=rememberAppearanceState()
    var appearance by rememberSaveable{mutableStateOf(false)}
    var connections by rememberSaveable{mutableStateOf(false)};var setup by rememberSaveable{mutableStateOf(false)};var approvals by rememberSaveable{mutableStateOf(false)};var privacy by rememberSaveable{mutableStateOf(false)}
    var permissionResult by remember{mutableStateOf("")};var allowed by remember{mutableStateOf(TermuxBridge.allowed(context))}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->allowed=granted;permissionResult=if(granted)"Permission granted"else "Enable Run Commands permission in Android settings after installing Termux."}
    Scaffold(containerColor=cs.background,topBar={TopAppBar(title={Text("User",style=MaterialTheme.typography.titleLarge)},colors=TopAppBarDefaults.topAppBarColors(containerColor=cs.background))}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            item{Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=CircleShape,color=cs.primaryContainer,modifier=Modifier.size(64.dp)){Box(contentAlignment=Alignment.Center){HydraMark(Modifier.size(32.dp))}};Spacer(Modifier.width(16.dp));Text("Agentic Hydra",style=MaterialTheme.typography.titleLarge)}}
            item{SettingsGroup {
                SettingsEntry(Icons.Outlined.Hub,"Connections",chat.provider.label){connections=true}
                GroupDivider()
                SettingsEntry(Icons.Outlined.Terminal,"Termux",if(allowed)"Connected"else "Set up"){setup=true}
                GroupDivider()
                SettingsEntry(Icons.Outlined.Shield,"Tool approvals",work.mode.title){approvals=true}
            }}
            item{SettingsGroup {
                SettingsEntry(Icons.Outlined.Contrast,"Appearance",appearanceState.themeMode.label){appearance=true}
                GroupDivider();SettingsEntry(Icons.Outlined.Language,"Language","English")
                GroupDivider();SettingsEntry(Icons.Outlined.PrivacyTip,"Privacy & storage",null){privacy=true}
                GroupDivider();SettingsEntry(Icons.Outlined.Info,"Version","1.2.0")
            }}
        }
    }
    if(appearance)HydraBottomSheet(onDismiss={appearance=false},containerColor=cs.surfaceContainerLow){ _ ->
        val prefs=context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Contrast,null,tint=cs.primary);Spacer(Modifier.width(12.dp));Text("Appearance",style=MaterialTheme.typography.titleLarge)}
            AppearanceOptions(appearanceState,Build.VERSION.SDK_INT>=31,
                onThemeChange={prefs.edit().putString("mode",it.name).apply()},
                onDynamicColorChange={prefs.edit().putBoolean("dynamic",it).apply()})
        }
    }
    if(connections)ConnectionSheet(chat){connections=false}
    if(approvals)ApprovalSheet(work){approvals=false}
    if(privacy)HydraBottomSheet(onDismiss={privacy=false},containerColor=cs.surfaceContainer){ _ ->Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){Text("Privacy & storage",style=MaterialTheme.typography.titleLarge);Text("Chats and task history stay on this device. API keys use Android Keystore encryption. Prompts, attachments, and tool results go to your selected provider. Cloud backup and device transfer are disabled.");Text("Text and code attachments: 128 KB. Project files: 256 KB. No Hydra account required.");Spacer(Modifier.height(24.dp))}}
    if(setup)HydraBottomSheet(onDismiss={setup=false},containerColor=cs.surfaceContainer){ _ ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Terminal,null,tint=cs.primary);Spacer(Modifier.width(12.dp));Text("Termux setup",style=MaterialTheme.typography.titleLarge)}
            Text("Install and open Termux, then run:")
            MarkdownBody("```bash\ntermux-setup-storage\nmkdir -p ~/.termux\necho 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings\n```" )
            Text("Allow storage access. Install the packages your project needs, such as Git, Python, or Node.js.")
            Button(onClick={permission.launch("com.termux.permission.RUN_COMMAND")},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Icon(Icons.Outlined.Security,null);Spacer(Modifier.width(8.dp));Text("Grant command permission")}
            if(permissionResult.isNotBlank())Text(permissionResult)
            TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/termux/termux-app#installation")))}){Icon(Icons.AutoMirrored.Outlined.OpenInNew,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Install Termux")}
            Text("Stopping the agent prevents further steps. Commands already running must be stopped in Termux.",style=MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable private fun SettingsGroup(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),content=content)}
@Composable private fun GroupDivider(){Spacer(Modifier.height(4.dp))}
@Composable private fun SettingsEntry(icon:ImageVector,title:String,value:String?,onClick:(()->Unit)?=null){
    val cs=MaterialTheme.colorScheme
    val row:@Composable ()->Unit={ListItem(headlineContent={Text(title)},leadingContent={Icon(icon,null,tint=cs.primary)},trailingContent={Row(verticalAlignment=Alignment.CenterVertically){value?.let{Text(it,style=MaterialTheme.typography.labelMedium,maxLines=1)};if(onClick!=null){Spacer(Modifier.width(4.dp));Icon(Icons.Outlined.ChevronRight,null)}}},colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerHigh))}
    if(onClick!=null)Surface(onClick=onClick,color=cs.surfaceContainerHigh,shape=MaterialTheme.shapes.extraSmall,content=row)else Surface(color=cs.surfaceContainerHigh,shape=MaterialTheme.shapes.extraSmall,content=row)
}
