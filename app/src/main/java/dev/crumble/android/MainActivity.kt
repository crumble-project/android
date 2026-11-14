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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ThemeChoice
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import dev.crumble.android.theme.CrumbleTheme
import dev.crumble.android.ui.CrumbleAppScreen
import dev.crumble.android.ui.LocalAttentionWindowID
import dev.crumble.android.push.PushRuntime

private val LIGHT_BAR_SCRIM = Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DARK_BAR_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** Web pages in the app, such as the bot desktop viewer, keep their own storage, which must not carry over to the next account. */
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
    private var attentionViewID: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attentionViewID = model.newAttentionView()
        model.appActive = false
        model.updateChatAttention()

        if (push.repliesWanted) requestNotificationPermissionIfNeeded()

        owner.onNotificationsChanged = ::notificationsChanged
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
        if (owner.start()) {
            model.refresh()
            model.followActivity()
            handleIncomingIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(model) {
                snapshotFlow { model.client }.collect { api -> api?.let { push.configure(it) } }
            }
            LaunchedEffect(model) {
                snapshotFlow { Triple(model.client, model.appActive, model.route) }.collect { (_, active, route) ->
                    if (model.attentionWindowID == attentionViewID) {
                        push.updateVisibility(active, route)
                        model.updateChatAttention()
                    }
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
                    CompositionLocalProvider(LocalAttentionWindowID provides attentionViewID) {
                        CrumbleAppScreen(model = model)
                    }
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
        if (model.attentionWindowID != attentionViewID) return
        model.appActive = true
        model.notificationsAllowed = push.notificationsAllowed()
        model.client?.let { push.configure(it) }
        push.updateVisibility(true, model.route)
        model.updateChatAttention()
    }

    override fun onPause() {
        super.onPause()
        if (model.attentionWindowID != attentionViewID) return
        model.appActive = false
        model.saveDrafts()
        push.updateVisibility(false, model.route)
        model.updateChatAttention()
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
