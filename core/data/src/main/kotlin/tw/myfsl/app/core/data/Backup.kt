package tw.myfsl.app.core.data

import tw.myfsl.app.core.data.db.AccountEntity
import tw.myfsl.app.core.data.db.BalanceSnapshotEntity
import tw.myfsl.app.core.data.db.CardInstallmentEntity
import tw.myfsl.app.core.data.db.CardStatementEntity
import tw.myfsl.app.core.data.db.CheckInEntity
import tw.myfsl.app.core.data.db.DeferralEntity
import tw.myfsl.app.core.data.db.PostedKeyEntity
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
    val defaultCardId: Long? = null,
    val autoPostFrom: Long? = null,
    /** 到期前幾天提醒；舊備份沒有時用預設的 7、3 天。 */
    val reminderDays: List<Int>? = null,
    /** 試算的付款假設（R-MIX-02）；舊備份沒有時用預設的「全部當現金付」。 */
    val forecastCardPercent: Int? = null,
) {
    fun toSettings(current: AppSettings) = current.copy(
        reminderDays = reminderDays ?: AppSettings().reminderDays,
        forecastCardPercent = forecastCardPercent ?: AppSettings().forecastCardPercent,
        safetyLevel = safetyLevel,
        horizonMonths = horizonMonths,
        checkInDay = DayOfWeek.of(checkInDay.coerceIn(1, 7)),
        pickCard = pickCard,
        cashAccountId = cashAccountId,
        transferAccountId = transferAccountId,
        cardPostingDays = cardPostingDays,
        defaultCardId = defaultCardId,
    )

    companion object {
        fun of(s: AppSettings) = SettingsBackup(
            s.safetyLevel, s.horizonMonths, s.checkInDay.value, s.pickCard, s.cashAccountId, s.transferAccountId, s.cardPostingDays,
            s.defaultCardId, s.autoPostFrom, s.reminderDays, s.forecastCardPercent,
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
    val postedKeys: List<PostedKeyEntity> = emptyList(),
    val deferrals: List<DeferralEntity> = emptyList(),
    /** 帳單校正（R-CARD-23）；舊備份沒有這個欄位時為空。 */
    val cardStatements: List<CardStatementEntity> = emptyList(),
    val settings: SettingsBackup? = null,
) {
    companion object {
        const val APP = "MyFSL"

        /**
         * 3：計畫不再帶支付方式（R-MIX-01），試算的付款假設改成設定裡的一個數字（R-MIX-02）。
         * 第 1 版（項目 × 支付方式）與第 2 版（項目帶支付方式）的備份還原時會自動轉換，
         * 見 [BackupCodec.upgradeFromV1] 與 [BackupCodec.upgradeFromV2]。
         */
        const val FORMAT_VERSION = 3
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
    /** 有對不上的資料：只能用「救援還原」，並列出會略過的內容（R-DATA-04）。 */
    val needsRescue: Boolean get() = warnings.isNotEmpty()
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

    /** 第 1 版備份裡「項目 × 支付方式」的一列計畫金額。 */
    @Serializable
    private data class V1Amount(val itemId: Long, val method: String = "", val year: Int, val month: Int, val amount: Long = 0)

    @Serializable
    private data class V1File(val amounts: List<V1Amount> = emptyList())

    /**
     * 把第 1 版備份升到第 2 版（R-MIX-05）：
     * 同一個項目的多個支付方式列合併成一列（金額相加），項目的支付方式取金額最大的那一個；
     * 每月狀態去掉支付方式只留一筆（已完成優先）；到期識別碼拿掉中間的支付方式段。
     */
    private fun upgradeFromV1(file: BackupFile, text: String): BackupFile {
        val legacy = try {
            json.decodeFromString(V1File.serializer(), text).amounts
        } catch (e: SerializationException) {
            emptyList()
        }
        val methodByItem = legacy
            .filter { it.method.isNotEmpty() && it.method != "-" }
            .groupBy { it.itemId }
            .mapValues { (_, rows) ->
                rows.groupBy { it.method }.maxByOrNull { (_, ms) -> ms.sumOf { it.amount } }?.key
            }
        // 第 3 版的項目沒有支付方式；舊檔的刷卡比例改成試算的付款假設（R-MIX-02）。
        val cardTotal = legacy.filter { it.method == "CREDIT_CARD" }.sumOf { it.amount }
        val allTotal = legacy.sumOf { it.amount }
        val cardPercent = if (allTotal > 0) Math.round(cardTotal * 100.0 / allTotal).toInt() else 0
        val settings = file.settings?.copy(forecastCardPercent = cardPercent)
        val amounts = file.amounts
            .groupBy { Triple(it.itemId, it.year, it.month) }
            .map { (key, rows) -> PlanAmountEntity(key.first, key.second, key.third, rows.sumOf { it.amount }) }
        val actuals = file.actuals
            .groupBy { Triple(it.itemId, it.year, it.month) }
            .map { (_, rows) -> rows.minByOrNull { it.status } ?: rows.first() }
        val ledger = file.ledger.map { it.copy(postingKey = it.postingKey?.let(::upgradePlanKey)) }
        val postedKeys = file.postedKeys.map { it.copy(key = upgradePlanKey(it.key)) }
        return file.copy(
            formatVersion = BackupFile.FORMAT_VERSION,
            amounts = amounts,
            actuals = actuals,
            ledger = ledger,
            postedKeys = postedKeys,
            settings = settings,
        )
    }

    /** 第 2 版備份裡帶著支付方式的項目。 */
    @Serializable
    private data class V2Item(val id: Long = 0, val type: String = "", val method: String? = null)

    @Serializable
    private data class V2File(val items: List<V2Item> = emptyList(), val amounts: List<V1Amount> = emptyList())

    /**
     * 把第 2 版備份升到第 3 版（R-MIX-02）：項目上的支付方式換算成整體的刷卡比例，
     * 寫進設定的「試算付款假設」；項目本身不再帶支付方式。
     */
    private fun upgradeFromV2(file: BackupFile, text: String): BackupFile {
        val legacy = try {
            json.decodeFromString(V2File.serializer(), text)
        } catch (e: SerializationException) {
            null
        }
        val cardItems = legacy?.items.orEmpty().filter { it.method == "CREDIT_CARD" }.map { it.id }.toSet()
        val byItem = legacy?.amounts.orEmpty().groupBy { it.itemId }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
        val cardTotal = byItem.filterKeys { it in cardItems }.values.sum()
        val allTotal = byItem.values.sum()
        val cardPercent = if (allTotal > 0) Math.round(cardTotal * 100.0 / allTotal).toInt() else 0
        return file.copy(
            formatVersion = BackupFile.FORMAT_VERSION,
            settings = file.settings?.copy(forecastCardPercent = cardPercent),
        )
    }

    /** `plan:12:CASH:2026-09:15` → `plan:12:2026-09:15`；其他識別碼原樣保留。 */
    internal fun upgradePlanKey(key: String): String {
        if (!key.startsWith("plan:")) return key
        val parts = key.split(":")
        if (parts.size != 5) return key
        return listOf(parts[0], parts[1], parts[3], parts[4]).joinToString(":")
    }

    fun decode(text: String): BackupReadResult {
        val raw = try {
            json.decodeFromString(BackupFile.serializer(), text.removePrefix(BOM))
        } catch (e: SerializationException) {
            return BackupReadResult.Error("這不是 MyFSL 的備份檔，或檔案已損壞")
        } catch (e: IllegalArgumentException) {
            return BackupReadResult.Error("這不是 MyFSL 的備份檔，或檔案已損壞")
        }
        val v2 = if (raw.formatVersion < 2) upgradeFromV1(raw, text.removePrefix(BOM)) else raw
        val file = if (v2.formatVersion < 3) upgradeFromV2(v2, text.removePrefix(BOM)) else v2
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
            "計畫金額".takeIf { duplicates(file.amounts) { listOf(it.itemId, it.year, it.month) } },
            "延期款".takeIf { duplicates(file.deferrals) { it.id } },
            "帳單".takeIf { duplicates(file.cardStatements) { listOf(it.cardId, it.year, it.month) } },
            "到期項目識別碼".takeIf { duplicates(file.ledger.mapNotNull { it.postingKey }) { it } },
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
            val orphanDeferrals = file.deferrals.count { it.itemId !in itemIds }
            if (orphanDeferrals > 0) add("$orphanDeferrals 筆延期款的項目不存在，會被略過")
        }
        val cleaned = file.copy(
            amounts = file.amounts.filter { it.itemId in itemIds },
            actuals = file.actuals.filter { it.itemId in itemIds },
            snapshots = file.snapshots.filter { it.accountId in accountIds },
            deferrals = file.deferrals.filter { it.itemId in itemIds },
            cardStatements = file.cardStatements.filter { it.cardId in accountIds },
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
