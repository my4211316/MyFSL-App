package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import java.time.LocalDate

/** 紀錄頁與本期頁顯示記帳的規則。 */
object RecordRules {

    /** 本週檢查產生的記帳在紀錄頁的標示；自己記的沒有標示。 */
    fun sourceLabel(entry: LedgerEntry): String? = when (entry.source) {
        EntrySource.MANUAL -> null
        EntrySource.MISSED -> if (entry.amount >= 0) "漏記差額" else "多記差額"
        EntrySource.CONFIRMED -> "到期確認"
    }

    data class MonthTotals(val expense: Money, val income: Money)

    fun monthTotals(snapshot: FinanceSnapshot, year: Int, month: Int): MonthTotals {
        val entries = snapshot.ledger.filter { it.date.year == year && it.date.monthValue == month }
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
}
