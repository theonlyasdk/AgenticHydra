package app.agentichydra

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TermuxResult(val exitCode:Int,val stdout:String,val stderr:String) {
    fun render()="Exit code: $exitCode\n$stdout\n$stderr".take(32000)
}

object TermuxBridge {
    val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxResult>>()
    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo("com.termux",0); true }.getOrDefault(false)
    fun allowed(context: Context) = context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
    suspend fun execute(context: Context, directory: String, command: String): TermuxResult {
        check(installed(context)) { "Install Termux from F-Droid or its official GitHub releases first." }
        check(allowed(context)) { "Grant Hydra the Termux Run Commands permission in User → Termux setup." }
        val token=UUID.randomUUID().toString()
        val result=CompletableDeferred<TermuxResult>()
        pending[token]=result
        val callbackIntent=Intent(context,TermuxResultReceiver::class.java).setAction("app.agentichydra.RESULT.$token")
        val flags=PendingIntent.FLAG_ONE_SHOT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
        val callback=PendingIntent.getBroadcast(context,0,callbackIntent,flags)
        try {
            val intent=Intent("com.termux.RUN_COMMAND").setClassName("com.termux","com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",arrayOf("-lc",command))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR",directory)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND",true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",callback)
            context.startService(intent)
            return try {withTimeout(180_000) {result.await()}}
            catch(_:TimeoutCancellationException){throw IOException("Termux did not return a result within three minutes. The command may still be running; check Termux before retrying.")}
        } finally { pending.remove(token); callback.cancel() }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val token=intent.action?.removePrefix("app.agentichydra.RESULT.") ?: return
        val result=TermuxBridge.pending.remove(token) ?: return
        val bundle=intent.getBundleExtra("result")
        try {result.complete(parseTermuxResult(bundle))}
        catch(e:IOException){result.completeExceptionally(e)}
    }
}

internal fun parseTermuxResult(bundle:android.os.Bundle?):TermuxResult {
    if(bundle==null)throw IOException("Termux returned no result bundle.")
    // The plugin status uses Activity.RESULT_OK (-1), independently of the process exit code.
    val error=bundle.getInt("err",Activity.RESULT_OK)
    if(error!=Activity.RESULT_OK)throw IOException(bundle.getString("errmsg")?.takeIf{it.isNotBlank()}
        ?:"Termux could not execute this command (error $error). Check its Run Commands permission and allow-external-apps setting.")
    if(!bundle.containsKey("exitCode"))throw IOException("Termux returned no process exit code.")
    return TermuxResult(bundle.getInt("exitCode"),bundle.getString("stdout").orEmpty(),bundle.getString("stderr").orEmpty())
}

class TaskService : Service() {
    private var watcher: CoroutineScope? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("work","Agent tasks",NotificationManager.IMPORTANCE_LOW))
        // Adopt the started conversation until a run owns the preview itself.
        val conversationId=intent?.getStringExtra("conversationId")
        if(conversationId!=null)TaskProgressPipe.update{it.copy(conversationId=conversationId)}
        startForeground(1,render())
        if(watcher==null) {
            watcher=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).also{scope->
                scope.launch {
                    val ticks=flow{while(true){emit(Unit);delay(1000)}}
                    combine(TaskProgressPipe.live,ticks){live,_->render(live)}.collect{notification->manager.notify(1,notification)}
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        watcher?.cancel(); watcher = null
        super.onDestroy()
    }
    private fun render(live: TaskProgressPipe.Live=TaskProgressPipe.live.value):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,WorkChatActivity::class.java).putExtra("conversationId",live.conversationId),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title=live.title.ifBlank{"Hydra is working"}
        val builder=Notification.Builder(this,"work").setSmallIcon(R.drawable.hydra_monochrome)
            .setContentTitle(title).setContentIntent(open).setOngoing(true)
        val body=if(live.running)live.headline.ifBlank{"Thinking…"}else "Task finished. Open to review the result."
        builder.setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
        if(live.running)builder.setSubText(elapsedLabel(live.startedElapsed))
        return builder.build()
    }
    private fun elapsedLabel(startedElapsed: Long):String {
        val seconds=((android.os.SystemClock.elapsedRealtime()-startedElapsed)/1000L).coerceAtLeast(1)
        val minutes=seconds/60; val hours=minutes/60
        return when {
            hours>0 -> String.format("%d:%02d:%02d elapsed",hours,minutes%60,seconds%60)
            minutes>0 -> String.format("%d:%02d elapsed",minutes,seconds%60)
            else -> String.format("%ds elapsed",seconds)
        }
    }
}
