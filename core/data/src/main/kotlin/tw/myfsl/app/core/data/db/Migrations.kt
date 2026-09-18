package tw.myfsl.app.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 資料庫升級。每提高一次版本就在這裡加一筆，
 * 讓 `MigrationTest` 對照 core/data/schemas 裡各版本的 JSON 檢查有沒有漏。
 *
 * 例：v2 新增欄位
 * ```
 * 2 to listOf("ALTER TABLE `accounts` ADD COLUMN `nickname` TEXT")
 * ```
 */
object Migrations {

    /** 版本 → 從上一版升到這一版要執行的 SQL。1.0.0 是第 1 版，還沒有任何升級。 */
    val statements: Map<Int, List<String>> = emptyMap()

    val all: Array<Migration> = statements.map { (to, sql) ->
        object : Migration(to - 1, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach(db::execSQL)
            }
        }
    }.toTypedArray()
}
