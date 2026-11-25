package app.eatezy

import android.app.Application
import app.eatezy.data.Db
import app.eatezy.data.Repository
import app.eatezy.data.Settings
import app.eatezy.health.Health

class App : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep the last crash so it can be copied from Settings → Updates without a computer.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile(this).writeText("${java.util.Date()} · ${BuildConfig.VERSION_NAME}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
        repository = Repository(this, Db.open(this), Settings(this), Health(this))
        repository.refreshModelsInBackground()
        repository.resumePending()
    }
}

fun crashFile(context: android.content.Context) = java.io.File(context.filesDir, "last_crash.txt")
