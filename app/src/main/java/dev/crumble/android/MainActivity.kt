package dev.crumble.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ThemeChoice
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import dev.crumble.android.theme.CrumbleTheme
import dev.crumble.android.ui.CrumbleAppScreen
import dev.crumble.android.push.PushRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val LIGHT_BAR_SCRIM = Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DARK_BAR_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** GitHub's pages keep their own sign-in, which must not carry over to the next account. */
private fun forgetWebSessions() {
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }
}

class MainActivity : ComponentActivity() {
    private val push by lazy { PushRuntime.get(applicationContext) }
    private val owner: AppViewModel by viewModels {
        // The model outlives this activity, so nothing it keeps may reach back here.
        val context = applicationContext
        val runtime = push
        viewModelFactory {
            initializer {
                AppViewModel { scope, notificationsChanged ->
                    AppModel(
                        credentials = AndroidKeyStoreCredentials(context),
                        notifier = runtime,
                        scope = scope,
                        environment = System.getenv(),
                        onSignOut = { runtime.signedOut(it) },
                        onSessionReset = ::forgetWebSessions,
                        onNotificationsChanged = notificationsChanged,
                    )
                }
            }
        }
    }
    private val model: AppModel
        get() = owner.model

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (push.repliesWanted) requestNotificationPermissionIfNeeded()

        owner.onNotificationsChanged = ::notificationsChanged
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
        if (owner.start()) {
            model.refresh()
            model.followActivity()
            handleIncomingIntent(intent)
        }
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
            val dark = when (model.theme) {
                ThemeChoice.System -> isSystemInDarkTheme()
                ThemeChoice.Light -> false
                ThemeChoice.Dark -> true
            }
            LaunchedEffect(dark) { matchSystemBars(dark) }
            CrumbleTheme(darkTheme = dark) {
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
        model.appActive = true
        model.browserReturned()
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
        push.updatePresence(true, model.route)
    }

    override fun onPause() {
        super.onPause()
        model.appActive = false
        model.saveDrafts()
        push.updatePresence(false, model.route)
    }

    override fun onDestroy() {
        owner.onNotificationsChanged = {}
        super.onDestroy()
    }

    /** Android's default follows the system, which leaves dark icons on a dark app after a theme pick. */
    private fun matchSystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_BAR_SCRIM, DARK_BAR_SCRIM) { dark },
        )
    }

    private fun notificationsChanged() {
        if (model.replyNotifications) requestNotificationPermissionIfNeeded()
        model.client?.let { push.configure(it) }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val groupId = push.groupFromIntent(intent)
        val agentId = push.agentFromIntent(intent)
        if (!groupId.isNullOrEmpty()) {
            model.openGroupChat(groupId)
        } else if (!agentId.isNullOrEmpty()) {
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
