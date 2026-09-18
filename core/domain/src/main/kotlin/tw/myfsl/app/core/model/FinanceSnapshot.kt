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
    /** 刷卡但未指定卡片、且晚於最近一次信用卡校正的金額合計。 */
    val unassignedCardSpending: Money = 0,
) {
    val activeAccounts: List<Account> get() = accounts.filter { !it.archived }
    val activeItems: List<PlanItem> get() = items.filter { !it.archived }

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
