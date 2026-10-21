package io.github.thegodspun.sentinel

import android.app.Application
import io.github.thegodspun.sentinel.core.InboxStore
import io.github.thegodspun.sentinel.core.NotesStore
import io.github.thegodspun.sentinel.core.TrackerStore
import io.github.thegodspun.sentinel.service.AgentService
import io.github.thegodspun.sentinel.work.SentinelWorker
import java.io.File

class SentinelApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var tracker: TrackerStore
        private set
    lateinit var notes: NotesStore
        private set
    lateinit var inbox: InboxStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        tracker = TrackerStore(File(filesDir, "tracker.json"))
        notes = NotesStore(File(filesDir, "notes.txt"))
        inbox = InboxStore(File(filesDir, "inbox.json"))
        Notifications.createChannels(this)
        SentinelWorker.schedule(this)
        if (settings.alwaysOn) AgentService.start(this)
    }
}

val android.content.Context.app: SentinelApp get() = applicationContext as SentinelApp
