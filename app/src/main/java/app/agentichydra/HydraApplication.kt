package app.agentichydra

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Task execution survives navigation between history, a task activity, and settings. */
class HydraApplication:Application(),ViewModelStoreOwner {
    override val viewModelStore=ViewModelStore()
    val models by lazy { ViewModelProvider(this,ViewModelProvider.AndroidViewModelFactory.getInstance(this)) }
    internal val schedules by lazy { TaskSchedules(this) }
}
