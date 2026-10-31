package app.agentichydra

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.agentichydra.mcp.McpRegistry

/** Task execution survives navigation between history, a task activity, and settings. */
class HydraApplication :
    Application(),
    ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val models by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this)) }
    internal val schedules by lazy { TaskSchedules(this) }
    val mcpRegistry by lazy { McpRegistry(this) }

    override fun onCreate() {
        super.onCreate()
        app.agentichydra.mcp.McpToolRouter.registry = mcpRegistry
        // auto-connect
        mcpRegistry.connectAllAuto()
    }
}
