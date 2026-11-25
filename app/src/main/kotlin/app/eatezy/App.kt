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
        repository = Repository(this, Db.open(this), Settings(this), Health(this))
        repository.refreshModelsInBackground()
    }
}
