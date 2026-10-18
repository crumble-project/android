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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.crumble.android.model.AndroidReplyNotifier
import dev.crumble.android.model.AppModel
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import dev.crumble.android.theme.CrumbleTheme
import dev.crumble.android.ui.CrumbleAppScreen

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        model = AppModel(
            credentials = AndroidKeyStoreCredentials(applicationContext),
            notifier = AndroidReplyNotifier(applicationContext),
            scope = lifecycleScope,
            environment = System.getenv(),
        )
        model.refresh()
        model.followActivity()
        handleIncomingIntent(intent)

        enableEdgeToEdge()
        setContent {
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
        }
    }

    override fun onPause() {
        super.onPause()
        if (::model.isInitialized) {
            model.appActive = false
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || !::model.isInitialized) return
        val agentId = intent.getStringExtra(AndroidReplyNotifier.EXTRA_AGENT_ID)
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
