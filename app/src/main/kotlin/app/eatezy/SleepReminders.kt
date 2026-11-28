package app.eatezy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.eatezy.data.Repository
import app.eatezy.data.bedBefore
import app.eatezy.data.eveningReminder
import app.eatezy.data.isFresh
import app.eatezy.data.language
import app.eatezy.data.morningReminder
import app.eatezy.data.usualBed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Evening "going to sleep?" and morning "when did you wake up?" notifications.
 * Their time follows the user's own sleep (see [eveningReminder], [morningReminder]); an alarm is
 * set for the next one only, and set again whenever it fires or the sleep log changes.
 */
object SleepReminders {
    const val EVENING = "app.eatezy.SLEEP_EVENING"
    const val MORNING = "app.eatezy.SLEEP_MORNING"
    const val GO_TO_BED = "app.eatezy.GO_TO_BED"
    const val WOKE_NOW = "app.eatezy.WOKE_NOW"
    const val EXTRA_WAKE = "sleep_wake"
    private const val CHANNEL = "sleep"
    private const val ID_EVENING = 51
    private const val ID_MORNING = 52

    suspend fun schedule(context: Context, repo: Repository) {
        val prefs = repo.settings.now()
        val nights = repo.sleepOnce()
        set(context, EVENING, if (prefs.sleepEvening) eveningReminder(nights) else null)
        set(context, MORNING, if (prefs.sleepMorning) morningReminder(nights) else null)
    }

    private fun set(context: Context, action: String, time: LocalTime?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, SleepReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (time == null) return
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(time)
        if (!at.isAfter(now.plusMinutes(1))) at = at.plusDays(1)
        // Inexact: needs no special permission, a few minutes late is fine for a reminder.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    /** Hides the reminders once the night they ask about is logged. */
    fun cancelShown(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.run { cancel(ID_EVENING); cancel(ID_MORNING) }
    }

    /** Evening: only when the bedtime for tonight is not logged yet. */
    suspend fun evening(context: Context, repo: Repository) {
        val now = LocalDateTime.now()
        val logged = repo.sleepOnce().any { e -> e.bed != null && Duration.between(e.bed, now).abs().toHours() < 6 }
        if (logged) return
        val ru = language(repo.settings.now()) == "ru"
        show(
            context, ID_EVENING,
            if (ru) "Скоро спать?" else "Going to bed soon?",
            if (ru) "Отметьте, когда ляжете" else "Tap when you go to bed",
            action(context, GO_TO_BED, if (ru) "Иду спать" else "Going to sleep"),
            ru,
        )
    }

    /** Morning: only when the wake-up of this night is not logged yet. */
    suspend fun morning(context: Context, repo: Repository) {
        val now = LocalDateTime.now()
        val woke = repo.sleepOnce().any { e -> e.wake != null && Duration.between(e.wake, now).abs().toHours() < 10 }
        if (woke) return
        val ru = language(repo.settings.now()) == "ru"
        show(
            context, ID_MORNING,
            if (ru) "Доброе утро" else "Good morning",
            if (ru) "Во сколько проснулись?" else "When did you wake up?",
            action(context, WOKE_NOW, if (ru) "Проснулся сейчас" else "Woke up now"),
            ru,
            open = Intent(context, MainActivity::class.java).putExtra(EXTRA_WAKE, true),
        )
    }

    /** "Woke up now" from the notification: bedtime is the logged one, else the usual one. */
    suspend fun wokeNow(repo: Repository) {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val nights = repo.sleepOnce()
        val open = nights.firstOrNull { it.wake == null && isFresh(it, now) }
        if (open != null) repo.wakeUp(now, open.bed, open.source)
        else repo.wakeUp(now, usualBed(nights)?.let { bedBefore(it, now) }, "usual")
    }

    private fun action(context: Context, action: String, title: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, SleepReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun show(context: Context, id: Int, title: String, text: String, action: Notification.Action, ru: Boolean, open: Intent? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, if (ru) "Сон" else "Sleep", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val tap = PendingIntent.getActivity(
            context, id,
            (open ?: Intent(context, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        runCatching {
            nm.notify(
                id,
                b.setSmallIcon(R.drawable.ic_sleep).setContentTitle(title).setContentText(text)
                    .setContentIntent(tap).setAutoCancel(true).addAction(action).build(),
            )
        }
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as App).repository
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    SleepReminders.EVENING -> SleepReminders.evening(context, repo)
                    SleepReminders.MORNING -> SleepReminders.morning(context, repo)
                    // Saving a night also hides the notification and sets the next reminders.
                    SleepReminders.GO_TO_BED -> repo.goToBed(LocalDateTime.now().withSecond(0).withNano(0), "notification")
                    SleepReminders.WOKE_NOW -> SleepReminders.wokeNow(repo)
                }
                SleepReminders.schedule(context, repo)
            } finally {
                done.finish()
            }
        }
    }
}

/** Alarms are lost on reboot and app update: set them again. */
class SleepBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as App).repository
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { SleepReminders.schedule(context, repo) } finally { done.finish() }
        }
    }
}
