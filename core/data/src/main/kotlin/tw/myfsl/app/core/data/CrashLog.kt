package tw.myfsl.app.core.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 當機紀錄：App 沒有連網、也不上傳任何東西，所以把最後一次當機的錯誤存在手機裡，
 * 使用者可以在「設定 → 關於」複製給開發者。只留最後一次，不含任何帳務資料。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val FILE = "last.txt"

    fun install(context: Context, versionName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, versionName, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun write(context: Context, versionName: String, threadName: String, error: Throwable) {
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(format(versionName, threadName, error, System.currentTimeMillis()))
    }

    /** 純文字格式，方便測試。 */
    fun format(versionName: String, threadName: String, error: Throwable, atMillis: Long): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("MyFSL $versionName")
            appendLine("時間：$time")
            appendLine("Android ${android.os.Build.VERSION.RELEASE ?: "?"}（API ${android.os.Build.VERSION.SDK_INT}）")
            appendLine("執行緒：$threadName")
            appendLine()
            append(stack.lines().take(60).joinToString("\n"))
        }
    }
}
