package tw.myfsl.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 到期提醒的排程（R-REM-01）：每天早上 9 點左右檢查一次（不精確的每日鬧鐘，省電），
 * 由 [ReminderReceiver] 算出今天要發的提醒。開 App 與開機時都重新排一次。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "due_reminders"
    private const val REQUEST_CODE = 1001
    private val TIME: LocalTime = LocalTime.of(9, 0)

    fun schedule(context: Context) {
        createChannel(context)
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val todayAt = LocalDate.now(zone).atTime(TIME).atZone(zone)
        val first = if (todayAt.toInstant().toEpochMilli() > System.currentTimeMillis()) todayAt else todayAt.plusDays(1)
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "到期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "卡費、貸款與每月固定付款的到期提醒，以及信用卡結帳後對照帳單"
        }
        manager.createNotificationChannel(channel)
    }
}
