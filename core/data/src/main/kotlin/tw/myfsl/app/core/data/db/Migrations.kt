package tw.myfsl.app.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 資料庫升級。每提高一次版本就在這裡加一筆，
 * 讓 `MigrationTest` 對照 core/data/schemas 裡各版本的 JSON 檢查有沒有漏。
 */
object Migrations {

    /**
     * 第 2 版：預算不再分支付方式（R-MIX-01）。
     *
     * - `plan_items` 加上 `method`（支出的支付方式）與 `useActualMix`。
     * - `plan_amounts` 主鍵去掉 `method`，同一個項目同一個月的多列金額相加。
     * - `item_actuals` 主鍵去掉 `method`，同一個項目同一個月只留一筆
     *   （文字排序 DONE < IN_PROGRESS < POSTPONED，取 MIN 等於已完成優先）。
     * - 到期識別碼 `plan:<項目>:<方式>:<年月>:<日>` 去掉方式那一段。
     */
    private val toVersion2 = listOf(
        "ALTER TABLE `plan_items` ADD COLUMN `method` TEXT",
        "ALTER TABLE `plan_items` ADD COLUMN `useActualMix` INTEGER NOT NULL DEFAULT 1",
        // 支付方式取這個項目金額最大的那一列；沒有計畫金額的支出項目當作現金。
        "UPDATE `plan_items` SET `method` = (" +
            "SELECT a.`method` FROM `plan_amounts` a WHERE a.`itemId` = `plan_items`.`id` " +
            "GROUP BY a.`method` ORDER BY SUM(a.`amount`) DESC, a.`method` ASC LIMIT 1" +
            ") WHERE `type` = 'EXPENSE'",
        "UPDATE `plan_items` SET `method` = 'CASH' WHERE `type` = 'EXPENSE' AND (`method` IS NULL OR `method` = '-')",
        "UPDATE `plan_items` SET `method` = NULL WHERE `type` <> 'EXPENSE'",

        "CREATE TABLE IF NOT EXISTS `plan_amounts_new` (`itemId` INTEGER NOT NULL, `year` INTEGER NOT NULL, " +
            "`month` INTEGER NOT NULL, `amount` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `year`, `month`))",
        "INSERT INTO `plan_amounts_new` (`itemId`, `year`, `month`, `amount`) " +
            "SELECT `itemId`, `year`, `month`, SUM(`amount`) FROM `plan_amounts` GROUP BY `itemId`, `year`, `month`",
        "DROP TABLE `plan_amounts`",
        "ALTER TABLE `plan_amounts_new` RENAME TO `plan_amounts`",

        "CREATE TABLE IF NOT EXISTS `item_actuals_new` (`itemId` INTEGER NOT NULL, `year` INTEGER NOT NULL, " +
            "`month` INTEGER NOT NULL, `status` TEXT NOT NULL, `updatedEpochDay` INTEGER NOT NULL, " +
            "PRIMARY KEY(`itemId`, `year`, `month`))",
        "INSERT INTO `item_actuals_new` (`itemId`, `year`, `month`, `status`, `updatedEpochDay`) " +
            "SELECT `itemId`, `year`, `month`, MIN(`status`), MAX(`updatedEpochDay`) " +
            "FROM `item_actuals` GROUP BY `itemId`, `year`, `month`",
        "DROP TABLE `item_actuals`",
        "ALTER TABLE `item_actuals_new` RENAME TO `item_actuals`",

        // plan:12:CASH:2026-09:15 → plan:12:2026-09:15
        planKeySql("ledger_entries", "postingKey"),
        planKeySql("posted_keys", "key"),
    )

    /** 拿掉到期識別碼裡第 3 段（支付方式）的 SQL。 */
    private fun planKeySql(table: String, column: String): String {
        val rest = "substr(`$column`, instr(`$column`, ':') + 1)"
        val id = "substr($rest, 1, instr($rest, ':') - 1)"
        val afterId = "substr($rest, instr($rest, ':') + 1)"
        val afterMethod = "substr($afterId, instr($afterId, ':') + 1)"
        return "UPDATE `$table` SET `$column` = 'plan:' || $id || ':' || $afterMethod WHERE `$column` LIKE 'plan:%'"
    }

    /**
     * 第 3 版：計畫不再帶支付方式（R-MIX-01）。試算的付款假設改成設定裡的一個數字（R-MIX-02），
     * 在 SettingsRepository 讀不到時用預設值「全部當現金付」，所以這裡只要把欄位拿掉。
     */
    private val toVersion3 = listOf(
        "CREATE TABLE IF NOT EXISTS `plan_items_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `groupId` INTEGER NOT NULL, `type` TEXT NOT NULL, `accountId` INTEGER, " +
            "`toAccountId` INTEGER, `timing` TEXT NOT NULL, `flexibility` TEXT NOT NULL, `tracking` TEXT NOT NULL, " +
            "`note` TEXT NOT NULL, `archived` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `dueDay` INTEGER, " +
            "`extraRepayment` INTEGER NOT NULL, `archivedFrom` INTEGER)",
        "INSERT INTO `plan_items_new` (`id`, `name`, `groupId`, `type`, `accountId`, `toAccountId`, `timing`, " +
            "`flexibility`, `tracking`, `note`, `archived`, `sortOrder`, `dueDay`, `extraRepayment`, `archivedFrom`) " +
            "SELECT `id`, `name`, `groupId`, `type`, `accountId`, `toAccountId`, `timing`, `flexibility`, `tracking`, " +
            "`note`, `archived`, `sortOrder`, `dueDay`, `extraRepayment`, `archivedFrom` FROM `plan_items`",
        "DROP TABLE `plan_items`",
        "ALTER TABLE `plan_items_new` RENAME TO `plan_items`",
        "CREATE INDEX IF NOT EXISTS `index_plan_items_groupId` ON `plan_items` (`groupId`)",
    )

    /** 版本 → 從上一版升到這一版要執行的 SQL。 */
    val statements: Map<Int, List<String>> = mapOf(2 to toVersion2, 3 to toVersion3)

    val all: Array<Migration> = statements.map { (to, sql) ->
        object : Migration(to - 1, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach(db::execSQL)
            }
        }
    }.toTypedArray()
}
