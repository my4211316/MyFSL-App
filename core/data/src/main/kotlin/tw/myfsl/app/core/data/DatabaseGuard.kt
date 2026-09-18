package tw.myfsl.app.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import tw.myfsl.app.core.data.db.AppDatabase

/**
 * 開資料庫之前先檢查版本（R-DATA-02）：手機上的資料如果是較新版 App 寫的（例如不小心裝回舊版 APK），
 * 不能打開、也絕不能清掉，要請使用者裝回新版。
 */
object DatabaseGuard {

    /** 手機上資料庫的版本；還沒有資料庫時為 null。 */
    fun storedVersion(context: Context): Int? {
        val file = context.getDatabasePath(AppDatabase.NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrNull()
    }

    /** 手機上的資料比這個 App 新：要擋下來。 */
    fun isNewerThanApp(context: Context): Boolean = (storedVersion(context) ?: 0) > AppDatabase.VERSION
}
