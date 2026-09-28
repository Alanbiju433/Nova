package com.aethernova.nova

import android.app.Application
import com.aethernova.nova.ai.NovaBrain
import com.aethernova.nova.data.NovaDatabase
import com.aethernova.nova.data.Repository
import com.aethernova.nova.data.SettingsStore
import com.aethernova.nova.reminders.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovaApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var settings: SettingsStore
    lateinit var repo: Repository
    lateinit var brain: NovaBrain
    lateinit var db: NovaDatabase
    var demo = false; private set
    /** True while MainActivity is on screen — background prompts skip what the app already shows. */
    @Volatile var foreground = false
    val dbName get() = if (demo) com.aethernova.nova.data.DemoData.DB_NAME else NovaDatabase.NAME

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        settings = SettingsStore(this)
        demo = com.aethernova.nova.data.DemoData.isDemo(this)
        db = NovaDatabase.create(this, if (demo) com.aethernova.nova.data.DemoData.DB_NAME else NovaDatabase.NAME)
        repo = Repository(db.dao(), settings)
        repo.onDataChanged = { com.aethernova.nova.widget.NovaWidget.refresh(this) }
        brain = NovaBrain(repo, settings)
        Reminders.createChannel(this)
        com.aethernova.nova.reminders.Nudges.channels(this)
        com.aethernova.nova.reminders.MedReminders.channel(this)
        if (demo) {
            // demo data only: no real reminders fire while showing Nova off
            appScope.launch { com.aethernova.nova.data.DemoData.fillIfEmpty(repo.dao); com.aethernova.nova.data.DemoData.fillWorkIfEmpty(repo.dao) }
            return
        }
        if (settings.current.reminders) Reminders.scheduleAll(this)
        appScope.launch { repo.seedMilestones(settings.current.dissertationDue) }
        com.aethernova.nova.reminders.Nudges.scheduleNext(this)
        com.aethernova.nova.reminders.MedReminders.scheduleAll(this)
    }
}
