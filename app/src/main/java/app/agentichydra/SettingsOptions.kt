package app.agentichydra

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable fun AppearanceOptions(state:AppearanceUiState,supportsDynamicColor:Boolean,onThemeChange:(AppThemeMode)->Unit,onDynamicColorChange:(Boolean)->Unit,onMarqueeChange:(Boolean)->Unit) {
    val cs=MaterialTheme.colorScheme
    val count=AppThemeMode.entries.size+2
    Column(Modifier.fillMaxWidth().selectableGroup(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        AppThemeMode.entries.forEachIndexed { index,mode ->
            val selected=state.themeMode==mode
            val color=if(selected)cs.primaryContainer else cs.surfaceContainerHigh
            Surface(color=color,shape=groupedRowShape(index,count),modifier=Modifier.fillMaxWidth()
                .selectable(selected=selected,role=Role.RadioButton,onClick={onThemeChange(mode)})) {
                ListItem(headlineContent={Text(mode.label)},
                    supportingContent=if(mode==AppThemeMode.SYSTEM){{Text("Follow your device’s appearance")}}else null,
                    leadingContent={Icon(when(mode){AppThemeMode.SYSTEM->Icons.Outlined.Contrast;AppThemeMode.LIGHT->Icons.Outlined.LightMode;AppThemeMode.DARK->Icons.Outlined.DarkMode},null,tint=cs.primary)},
                    trailingContent={RadioButton(selected=selected,onClick=null)},
                    colors=ListItemDefaults.colors(containerColor=color,headlineColor=if(selected)cs.onPrimaryContainer else cs.onSurface,
                        supportingColor=if(selected)cs.onPrimaryContainer else cs.onSurfaceVariant))
            }
        }
        Surface(color=cs.surfaceContainerHigh,shape=groupedRowShape(count-2,count)) {
            ListItem(headlineContent={Text("Device colors")},supportingContent={Text("Use Android’s wallpaper palette")},
                leadingContent={Icon(Icons.Outlined.Palette,null,tint=cs.primary)},
                trailingContent={Switch(checked=state.dynamicColor,onCheckedChange=onDynamicColorChange,enabled=supportsDynamicColor)},
                colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerHigh))
        }
        Surface(color=cs.surfaceContainerHigh,shape=groupedRowShape(count-1,count)) {
            ListItem(headlineContent={Text("Scroll long text")},supportingContent={Text("Animate titles that overflow")},
                leadingContent={Icon(Icons.Outlined.SwapHoriz,null,tint=cs.primary)},
                trailingContent={Switch(checked=state.marquee,onCheckedChange=onMarqueeChange)},
                colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerHigh))
        }
    }
}

data class ToolApprovalsUiState(val selected:ApprovalMode,val enabled:Boolean=true)

@Composable fun ToolApprovalOptions(state:ToolApprovalsUiState,onSelect:(ApprovalMode)->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        ApprovalMode.entries.forEachIndexed { index,mode ->
            val content:@Composable ()->Unit={
                val cs=MaterialTheme.colorScheme
                val selected=state.selected==mode
                val warning=mode==ApprovalMode.FULL
                val color=if(warning)cs.tertiaryContainer else if(selected)cs.primaryContainer else cs.surfaceContainerHigh
                val foreground=if(warning)cs.onTertiaryContainer else if(selected)cs.onPrimaryContainer else cs.onSurface
                Surface(onClick={onSelect(mode)},enabled=state.enabled,color=color,shape=groupedRowShape(index,ApprovalMode.entries.size),
                    modifier=Modifier.fillMaxWidth().semantics{this.selected=selected}) {
                    ListItem(headlineContent={Text(mode.title)},supportingContent={Text(mode.detail)},
                        leadingContent={Icon(if(warning)Icons.Outlined.WarningAmber else if(mode==ApprovalMode.SESSION)Icons.Outlined.Bolt else Icons.Outlined.Shield,null,tint=if(warning)cs.tertiary else cs.primary)},
                        trailingContent={if(selected)Icon(Icons.Outlined.Check,"Selected",tint=foreground)},
                        colors=ListItemDefaults.colors(containerColor=color,headlineColor=foreground,supportingColor=if(warning||selected)foreground else cs.onSurfaceVariant))
                }
            }
            if(mode==ApprovalMode.FULL)HydraWarningTheme(content)else content()
        }
    }
}

@Preview(name="Appearance light")
@Preview(name="Appearance dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Appearance large text",fontScale=1.5f)
@Composable private fun AppearanceOptionsPreview(){HydraTheme{AppearanceOptions(AppearanceUiState(AppThemeMode.SYSTEM),true,{},{},{})}}

@Preview(name="Approvals light")
@Preview(name="Approvals dark",uiMode=Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Approvals large text",fontScale=1.5f)
@Composable private fun ToolApprovalOptionsPreview(){HydraTheme{ToolApprovalOptions(ToolApprovalsUiState(ApprovalMode.CHANGES),{})}}
