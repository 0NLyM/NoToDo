package app.notodo

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.notodo.data.Db
import app.notodo.data.Repo
import app.notodo.data.SettingsStore
import app.notodo.parse.CaptureParser
import app.notodo.parse.ItalianParser
import app.notodo.reminder.Alarms
import app.notodo.reminder.Notifications
import app.notodo.widget.Widget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val db: Db by lazy { Room.databaseBuilder(this, Db::class.java, "notodo.db").build() }
    val settings by lazy { SettingsStore(this) }
    // Sostituibile: un futuro parser avanzato, o uno guasto nei test.
    var parser: CaptureParser = ItalianParser
    val repo by lazy {
        Repo(db) {
            Alarms.schedule(this)
            Widget.refresh(this)
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.channels(this)
        // Ogni avvio ripara un eventuale allarme perso (crash tra commit e pianificazione).
        scope.launch {
            Alarms.schedule(this@App)
            Alarms.scheduleDigest(this@App)
        }
    }
}

val Context.app: App get() = applicationContext as App
