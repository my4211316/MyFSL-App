package tw.myfsl.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tw.myfsl.app.MainActivity
import tw.myfsl.app.R
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.Reminder
import tw.myfsl.app.core.domain.Reminders
import tw.myfsl.app.core.model.FinanceSnapshot
import javax.inject.Inject

/** 每天檢查一次，發出今天該發的到期提醒（R-REM-01）。不寫入任何資料。 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: FinanceRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        if (!canNotify(context)) return
        val pending = goAsync()
        scope.launch {
            try {
                val snapshot = repository.snapshot.first()
                // 資料正在更新（還原、清除途中）時不提醒，下次再算。
                if (snapshot.generation != FinanceSnapshot.NO_GENERATION) {
                    Reminders.forDate(snapshot).forEach { notify(context, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, reminder: Reminder) {
        if (!canNotify(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            reminder.billCardId?.let { putExtra(MainActivity.EXTRA_OPEN_BILL, it) }
        }
        val tap = PendingIntent.getActivity(
            context, reminder.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // 使用者在檢查之後才關掉通知權限：略過。
        }
    }

    companion object {
        const val ACTION_CHECK = "tw.myfsl.app.action.CHECK_REMINDERS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** 開機後重新排每天的檢查（不精確的鬧鐘在重開機後會消失）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler.schedule(context)
    }
}
