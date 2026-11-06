package dev.crumble.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.crumble.android.model.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Keeps the model through activity recreation, so drafts, open forms, and running saves survive a theme or locale change. */
class AppViewModel(build: (CoroutineScope, () -> Unit) -> AppModel) : ViewModel() {
    /** The current activity's handler; the model itself must never hold an activity. */
    var onNotificationsChanged: () -> Unit = {}

    val model: AppModel = build(viewModelScope) { onNotificationsChanged() }

    private var started = false

    /** True once per model: a recreated activity must not reload it or replay the intent that opened the app. */
    fun start(): Boolean = !started.also { started = true }

    override fun onCleared() {
        // viewModelScope has ended by now; handing back must not end with it.
        model.closeBotDesktop(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
