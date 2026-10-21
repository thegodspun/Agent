package io.github.thegodspun.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.github.thegodspun.sentinel.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the user to approve a call, text or tap. The question shows in Sentinel and as a
 * pop-up notification at the same time, so it can be answered even while Sentinel is
 * operating another app. Whichever answer comes first wins; no answer in two minutes means no.
 */
object ApprovalCenter {
    const val EXTRA_ALLOW = "allow"
    private const val NOTIFICATION_ID = 1003
    private const val TIMEOUT_MILLIS = 120_000L

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun ask(context: Context, question: String, answer: CompletableDeferred<Boolean>): Boolean {
        pending = answer
        Notifications.postApproval(context, NOTIFICATION_ID, question)
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { answer.await() } ?: false
        } finally {
            pending = null
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    fun answer(allow: Boolean) {
        pending?.complete(allow)
    }
}

/** Receives Allow / Not now from the approval notification (and a swipe-away, which means no). */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApprovalCenter.answer(intent.getBooleanExtra(ApprovalCenter.EXTRA_ALLOW, false))
    }
}
