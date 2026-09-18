package tw.myfsl.app.core.data

import tw.myfsl.app.core.data.db.AccountEntity
import tw.myfsl.app.core.data.db.BalanceSnapshotEntity
import tw.myfsl.app.core.data.db.CardInstallmentEntity
import tw.myfsl.app.core.data.db.CheckInEntity
import tw.myfsl.app.core.data.db.ItemActualEntity
import tw.myfsl.app.core.data.db.LedgerEntryEntity
import tw.myfsl.app.core.data.db.PlanAmountEntity
import tw.myfsl.app.core.data.db.PlanGroupEntity
import tw.myfsl.app.core.data.db.PlanItemEntity
import tw.myfsl.app.core.data.db.ScenarioEntity
import tw.myfsl.app.core.model.AppSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

@Serializable
data class SettingsBackup(
    val safetyLevel: Long,
    val horizonMonths: Int,
    val checkInDay: Int,
    val pickCard: Boolean,
    val cashAccountId: Long? = null,
    val transferAccountId: Long? = null,
    val cardPostingDays: Int,
) {
    fun toSettings(current: AppSettings) = current.copy(
        safetyLevel = safetyLevel,
        horizonMonths = horizonMonths,
        checkInDay = DayOfWeek.of(checkInDay.coerceIn(1, 7)),
        pickCard = pickCard,
        cashAccountId = cashAccountId,
        transferAccountId = transferAccountId,
        cardPostingDays = cardPostingDays,
    )

    companion object {
        fun of(s: AppSettings) = SettingsBackup(
            s.safetyLevel, s.horizonMonths, s.checkInDay.value, s.pickCard, s.cashAccountId, s.transferAccountId, s.cardPostingDays,
        )
    }
}

/** 完整備份檔：所有資料表原樣保存（含 id），加上設定。 */
@Serializable
data class BackupFile(
    val app: String = APP,
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAtMillis: Long,
    val accounts: List<AccountEntity> = emptyList(),
    val snapshots: List<BalanceSnapshotEntity> = emptyList(),
    val groups: List<PlanGroupEntity> = emptyList(),
    val items: List<PlanItemEntity> = emptyList(),
    val amounts: List<PlanAmountEntity> = emptyList(),
    val actuals: List<ItemActualEntity> = emptyList(),
    val ledger: List<LedgerEntryEntity> = emptyList(),
    val installments: List<CardInstallmentEntity> = emptyList(),
    val scenarios: List<ScenarioEntity> = emptyList(),
    val checkIns: List<CheckInEntity> = emptyList(),
    val settings: SettingsBackup? = null,
) {
    companion object {
        const val APP = "MyFSL"
        const val FORMAT_VERSION = 1
    }
}

/** 還原前給使用者看的摘要。 */
data class BackupSummary(
    val exportedAtMillis: Long,
    val accounts: Int,
    val items: Int,
    val ledger: Int,
    val installments: Int,
    val scenarios: Int,
    val warnings: List<String>,
) {
    val text: String get() = "帳戶 $accounts 個、計畫項目 $items 個、記帳 $ledger 筆、分期 $installments 筆、情境 $scenarios 個"
}

sealed interface BackupReadResult {
    data class Ok(val file: BackupFile, val summary: BackupSummary) : BackupReadResult
    data class Error(val message: String) : BackupReadResult
}

object BackupCodec {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupReadResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text.removePrefix(BOM))
        } catch (e: SerializationException) {
            return BackupReadResult.Error("這不是 MyFSL 的備份檔，或檔案已損壞")
        } catch (e: IllegalArgumentException) {
            return BackupReadResult.Error("這不是 MyFSL 的備份檔，或檔案已損壞")
        }
        if (file.app != BackupFile.APP) return BackupReadResult.Error("這不是 MyFSL 的備份檔")
        if (file.formatVersion > BackupFile.FORMAT_VERSION) {
            return BackupReadResult.Error("這個備份來自較新版本的 App，請先更新 App 再還原")
        }

        fun <T> duplicates(list: List<T>, key: (T) -> Any): Boolean = list.groupBy(key).any { it.value.size > 1 }
        val duplicated = listOfNotNull(
            "帳戶".takeIf { duplicates(file.accounts) { it.id } },
            "群組".takeIf { duplicates(file.groups) { it.id } },
            "項目".takeIf { duplicates(file.items) { it.id } },
            "記帳".takeIf { duplicates(file.ledger) { it.id } },
            "分期".takeIf { duplicates(file.installments) { it.id } },
            "計畫金額".takeIf { duplicates(file.amounts) { listOf(it.itemId, it.method, it.year, it.month) } },
        )
        if (duplicated.isNotEmpty()) return BackupReadResult.Error("備份檔內容有重複（${duplicated.joinToString("、")}），無法還原")

        val accountIds = file.accounts.map { it.id }.toSet()
        val itemIds = file.items.map { it.id }.toSet()
        val groupIds = file.groups.map { it.id }.toSet()
        val installmentIds = file.installments.map { it.id }.toSet()
        val warnings = buildList {
            val orphanItems = file.items.count { it.groupId !in groupIds }
            if (orphanItems > 0) add("$orphanItems 個項目的群組不存在")
            val orphanLedger = file.ledger.count {
                (it.itemId != null && it.itemId !in itemIds) ||
                    (it.accountId != null && it.accountId !in accountIds) ||
                    (it.installmentId != null && it.installmentId !in installmentIds)
            }
            if (orphanLedger > 0) add("$orphanLedger 筆記帳對應的項目、帳戶或分期不存在")
            val orphanAmounts = file.amounts.count { it.itemId !in itemIds }
            if (orphanAmounts > 0) add("$orphanAmounts 筆計畫金額的項目不存在，會被略過")
            val orphanSnapshots = file.snapshots.count { it.accountId !in accountIds }
            if (orphanSnapshots > 0) add("$orphanSnapshots 筆餘額校正的帳戶不存在，會被略過")
        }
        val cleaned = file.copy(
            amounts = file.amounts.filter { it.itemId in itemIds },
            actuals = file.actuals.filter { it.itemId in itemIds },
            snapshots = file.snapshots.filter { it.accountId in accountIds },
        )
        return BackupReadResult.Ok(
            cleaned,
            BackupSummary(
                file.exportedAtMillis, file.accounts.size, file.items.size, file.ledger.size,
                file.installments.size, file.scenarios.size, warnings,
            ),
        )
    }
}

/** UTF-8 BOM（Excel 存檔時會加在開頭）。用碼位寫，避免原始碼裡出現看不見的字元。 */
private const val BOM = "\uFEFF"
