package dev.crumble.android.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** WorkManager persists retries without putting server credentials in its input database. */
class PushSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock {
        val runtime = PushRuntime.get(applicationContext)
        val outcome = PushSync(
            store = runtime.store,
            installationId = runtime.installationId,
            firebaseConfigured = FirebaseApp.getApps(applicationContext).isNotEmpty(),
            fetchToken = { fetchToken() },
            onRegistered = { runtime.sendPresence() },
        ).run()
        when (outcome) {
            PushSyncResult.SUCCESS -> Result.success()
            PushSyncResult.RETRY -> Result.retry()
            PushSyncResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        // A replaced worker may still be finishing blocking HTTP I/O. Serialize until
        // it unwinds, so the latest token/connection is always the last PUT sent.
        private val syncMutex = Mutex()
    }

    // The backend sends HTTP v1 message.token. FID direct-send is a separate opt-in
    // transport; retain standard registration tokens until both ends migrate.
    @Suppress("DEPRECATION")
    private suspend fun fetchToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
}
