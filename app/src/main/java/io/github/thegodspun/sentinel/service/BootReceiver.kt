package io.github.thegodspun.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.work.SentinelWorker

/** Restarts the watch after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SentinelApp.onCreate has already run and started the service; this makes sure
        // the schedule exists even if it was cleared.
        SentinelWorker.schedule(context)
        if (context.app.settings.alwaysOn) AgentService.start(context)
    }
}
