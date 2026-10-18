package dev.crumble.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.crumble.android.model.AppModel
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import dev.crumble.android.theme.CrumbleTheme
import dev.crumble.android.ui.CrumbleAppScreen
import dev.crumble.android.push.PushRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private val push by lazy { PushRuntime.get(applicationContext) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { if (::model.isInitialized) model.client?.let { push.configure(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        model = AppModel(
            credentials = AndroidKeyStoreCredentials(applicationContext),
            notifier = push,
            scope = lifecycleScope,
            environment = System.getenv(),
        )
        model.client?.let { push.configure(it) }
        model.refresh()
        model.followActivity()
        handleIncomingIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    push.refreshPresence()
                    delay(30_000)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            LaunchedEffect(model) {
                snapshotFlow { model.client }.collect { api -> api?.let { push.configure(it) } }
            }
            LaunchedEffect(model) {
                snapshotFlow { model.appActive to model.route }.collect { (active, route) ->
                    push.updatePresence(active, route)
                }
            }
            CrumbleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CrumbleAppScreen(model = model)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            model.appActive = true
            model.client?.let { push.configure(it) }
            push.updatePresence(true, model.route)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::model.isInitialized) {
            model.appActive = false
            push.updatePresence(false, model.route)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || !::model.isInitialized) return
        val agentId = push.agentFromIntent(intent)
        if (!agentId.isNullOrEmpty()) {
            model.selectAgent(agentId)
        }
        val data = intent.data
        if (data != null && data.scheme == AppModel.oauthCallbackScheme) {
            model.handleOAuthCallback(data.toString())
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
