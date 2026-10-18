package dev.crumble.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushMessagingService : FirebaseMessagingService() {
    // Standard token transport matches the backend's HTTP v1 message.token contract.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        PushRuntime.get(this).tokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Data messages contain everything needed to post; no backend fetch is required
        // within FCM's short execution window or while the local server is unreachable.
        PushRuntime.get(this).receive(message.data)
    }

    override fun onDeletedMessages() {
        // The UI refreshes history on its next launch; revalidate the registration now.
        PushRuntime.get(this).scheduleSync()
    }
}
