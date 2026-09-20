package tw.myfsl.app.core.model

import java.time.LocalDate

enum class ActualStatus(val label: String) {
    IN_PROGRESS("進行中"),
    DONE("已完成"),
    POSTPONED("延到下月"),
}

/** 記帳的來源。 */
enum class EntrySource {
    /** 使用者自己記的。 */
    MANUAL,

    /** 本週檢查對帳算出的差額。 */
    MISSED,

    /** 本週檢查的到期確認補記。 */
    CONFIRMED,

    /** 到期記下（R-DUE）：在記帳畫面點本月到期的項目記下的，例如每月固定的帳單、貸款月繳、繳卡費、循環利息、分期每期入帳。 */
    DUE,

    /** 帳單校正（R-CARD-23）：帳單金額和 App 估計的差額。 */
    STATEMENT,
}

/**
 * 延到之後才付的款項（例如 9 月的保養延到 10 月）。
 * 金額在延期當下固定，之後改計畫不會跟著變；到期時在本週檢查確認，可以再延一次。
 */
data class Deferral(
    val id: Long = 0,
    val itemId: Long,
    val method: PaymentMethod?,
    /** 原本應付的年月。 */
    val fromYear: Int,
    val fromMonth: Int,
    /** 現在預計要付的年月。 */
    val dueYear: Int,
    val dueMonth: Int,
    /** 延期時還沒付的金額。 */
    val amount: Money,
    val settled: Boolean = false,
) {
    val line: PlanLine get() = PlanLine(itemId)
    val key: String get() = "deferral:$id"
    fun isDueBy(year: Int, month: Int): Boolean = dueYear * 12 + dueMonth <= year * 12 + month
}

/** 某項目某月的狀態，由到期確認寫入；金額一律由記帳加總得到（R-MIX-01：不再分支付方式）。 */
data class ItemActual(
    val itemId: Long,
    val year: Int,
    val month: Int,
    val status: ActualStatus,
    val updatedOn: LocalDate,
) {
    val line: PlanLine get() = PlanLine(itemId)
}

/** 一筆記帳。本週檢查的差額與補記也是記帳，只是 [source] 不同。 */
data class LedgerEntry(
    val id: Long = 0,
    val date: LocalDate,
    val type: FlowType,
    val amount: Money,
    val itemId: Long? = null,
    /** 支出的支付方式；收入與轉帳為 null。 */
    val method: PaymentMethod? = null,
    /** 實際進出的帳戶：收入為入帳帳戶，支出與轉帳為扣款帳戶。刷卡但未指定卡片時為 null。 */
    val accountId: Long? = null,
    /** 轉帳的轉入帳戶。 */
    val toAccountId: Long? = null,
    val note: String = "",
    val source: EntrySource = EntrySource.MANUAL,
    /** 這筆是分期消費或分期某一期入帳時，對應的分期 id。 */
    val installmentId: Long? = null,
    /** 寫入時間（epoch 毫秒），由 repository 在新增時填入。 */
    val createdAt: Long = 0,
    /**
     * 到期項目與延期付款的識別碼，例如 `plan:12:CASH:2026-09:15`、`loan:5:2026-10`、`inst:3:2`、`deferral:7`。
     * 同一個識別碼只會有一筆；記下後這個到期項目就不再列出，刪掉這筆記帳則重新列出。
     */
    val postingKey: String? = null,
) {
    /** 分期消費本身：全額算進預算，但不直接變成卡債（卡債由各期入帳累加）。 */
    val isInstallmentPurchase: Boolean get() = installmentId != null && postingKey == null

    /** 分期某一期的本金入帳：變成卡債，但不再算一次預算。 */
    val isInstallmentPrincipal: Boolean get() = postingKey?.startsWith(PostingKeys.INSTALLMENT_PRINCIPAL) == true

    /** 銀行在卡上產生的款項（循環利息、分期各期與手續費），不是自己的消費。 */
    val isCardCharge: Boolean
        get() = postingKey?.let {
            it.startsWith(PostingKeys.CARD_INTEREST) || it.startsWith(PostingKeys.INSTALLMENT_PRINCIPAL) ||
                it.startsWith(PostingKeys.INSTALLMENT_FEE) || it.startsWith(PostingKeys.STATEMENT)
        } == true

    /**
     * 屬於哪個月的預算：每月固定的到期項目看識別碼裡的月份（逾期才付也算原本那個月），其他看日期。
     */
    val budgetMonth: java.time.YearMonth
        get() {
            val key = postingKey
            if (key != null && key.startsWith(PostingKeys.PLAN)) {
                // plan:<項目>:<年月>:<日>
                key.split(':').getOrNull(2)?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }?.let { return it }
            }
            return java.time.YearMonth.from(date)
        }

    /** 是否算進項目的實際花費（預算進度、紀錄合計）。 */
    val countsForBudget: Boolean get() = !isInstallmentPrincipal
}

/** 到期項目識別碼的前綴。 */
object PostingKeys {
    const val PLAN = "plan:"
    const val LOAN = "loan:"
    const val CARD_INTEREST = "cardint:"
    const val CARD_PAYMENT = "cardpay:"
    const val INSTALLMENT_PRINCIPAL = "inst:"
    const val INSTALLMENT_FEE = "instfee:"
    const val DEFERRAL = "deferral:"

    /** 帳單校正的差額：`stmt:<卡片>:<結帳年月>`（R-CARD-23）。 */
    const val STATEMENT = "stmt:"
}

/**
 * 使用者輸入的某張卡某一期帳單（R-CARD-23）。[year]/[month] 是結帳日所在的年月。
 * 帳單金額和 App 估計的差額另外記成一筆記帳（識別碼 `stmt:`）；這裡保存帳單上的最低應繳，
 * 以及校正時是否把這一期的循環利息一併算進帳單（[coversInterest]，刪除校正時要把利息放回本月到期）。
 */
data class CardStatement(
    val cardId: Long,
    val year: Int,
    val month: Int,
    val amount: Money,
    val minimumPayment: Money? = null,
    val coversInterest: Boolean = false,
) {
    val yearMonth: java.time.YearMonth get() = java.time.YearMonth.of(year, month)
}

/** 帳戶在某天校正後的餘額。 */
data class BalanceSnapshot(
    val accountId: Long,
    val date: LocalDate,
    val balance: Money,
)

data class CheckIn(
    val id: Long = 0,
    val date: LocalDate,
    val note: String = "",
)

/** 餘額校正的時點：先比日期，同一天再比寫入時間（epoch 毫秒）。 */
data class RecordMark(val date: LocalDate, val recordedAt: Long = 0)

/** 這筆記帳是否發生在 [mark] 之後：日期較晚，或同一天但寫入時間較晚。 */
fun LedgerEntry.isAfter(mark: RecordMark): Boolean =
    date.isAfter(mark.date) || (date == mark.date && createdAt > mark.recordedAt)
