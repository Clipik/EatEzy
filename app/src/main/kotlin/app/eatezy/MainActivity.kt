package app.eatezy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.eatezy.ui.AppRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as App).repository
        setContent { AppRoot(repo) }
        handle(intent)
        // The "AI is counting…" notification keeps recognition alive in the background; Android 13+
        // hides it unless allowed. Asked once: the system won't show the dialog again after a "no".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The morning notification opens the "when did you wake up?" dialog. */
    private fun handle(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(SleepReminders.EXTRA_WAKE, false) != true) return
        intent.removeExtra(SleepReminders.EXTRA_WAKE)
        val repo = (application as App).repository
        lifecycleScope.launch { repo.askWake() }
    }
}
