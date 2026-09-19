package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PostingKeys
import java.time.LocalDate

/** 紀錄頁與本期頁顯示記帳的規則。 */
object RecordRules {

    /** 紀錄頁的來源標示；自己記的沒有標示（退款標「退款」）。 */
    fun sourceLabel(entry: LedgerEntry): String? = when (entry.source) {
        EntrySource.MANUAL -> if (entry.amount < 0) "退款" else null
        EntrySource.MISSED -> if (entry.amount >= 0) "漏記差額" else "多記差額"
        EntrySource.CONFIRMED -> if (entry.postingKey?.startsWith(PostingKeys.DEFERRAL) == true) "延期款" else "到期確認"
        EntrySource.DUE -> when {
            entry.isInstallmentPrincipal -> "分期入帳"
            entry.postingKey?.startsWith(PostingKeys.INSTALLMENT_FEE) == true -> "分期手續費"
            entry.postingKey?.startsWith(PostingKeys.CARD_INTEREST) == true -> "循環利息"
            entry.postingKey?.startsWith(PostingKeys.CARD_PAYMENT) == true -> "繳卡費"
            entry.postingKey?.startsWith(PostingKeys.LOAN) == true -> "貸款月繳"
            else -> "每月固定"
        }
        EntrySource.STATEMENT -> "帳單差額"
    }

    data class MonthTotals(val expense: Money, val income: Money)

    /** 本月至今支出與收入：分期各期入帳的本金不算（消費當月已算過全額）。 */
    fun monthTotals(snapshot: FinanceSnapshot, year: Int, month: Int): MonthTotals {
        val entries = snapshot.ledger.filter { it.date.year == year && it.date.monthValue == month && it.countsForBudget }
        return MonthTotals(
            expense = entries.filter { it.type == FlowType.EXPENSE }.sumOf { it.amount },
            income = entries.filter { it.type == FlowType.INCOME }.sumOf { it.amount },
        )
    }

    data class MissedSummary(val count: Int, val total: Money)

    /** 本月對帳找出的漏記。 */
    fun missedSummary(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): MissedSummary {
        val entries = snapshot.ledger.filter {
            it.source == EntrySource.MISSED && it.amount > 0 &&
                it.date.year == date.year && it.date.monthValue == date.monthValue
        }
        return MissedSummary(entries.size, entries.sumOf { it.amount })
    }

    /** 本期頁的一行提示；沒有漏記時為 null。 */
    fun missedLabel(summary: MissedSummary): String? =
        if (summary.count == 0) null else "本月漏記 ${summary.count} 筆 · ${MoneyFormat.currency(summary.total)}"

    /**
     * 補登找回的單據（R-REC-03）：同項目、同支付方式、同月份還有正數的漏記差額時，
     * 這筆很可能就是當時漏記的那筆。回傳可以沖掉的漏記差額（最近的一筆）；沒有時為 null。
     */
    fun missedMatch(snapshot: FinanceSnapshot, itemId: Long, method: PaymentMethod?, date: LocalDate): LedgerEntry? =
        snapshot.ledger
            .filter {
                it.source == EntrySource.MISSED && it.amount > 0 && it.itemId == itemId && it.method == method &&
                    it.date.year == date.year && it.date.monthValue == date.monthValue
            }
            .maxWithOrNull(compareBy({ it.date }, { it.createdAt }))

    /**
     * 用補登的明細取代漏記差額：明細沿用差額的日期與寫入時間（對帳前就發生了，餘額不會再扣一次），
     * 差額扣掉這筆；明細比差額大的部分是新的花費，照今天記。
     */
    data class MissedReplacement(
        /** 沖銷後的漏記差額；null 表示整筆刪掉。 */
        val remainingMissed: LedgerEntry?,
        /** 取代差額的明細（沿用差額的時間）。 */
        val detail: LedgerEntry,
        /** 超過差額、要照今天記的部分；沒有時為 null。 */
        val extra: LedgerEntry?,
    )

    fun replaceMissed(missed: LedgerEntry, entry: LedgerEntry): MissedReplacement {
        val absorbed = minOf(entry.amount, missed.amount)
        val left = missed.amount - absorbed
        return MissedReplacement(
            remainingMissed = if (left > 0) missed.copy(amount = left) else null,
            detail = entry.copy(amount = absorbed, date = missed.date, createdAt = missed.createdAt),
            extra = (entry.amount - absorbed).takeIf { it > 0 }?.let { entry.copy(amount = it) },
        )
    }
}
