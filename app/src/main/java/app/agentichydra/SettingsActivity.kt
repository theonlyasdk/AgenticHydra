package app.agentichydra

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class SettingsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        val page=intent.getStringExtra("page").takeIf{it in setOf("termux","privacy","storage","about")}?:"about"
        setContent{HydraTheme{SettingsPage(page){finish()}}}
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun SettingsPage(page:String,onBack:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val context=LocalContext.current
    val title=when(page){"termux"->"Termux setup";"privacy"->"Privacy";"storage"->"Storage";else->"About"}
    var permissionResult by remember{mutableStateOf("")}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        permissionResult=if(granted)"Permission granted"else "Enable Run Commands permission in Android settings after installing Termux."
    }
    Scaffold(containerColor=cs.background,topBar={TopAppBar(title={Text(title)},
        navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}},
        colors=TopAppBarDefaults.topAppBarColors(containerColor=cs.background))}){padding->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter){
            Column(Modifier.widthIn(max=840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                when(page) {
                    "termux"->{
                        Text("Run project commands on your phone",style=MaterialTheme.typography.titleLarge)
                        Text("Install and open Termux, then run:")
                        MarkdownBody("```bash\ntermux-setup-storage\nmkdir -p ~/.termux\necho 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings\n```")
                        Text("Allow storage access, then install the tools your project needs, such as Git, Python, or Node.js.")
                        Button(onClick={permission.launch("com.termux.permission.RUN_COMMAND")},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                            Icon(Icons.Outlined.Security,null);Spacer(Modifier.width(8.dp));Text("Grant command permission")
                        }
                        if(permissionResult.isNotBlank())Text(permissionResult)
                        OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/termux/termux-app#installation")))},modifier=Modifier.fillMaxWidth()){Text("Install Termux")}
                        Text("Commands inherit Termux’s file and network permissions. Stopping a task prevents further steps; commands already running may need to be stopped in Termux.")
                    }
                    "privacy"->{
                        Text("Your providers, your data",style=MaterialTheme.typography.titleLarge)
                        Text("Chats and task history stay in app-private storage on this device. Saved API keys use Android Keystore encryption. Cloud backup and device transfer are disabled.")
                        Text("Prompts, relevant history, attachments, and tool results are sent to your selected provider. Web searches go to public search services, and page requests go to the requested hosts.")
                        Text("Hydra has no account service of its own. Each provider’s privacy policy and usage charges apply to requests you send there.")
                    }
                    "storage"->{
                        Text("Stored on this device",style=MaterialTheme.typography.titleLarge)
                        Text("Hydra retains up to 100 Chat conversations and 40 Work conversations. Export important conversations from history before deleting them.")
                        Text("Text and code attachments: 128 KiB each. Images: 4 MiB each. Up to four attachments per message. Native project text tools: 256 KiB per file.")
                        Text("Each task keeps its selected project folder. New tasks reuse the last folder you chose. Deleting a conversation keeps your project files.")
                    }
                    else->{
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            HydraIllustration(Modifier.size(112.dp))
                            Text("Agentic Hydra",style=MaterialTheme.typography.hydraTitle,modifier=Modifier.weight(1f))
                        }
                        Text("A little intelligence. A human in control.",style=MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Made with",modifier=Modifier.align(Alignment.CenterVertically))
                            Icon(Icons.Outlined.Favorite,"love",Modifier.size(20.dp).align(Alignment.CenterVertically),tint=cs.primary)
                            Text("by",modifier=Modifier.align(Alignment.CenterVertically))
                            TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/theonlyasdk")))}){Text("theonlyasdk")}
                        }
                        Text("© 2026",style=MaterialTheme.typography.bodyMedium)
                        Text("Version 1.2.0 · MIT license",style=MaterialTheme.typography.bodySmall,color=cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview(name="About light")
@Preview(name="About dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="About large text",fontScale=1.5f)
@Composable private fun SettingsPagePreview(){HydraTheme{SettingsPage("about",{})}}
