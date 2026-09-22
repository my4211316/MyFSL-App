package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import java.time.YearMonth

enum class TableRowKind { GROUP, ITEM, TOTAL, CASH_FLOW }

/**
 * 年表要回答的兩個問題（R-PLS-10）。同一張格子表，換個問題，數字的意思就不一樣：
 * 刷卡是隔月繳的，所以「花」和「付」不在同一個月。
 */
enum class PlanTableView(val label: String) {
    /** 我打算花多少：刷卡列是**刷的月份**。編列預算用這個。 */
    SPEND("什麼時候花"),

    /** 每個月真的從帳戶出去多少：刷卡列不在這裡，繳卡費才在。 */
    CASH("什麼時候付"),
}

/** 年度月份表的一列。 */
data class TableRow(
    val kind: TableRowKind,
    val label: String,
    val monthly: List<Money> = List(12) { 0L },
    val itemId: Long? = null,
    val flexible: Boolean = false,
    /** 依攤還或循環條件自動估算，不是自己填的。 */
    val auto: Boolean = false,
    /**
     * 這些月份（1–12）**沒有推估值**：已經過去了，不再推估卡費與貸款（R-PLS-05、R-PLS-11），
     * 畫面顯示「已過」而不是 0——那不是「這個月不用繳」。
     */
    val pastMonths: Set<Int> = emptySet(),
) {
    val total: Money get() = monthly.sum()
}

data class PlanTable(val year: Int, val view: PlanTableView, val rows: List<TableRow>, val currentMonth: Int?)

/**
 * 年度計畫表（R-PLN-02、R-PLS-10）。
 *
 * - [PlanTableView.SPEND]：依群組排列的計畫列（項目 × 支付方式），最後是合計與月現金流。
 * - [PlanTableView.CASH]：刷卡列不出現（刷卡不動現金），改成逐卡、逐貸款的「繳 X」一列。
 *   今天以前的月份不再推估卡費與貸款（R-PLS-05），那些格子標「已過」（R-PLS-11）。
 */
object PlanTableBuilder {

    fun build(snapshot: FinanceSnapshot, year: Int, view: PlanTableView = PlanTableView.SPEND): PlanTable {
        val amounts = snapshot.planForYear(year)
        val summary = PlanSummaryCalculator.summarize(snapshot, year)
        val items = snapshot.activeItems
        val kinds = snapshot.accounts.associate { it.id to it.kind }
        val cash = view == PlanTableView.CASH
        val rows = mutableListOf<TableRow>()

        snapshot.groups.sortedBy { it.sortOrder }.forEach { group ->
            // 一列是「項目 × 支付方式」（R-MIX-01）：同一個項目有現金列和刷卡列時，各列一行並標出方式。
            val lines = items.filter { it.groupId == group.id }.sortedBy { it.sortOrder }.flatMap { item ->
                // 「什麼時候付」的表裡，這兩種列不出現：刷卡列不動現金（要等繳卡費），
                // 繳負債的轉帳改由下面逐卡逐貸款的列統一顯示，免得同一筆錢出現兩次。
                val paysDebt = item.type == FlowType.TRANSFER && kinds[item.toAccountId]?.isLiability == true
                if (cash && paysDebt) return@flatMap emptyList()
                val planLines = snapshot.planLines(item.id, year)
                planLines.filterNot { cash && it.method == PaymentMethod.CREDIT_CARD }.mapNotNull { line ->
                    val months = amounts[line] ?: return@mapNotNull null
                    if (months.none { it != 0L }) return@mapNotNull null
                    TableRow(
                        kind = TableRowKind.ITEM,
                        label = if (planLines.size > 1 && line.method != null) "${item.name}・${line.method.label}" else item.name,
                        monthly = months,
                        itemId = item.id,
                        flexible = item.flexibility == Flexibility.FLEXIBLE,
                    )
                }
            }
            if (lines.isNotEmpty()) {
                rows += TableRow(TableRowKind.GROUP, group.name)
                rows += lines
            }
        }

        if (cash) {
            // 逐卡、逐貸款一列：把「繳卡費」從黑盒子攤開，看得到哪個月要繳多少（R-PLS-10）。
            val debts = summary.cards.map { Triple(it.accountId, it.name, it.monthlyPayments) } +
                summary.loans.map { Triple(it.accountId, it.name, it.monthlyPayments) }
            val listed = debts.map { (_, name, estimate) ->
                debtRow(snapshot, year, "繳 $name", estimate, summary.autoMonths)
                // 這一年完全沒有要繳的卡或貸款就不列：只有「已過」的空列沒有資訊，只是佔位置。
            }.filter { row -> row.monthly.any { it != 0L } }
            if (listed.isNotEmpty()) {
                rows += TableRow(TableRowKind.GROUP, "繳卡費與貸款")
                rows += listed
            }
        } else {
            // 繳卡費＋貸款合計裡，扣掉自己填的轉帳，剩下的是依攤還或循環條件自動產生的。
            val plannedDebt = LongArray(12)
            items.filter { it.type == FlowType.TRANSFER && kinds[it.toAccountId]?.isLiability == true }.forEach { item ->
                snapshot.planLines(item.id, year).forEach { line ->
                    amounts[line]?.forEachIndexed { m, v -> plannedDebt[m] += v }
                }
            }
            val autoDebt = List(12) { m -> (summary.debtPayments[m] - plannedDebt[m]).coerceAtLeast(0) }
            if (autoDebt.any { it != 0L }) {
                rows += TableRow(TableRowKind.GROUP, "自動產生")
                rows += TableRow(TableRowKind.ITEM, "貸款與卡費（依條件估算）", autoDebt, auto = true)
            }
        }

        // 合計列的標籤要自己說清楚哪一列是現金（R-PLS-10）：一欄加起來不是當月的現金，
        // 刷的那一欄要等繳卡費才真的出去。列名在 132dp 內，所以用最短的說法。
        rows += TableRow(TableRowKind.GROUP, "合計")
        rows += TableRow(TableRowKind.TOTAL, "收入合計", summary.income)
        if (cash) {
            // 循環利息只是加到卡債上，不是當月從帳戶出去的錢，所以「什麼時候付」的表裡不列。
            rows += TableRow(TableRowKind.TOTAL, "非刷卡支出", summary.nonCardSpending)
        } else {
            rows += TableRow(TableRowKind.TOTAL, "刷卡（隔月繳）", summary.cardSpending)
            rows += TableRow(TableRowKind.TOTAL, "非刷卡（當月）", summary.nonCardSpending)
            if (summary.cardInterest.any { it != 0L }) {
                rows += TableRow(TableRowKind.TOTAL, "循環利息（估）", summary.cardInterest, auto = true)
            }
        }
        rows += TableRow(TableRowKind.TOTAL, "繳卡費＋貸款", summary.debtPayments)
        rows += TableRow(TableRowKind.CASH_FLOW, "月現金流", summary.monthlyCashFlow)

        val today = snapshot.today
        return PlanTable(year, view, rows, if (today.year == year) today.monthValue else null)
    }

    /**
     * 一個負債帳戶的每月繳款（R-PLS-11）。今天以前的月份**不再推估**（R-PLS-05：那些已經發生過了），
     * 所以那些格子顯示「已過」——不是 0，也不是「這個月不用繳」。實際繳了多少在「紀錄」裡看。
     *
     * 全表用同一組推估（R-PLS-06 的「同一組結果」），所以這裡不混入實際記帳：
     * 同一欄的收入與非刷卡支出都是計畫值，只有這一列改成實際，那一欄就會變成三種東西相加。
     */
    private fun debtRow(
        snapshot: FinanceSnapshot,
        year: Int,
        label: String,
        estimate: List<Money>,
        autoMonths: List<Int>,
    ): TableRow {
        val todayYm = YearMonth.from(snapshot.today)
        val past = mutableSetOf<Int>()
        val monthly = (1..12).map { m ->
            val value = estimate.getOrElse(m - 1) { 0L }
            // 沒有推估值、而且那個月已經過去了 → 「已過」
            if (value == 0L && m !in autoMonths && YearMonth.of(year, m).isBefore(todayYm)) past += m
            value
        }
        return TableRow(kind = TableRowKind.ITEM, label = label, monthly = monthly, auto = true, pastMonths = past)
    }
}
