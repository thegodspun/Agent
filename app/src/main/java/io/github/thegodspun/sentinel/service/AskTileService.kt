package io.github.thegodspun.sentinel.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import io.github.thegodspun.sentinel.MainActivity
import io.github.thegodspun.sentinel.Notifications

/** Quick Settings tile: swipe down, tap "Ask Sentinel", and start talking. */
class AskTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(Notifications.openAppIntent(this, voice = true, requestCode = 3))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_VOICE, true)
            )
        }
    }
}
