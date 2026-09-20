package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanLine

enum class TableRowKind { GROUP, ITEM, TOTAL, CASH_FLOW }

/** 年度月份表的一列。 */
data class TableRow(
    val kind: TableRowKind,
    val label: String,
    val monthly: List<Money> = List(12) { 0L },
    val itemId: Long? = null,
    val flexible: Boolean = false,
    /** 依攤還或循環條件自動估算，不是自己填的。 */
    val auto: Boolean = false,
) {
    val total: Money get() = monthly.sum()
}

data class PlanTable(val year: Int, val rows: List<TableRow>, val currentMonth: Int?)

/** 年度計畫表：依群組排列的明細列，最後是刷卡、非刷卡、繳卡費＋貸款、月現金流合計（R-PLN-02）。 */
object PlanTableBuilder {

    fun build(snapshot: FinanceSnapshot, year: Int): PlanTable {
        val amounts = snapshot.planForYear(year)
        val summary = PlanSummaryCalculator.summarize(snapshot, year)
        val items = snapshot.activeItems
        val rows = mutableListOf<TableRow>()

        snapshot.groups.sortedBy { it.sortOrder }.forEach { group ->
            val lines = items.filter { it.groupId == group.id }.sortedBy { it.sortOrder }.mapNotNull { item ->
                val months = amounts[PlanLine(item.id)] ?: return@mapNotNull null
                if (months.none { it != 0L }) return@mapNotNull null
                TableRow(
                    kind = TableRowKind.ITEM,
                    label = item.name,
                    monthly = months,
                    itemId = item.id,
                    flexible = item.flexibility == Flexibility.FLEXIBLE,
                )
            }
            if (lines.isNotEmpty()) {
                rows += TableRow(TableRowKind.GROUP, group.name)
                rows += lines
            }
        }

        // 繳卡費＋貸款合計裡，扣掉自己填的轉帳，剩下的是依攤還或循環條件自動產生的。
        val plannedDebt = LongArray(12)
        val kinds = snapshot.accounts.associate { it.id to it.kind }
        items.filter { it.type == FlowType.TRANSFER && kinds[it.toAccountId]?.isLiability == true }.forEach { item ->
            amounts[PlanLine(item.id)]?.forEachIndexed { m, v -> plannedDebt[m] += v }
        }
        val autoDebt = List(12) { m -> (summary.debtPayments[m] - plannedDebt[m]).coerceAtLeast(0) }
        if (autoDebt.any { it != 0L }) {
            rows += TableRow(TableRowKind.GROUP, "自動產生")
            rows += TableRow(TableRowKind.ITEM, "貸款與卡費（依條件估算）", autoDebt, auto = true)
        }

        rows += TableRow(TableRowKind.TOTAL, "收入合計", summary.income)
        rows += TableRow(TableRowKind.TOTAL, "刷卡消費", summary.cardSpending)
        rows += TableRow(TableRowKind.TOTAL, "非刷卡支出", summary.nonCardSpending)
        if (summary.cardInterest.any { it != 0L }) rows += TableRow(TableRowKind.TOTAL, "循環利息（估）", summary.cardInterest, auto = true)
        rows += TableRow(TableRowKind.TOTAL, "繳卡費＋貸款", summary.debtPayments)
        rows += TableRow(TableRowKind.CASH_FLOW, "月現金流", summary.monthlyCashFlow)

        val today = snapshot.today
        return PlanTable(year, rows, if (today.year == year) today.monthValue else null)
    }
}
