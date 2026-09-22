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

    /**
     * 第 4 版：一期從半月改成整月（R-PER-01）。
     *
     * - `plan_items` 去掉 `timing`（上半月／下半月／上下各半）。項目的付款日改看 `dueDay`，
     *   沒填就是沒填（R-PER-02），App 不再替使用者猜。
     * - 期別編號從「年 × 24 + (月 − 1) × 2 + 半」改成「年 × 12 + (月 − 1)」：
     *   `card_installments.firstPeriodIndex` 直接換算（同一個月的上下半月都落在那個月）。
     * - 到期識別碼 `plan:<項目>:<年月>:<日>` 去掉日那一段：一個項目一個月只會到期一次。
     * - 情境裡的期別存在 JSON 裡，另外用 Kotlin 換算（見 [rewriteScenarioIndexes]）。
     */
    private val toVersion4 = listOf(
        "CREATE TABLE IF NOT EXISTS `plan_items_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `groupId` INTEGER NOT NULL, `type` TEXT NOT NULL, `accountId` INTEGER, " +
            "`toAccountId` INTEGER, `flexibility` TEXT NOT NULL, `tracking` TEXT NOT NULL, " +
            "`note` TEXT NOT NULL, `archived` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `dueDay` INTEGER, " +
            "`extraRepayment` INTEGER NOT NULL, `archivedFrom` INTEGER)",
        "INSERT INTO `plan_items_new` (`id`, `name`, `groupId`, `type`, `accountId`, `toAccountId`, " +
            "`flexibility`, `tracking`, `note`, `archived`, `sortOrder`, `dueDay`, `extraRepayment`, `archivedFrom`) " +
            "SELECT `id`, `name`, `groupId`, `type`, `accountId`, `toAccountId`, `flexibility`, `tracking`, " +
            "`note`, `archived`, `sortOrder`, `dueDay`, `extraRepayment`, `archivedFrom` FROM `plan_items`",
        "DROP TABLE `plan_items`",
        "ALTER TABLE `plan_items_new` RENAME TO `plan_items`",
        "CREATE INDEX IF NOT EXISTS `index_plan_items_groupId` ON `plan_items` (`groupId`)",

        // 半月編號 → 月編號：年 × 12 + 該年的第幾個月。
        "UPDATE `card_installments` SET `firstPeriodIndex` = " +
            "(`firstPeriodIndex` / 24) * 12 + ((`firstPeriodIndex` % 24) / 2)",

        // plan:12:2026-09:15 → plan:12:2026-09
        planMonthKeySql("ledger_entries", "postingKey"),
        planMonthKeySql("posted_keys", "key"),
    )

    /** 拿掉到期識別碼最後一段（日）的 SQL：只改還帶著日的那些（`plan:<id>:<年月>:<日>`）。 */
    private fun planMonthKeySql(table: String, column: String): String {
        val rest = "substr(`$column`, 6)"
        val id = "substr($rest, 1, instr($rest, ':') - 1)"
        val afterId = "substr($rest, instr($rest, ':') + 1)"
        val ym = "substr($afterId, 1, instr($afterId, ':') - 1)"
        return "UPDATE `$table` SET `$column` = 'plan:' || $id || ':' || $ym " +
            "WHERE `$column` LIKE 'plan:%:____-__:%'"
    }

    /**
     * 第 5 版：計畫列帶回支付方式（R-MIX-01）。那是使用者編預算時的決定，而且刷卡隔月才付，
     * 會直接改變月現金流，所以放回 `plan_amounts` 的主鍵裡。
     *
     * 第 3、4 版的計畫沒有支付方式，救不回來：**支出一律當成現金**（對現金流最保守，錢當月就離開帳戶），
     * 收入與轉帳存空字串。要正確的刷卡／現金結構，重新匯入一次 CSV 就好。
     */
    private val toVersion5 = listOf(
        "ALTER TABLE `accounts` ADD COLUMN `cardPaymentPlan` TEXT NOT NULL DEFAULT ''",
        "CREATE TABLE IF NOT EXISTS `plan_amounts_new` (`itemId` INTEGER NOT NULL, `year` INTEGER NOT NULL, " +
            "`month` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `method` TEXT NOT NULL, " +
            "PRIMARY KEY(`itemId`, `method`, `year`, `month`))",
        "INSERT INTO `plan_amounts_new` (`itemId`, `year`, `month`, `amount`, `method`) " +
            "SELECT a.`itemId`, a.`year`, a.`month`, a.`amount`, " +
            "CASE WHEN i.`type` = 'EXPENSE' THEN 'CASH' ELSE '' END " +
            "FROM `plan_amounts` a LEFT JOIN `plan_items` i ON i.`id` = a.`itemId`",
        "DROP TABLE `plan_amounts`",
        "ALTER TABLE `plan_amounts_new` RENAME TO `plan_amounts`",
    )

    /** 版本 → 從上一版升到這一版要執行的 SQL。 */
    val statements: Map<Int, List<String>> = mapOf(2 to toVersion2, 3 to toVersion3, 4 to toVersion4, 5 to toVersion5)

    /**
     * 情境的變動存成 JSON，裡面的期別是半月編號（R-PER-01 之前）。
     * 這裡只換算 `fromIndex`／`startIndex`／`atIndex` 三個數字；
     * 舊的 `payHalf` 欄位讀回來時會被忽略（`ignoreUnknownKeys`），不用處理。
     */
    private val indexFields = Regex("\"(fromIndex|startIndex|atIndex)\":([0-9]+)")

    internal fun rewriteScenarioIndexes(json: String): String =
        indexFields.replace(json) { m ->
            val half = m.groupValues[2].toInt()
            "\"" + m.groupValues[1] + "\":" + (half / 24 * 12 + half % 24 / 2)
        }

    private fun migrateScenarioJson(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<Pair<Long, String>>()
        db.query("SELECT `id`, `changesJson` FROM `scenarios`").use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0) to cursor.getString(1)
        }
        rows.forEach { (id, changes) ->
            db.execSQL("UPDATE `scenarios` SET `changesJson` = ? WHERE `id` = ?", arrayOf(rewriteScenarioIndexes(changes), id))
        }
    }

    val all: Array<Migration> = statements.map { (to, sql) ->
        object : Migration(to - 1, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach(db::execSQL)
                if (to == 4) migrateScenarioJson(db)
            }
        }
    }.toTypedArray()
}
