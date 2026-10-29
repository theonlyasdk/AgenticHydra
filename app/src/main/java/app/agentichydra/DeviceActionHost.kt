package app.agentichydra

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException

/** Only a visible task can request Android permissions or launch device UI. */
@Composable internal fun DeviceActionHost(vm:WorkModel) {
    val activity=LocalContext.current as? Activity?:return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    var permissionRequestId by rememberSaveable{mutableStateOf<String?>(null)}
    val request=vm.pendingDeviceAction
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        vm.pendingDeviceAction?.takeIf{it.id==permissionRequestId}?.let{pending->
            if(DeviceTools.permissions(pending.tool).any{grants[it]!=true&&DeviceTools.missingPermissions(activity,pending.tool).contains(it)})
                vm.completeDeviceAction(pending.id,WorkToolResult("Tool error: Android permission was denied. Do not retry without new user instructions.",failed=true))
            else vm.devicePermissionResolved(pending.id)
        }
        permissionRequestId=null
    }
    LaunchedEffect(request?.id,request?.permissionLaunched,resumed){
        if(request==null||!resumed)return@LaunchedEffect
        val missing=DeviceTools.missingPermissions(activity,request.tool)
        if(missing.isNotEmpty()) {
            if(!request.permissionLaunched){permissionRequestId=request.id;vm.markDevicePermissionLaunched(request.id);permission.launch(missing.toTypedArray())}
        }else{
            try{vm.completeDeviceAction(request.id,DeviceTools.execute(activity,request.tool,org.json.JSONObject(request.arguments),vm.projectFileSource()))}
            catch(e:CancellationException){throw e}
            catch(e:Exception){vm.completeDeviceAction(request.id,WorkToolResult("Tool error: ${e.message?:"Device action failed."}",failed=true))}
        }
    }
}
