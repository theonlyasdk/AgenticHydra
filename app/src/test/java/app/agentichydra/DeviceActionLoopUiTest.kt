package app.agentichydra

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

class DeviceActionLoopUiTest:AppUiTestBase() {
    @Suppress("DEPRECATION")
    @Test fun deviceCallsWaitForTheTaskUiAndFullAccessStillRequiresAndroidPermission(){
        val app=controller.get().application as HydraApplication
        val work=app.models[WorkModel::class.java];val chat=app.models[HydraModel::class.java]
        chat.chooseModel("test/alpha");work.mode=ApprovalMode.FULL
        val calls=AtomicInteger()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path?.endsWith("/models")==true)return MockResponse().setBody("""{"data":[{"id":"test/alpha"}]}""")
            val turn=calls.incrementAndGet()
            val message=if(turn%2==0)JSONObject().put("role","assistant").put("content","I received the device result.")
                else JSONObject().put("role","assistant").put("content","I’ll check the device.").put("tool_calls",JSONArray().put(JSONObject().put("id","device-$turn").put("type","function")
                    .put("function",JSONObject().put("name",if(turn==1)"device_info"else "get_location").put("arguments",if(turn==1)"{}"else "{}"))))
            return MockResponse().setBody(JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message))).toString())
        }}
        var host by mutableStateOf(false)
        controller.get().setContent{HydraTheme{if(host)DeviceActionHost(work)else Text("Task is in the background")}}
        compose.runOnIdle{assertTrue(work.followUp("Check device information.",chat))}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();work.pendingDeviceAction!=null}
        assertTrue(work.running);assertEquals("Waiting for device",work.status)
        compose.runOnIdle{host=true}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();!work.running}
        assertEquals("Done",work.steps.last().state)
        assertTrue(JSONObject(work.steps.last().output).has("api_level"))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        compose.runOnIdle{assertTrue(work.followUp("Check my approximate location.",chat))}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();work.pendingDeviceAction?.permissionLaunched==true}
        val permission=shadowOf(controller.get()).lastRequestedPermission
        assertTrue(permission.requestedPermissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        compose.runOnIdle{controller.get().onRequestPermissionsResult(permission.requestCode,permission.requestedPermissions,IntArray(permission.requestedPermissions.size){PackageManager.PERMISSION_DENIED})}
        compose.waitUntil(15000){ShadowLooper.idleMainLooper();!work.running}
        assertEquals("Failed",work.steps.last().state)
        assertTrue(work.steps.last().output.contains("permission was denied"))
        assertNull(work.pendingDeviceAction)
    }
}
