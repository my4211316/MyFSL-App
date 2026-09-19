package tw.myfsl.app

import android.app.Application
import tw.myfsl.app.core.data.CrashLog
import dagger.hilt.android.HiltAndroidApp
import tw.myfsl.app.reminder.ReminderScheduler

@HiltAndroidApp
class MyFSLApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, BuildConfig.VERSION_NAME)
        ReminderScheduler.schedule(this)
    }
}
