package app.agentichydra

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews

internal const val QUICK_VOICE_QUERY = "quickVoiceQuery"

private fun quickTask(context: Context, voice: Boolean) =
    Intent(context, WorkChatActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("newConversation", true)
        .putExtra(QUICK_VOICE_QUERY, voice)

private fun taskShortcut(context: Context, voice: Boolean) =
    PendingIntent.getActivity(
        context, if (voice) 1 else 0, quickTask(context, voice),
        PendingIntent.FLAG_IMMUTABLE,
    )

abstract class WorkShortcutTile(private val voice: Boolean) : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val launch = Runnable {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(taskShortcut(this, voice))
            } else {
                startActivityAndCollapse(quickTask(this, voice))
            }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
}

class NewTaskTile : WorkShortcutTile(false)

class VoiceTaskTile : WorkShortcutTile(true)

class WorkShortcutWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.work_shortcut_widget)
        views.setOnClickPendingIntent(R.id.widget_new_task, taskShortcut(context, false))
        views.setOnClickPendingIntent(R.id.widget_voice_task, taskShortcut(context, true))
        manager.updateAppWidget(ids, views)
    }
}
