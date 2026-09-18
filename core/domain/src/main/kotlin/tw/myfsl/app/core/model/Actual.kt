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
}

/** 某計畫列某月的狀態，由到期確認寫入；金額一律由記帳加總得到。 */
data class ItemActual(
    val itemId: Long,
    val method: PaymentMethod?,
    val year: Int,
    val month: Int,
    val status: ActualStatus,
    val updatedOn: LocalDate,
) {
    val line: PlanLine get() = PlanLine(itemId, method)
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
    /** 這筆是分期消費時，對應的分期 id；卡片餘額改由各期入帳計算。 */
    val installmentId: Long? = null,
    /** 寫入時間（epoch 毫秒），由 repository 在新增時填入。 */
    val createdAt: Long = 0,
)

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
