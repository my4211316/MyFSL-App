package tw.myfsl.app.core.model

import java.time.DayOfWeek
import java.time.LocalDate

data class AppSettings(
    val checkInDay: DayOfWeek = DayOfWeek.SUNDAY,
    /** 現金水位低於此金額就警示。 */
    val safetyLevel: Money = 30_000,
    val horizonMonths: Int = 24,
    val onboarded: Boolean = false,
    /** 記帳時是否指定哪一張信用卡；關閉時刷卡只歸到「信用卡」。 */
    val pickCard: Boolean = true,
    /** 支付方式「現金」扣款的帳戶；未設定時用第一個現金帳戶。 */
    val cashAccountId: Long? = null,
    /** 支付方式「轉帳」扣款的帳戶；未設定時用第一個銀行帳戶。 */
    val transferAccountId: Long? = null,
    /** 信用卡對帳時，最近幾天的刷卡可能還沒入帳。 */
    val cardPostingDays: Int = 5,
    /** 上次匯出完整備份的日期（epochDay）；從沒備份過為 null。 */
    val lastBackupEpochDay: Long? = null,
    /** 沒有指定卡片的刷卡（記帳、計畫、分期）歸到這張卡；未設定時用排序第一張卡。 */
    val defaultCardId: Long? = null,
    /**
     * 到期項目的起算日（epochDay）：這天（含）以前到期的款項視為已經包含在校正的餘額裡，不會列在本月到期。
     * 第一次開啟時設為當天；null 表示還沒開始（視為今天）。
     */
    val autoPostFrom: Long? = null,
    /**
     * 資料世代：整份替換資料（還原、放回、清除、載入示意資料）時加一，資料庫裡存一份相同的值。
     * 不會進備份，也不會被設定畫面改到。
     */
    val dataGeneration: Long = 0,
    /** 到期前幾天提醒（R-REM-01）；空的表示不提醒。 */
    val reminderDays: List<Int> = listOf(7, 3),
)

/** 某個時間點的完整財務資料，供純計算函式使用。 */
data class FinanceSnapshot(
    val today: LocalDate,
    val accounts: List<Account>,
    val groups: List<PlanGroup>,
    val items: List<PlanItem>,
    /** 年度 → 該年度計畫金額。 */
    val amountsByYear: Map<Int, MonthlyAmounts>,
    val actuals: List<ItemActual>,
    val ledger: List<LedgerEntry>,
    /** 未結清的分期。 */
    val installments: List<CardInstallment> = emptyList(),
    val settings: AppSettings,
    val lastCheckIn: CheckIn? = null,
    /** 刷卡但未指定卡片、且晚於最近一次「全部卡片一起對帳」的金額合計。 */
    val unassignedCardSpending: Money = 0,
    /** 最近一次「全部卡片一起對帳」的時點；在它之後的未指定卡片刷卡才算在預設卡片上。沒有為 null。 */
    val fullCardReconcile: RecordMark? = null,
    /** 選了「這個月沒有」的到期項目識別碼；記下的項目看記帳上的識別碼（[recordedKeys]）。 */
    val postedKeys: Set<String> = emptySet(),
    /** 延期款項（含已付清的）。 */
    val deferrals: List<Deferral> = emptyList(),
    /** 使用者輸入的帳單（R-CARD-23）。 */
    val cardStatements: List<CardStatement> = emptyList(),
    /**
     * 這份快照的資料世代：資料庫與設定兩邊一致時才有效，否則為 [NO_GENERATION]（資料正在更新）。
     * 畫面發出的寫入要帶著它，資料層確認還是同一個世代才會寫（避免還原後用舊的 id 改到別筆資料）。
     */
    val generation: Long = NO_GENERATION,
) {
    val activeAccounts: List<Account> get() = accounts.filter { !it.archived }

    /** 到期項目的起算日；這天（含）以前到期的款項視為已包含在餘額裡。 */
    val trackingFrom: LocalDate
        get() = settings.autoPostFrom?.let(LocalDate::ofEpochDay)?.takeIf { !it.isAfter(today) } ?: today

    /** 已經處理過的到期項目：記下的（記帳上的識別碼）與選了「這個月沒有」的。 */
    val recordedKeys: Set<String> by lazy { postedKeys + ledger.mapNotNull { it.postingKey } }
    val activeItems: List<PlanItem> get() = items.filter { !it.archived }

    val activeCards: List<Account> get() = activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD }.sortedBy { it.sortOrder }

    /** 沒有指定卡片的刷卡歸到哪一張卡；沒有任何信用卡時為 null。 */
    val defaultCardId: Long?
        get() = settings.defaultCardId?.takeIf { id -> activeCards.any { it.id == id } } ?: activeCards.firstOrNull()?.id

    /** 某張卡某一期（結帳年月）使用者輸入的帳單；沒有為 null。 */
    fun statementOf(cardId: Long, ym: java.time.YearMonth): CardStatement? =
        cardStatements.firstOrNull { it.cardId == cardId && it.year == ym.year && it.month == ym.monthValue }

    /** 已依合約（繳款條件或攤還條件）自動繳款的負債帳戶。 */
    fun isAutoManagedDebt(accountId: Long?): Boolean {
        val account = account(accountId) ?: return false
        return account.hasCardSchedule || account.loan != null
    }

    /**
     * 某項目在某月是否仍在使用：封存只影響封存月份（含）之後，之前的計畫與紀錄照舊。
     */
    fun isItemActiveIn(item: PlanItem, year: Int, month: Int): Boolean {
        val from = item.archivedFrom ?: return !item.archived
        return year * 12 + (month - 1) < from
    }

    fun account(id: Long?): Account? = accounts.firstOrNull { it.id == id }
    fun item(id: Long?): PlanItem? = items.firstOrNull { it.id == id }
    fun group(id: Long): PlanGroup? = groups.firstOrNull { it.id == id }

    /** 某年度的計畫；沒有該年度時沿用最接近的前一個年度，再沒有就用最早的年度。 */
    fun planForYear(year: Int): MonthlyAmounts =
        amountsByYear[year]
            ?: amountsByYear.keys.filter { it < year }.maxOrNull()?.let { amountsByYear[it] }
            ?: amountsByYear.keys.minOrNull()?.let { amountsByYear[it] }
            ?: emptyMap()

    fun planAmount(line: PlanLine, year: Int, month: Int): Money =
        planForYear(year)[line]?.getOrNull(month - 1) ?: 0

    /** 項目在所有年度出現過的計畫列，依支付方式順序排列。 */
    fun linesOf(itemId: Long): List<PlanLine> =
        amountsByYear.values.flatMap { it.keys }
            .filter { it.itemId == itemId }
            .distinct()
            .sortedBy { it.method?.ordinal ?: -1 }

    /** 項目在某月有計畫金額的支付方式。 */
    fun plannedMethods(itemId: Long, year: Int, month: Int): List<PaymentMethod> =
        linesOf(itemId).filter { it.method != null && planAmount(it, year, month) > 0 }.mapNotNull { it.method }

    /** 支付方式對應的扣款帳戶；信用卡回傳 null（由卡片決定或歸入信用卡合計）。 */
    fun methodAccountId(method: PaymentMethod): Long? {
        val liquid = activeAccounts.filter { it.kind.isLiquid }
        return when (method) {
            PaymentMethod.CASH -> settings.cashAccountId?.takeIf { id -> liquid.any { it.id == id } }
                ?: liquid.firstOrNull { it.kind == AccountKind.CASH }?.id
                ?: liquid.firstOrNull()?.id

            PaymentMethod.TRANSFER -> settings.transferAccountId?.takeIf { id -> liquid.any { it.id == id } }
                ?: liquid.firstOrNull { it.kind == AccountKind.BANK }?.id
                ?: liquid.firstOrNull()?.id

            PaymentMethod.CREDIT_CARD -> null
        }
    }

    companion object {
        /** 資料正在更新、世代還沒一致；帶這個值的寫入一律拒絕。 */
        const val NO_GENERATION = -1L

        fun empty(today: LocalDate) = FinanceSnapshot(
            today = today,
            accounts = emptyList(),
            groups = emptyList(),
            items = emptyList(),
            amountsByYear = emptyMap(),
            actuals = emptyList(),
            ledger = emptyList(),
            settings = AppSettings(),
        )
    }
}
