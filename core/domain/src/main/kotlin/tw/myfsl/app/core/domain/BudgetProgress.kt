package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class PaceStatus(val label: String, val severity: Int) {
    OVER("已超支", 0),
    AHEAD("花太快", 1),
    ON_TRACK("正常", 2),
    NOT_STARTED("尚未開始", 3),
    UNPLANNED("未規劃", 4),
    DONE("已完成", 5),
}

/**
 * 百分比顯示一律以十進位四捨五入到整數（0.145 → 15，不受浮點誤差影響）；
 * 超前幅度 = 兩個顯示值相減，避免畫面數字對不上。
 */
fun displayPercent(ratio: Double): Int =
    BigDecimal.valueOf(ratio).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()

/**
 * 支出項目的本月進度（R-MIX-01：一個項目一列，不分支付方式）。
 * 任何有本月額度的支出項目都有進度條（R-BUD-10）：用了多少、還剩多少。
 * 「花太快」與「剩下每天可用」只算可調項目——固定項目本來就該一次付掉。
 */
data class LineProgress(
    val item: PlanItem,
    val groupName: String,
    val planned: Money,
    val actual: Money,
    /** 這一列在本月的時間進度 0–1：今天是這個月的第幾天 ÷ 當月天數（R-PER-01）。 */
    val timeRatio: Double,
    val status: PaceStatus,
    val remaining: Money,
    /** 剩下每天可用；已用完、已完成或時間已過時為 null。 */
    val dailyAllowance: Money?,
    val reported: Boolean,
    /** 這個月實際花費依支付方式分解（R-MIX-04），金額大的在前；沒花錢時是空的。 */
    val actualByMethod: List<Pair<PaymentMethod, Money>> = emptyList(),
) {
    val line: PlanLine get() = PlanLine(item.id)

    /** 可調項目才看「花太快」與「剩下每天可用」。 */
    val flexible: Boolean get() = item.flexibility == Flexibility.FLEXIBLE

    /** 還剩多少（顯示用，不會是負的）。 */
    val left: Money get() = remaining

    /** 「刷卡 $17,100 · 現金 $6,300」；這個月還沒花錢時為 null。 */
    val methodBreakdown: String?
        get() = actualByMethod.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { (method, amount) -> "${method.label} ${MoneyFormat.currency(amount)}" }
    val spentRatio: Double
        get() = when {
            planned > 0 -> actual.toDouble() / planned
            actual > 0 -> 1.0
            else -> 0.0
        }
    val spentPercent: Int get() = displayPercent(spentRatio)
    val timePercent: Int get() = displayPercent(timeRatio)

    /** 花費進度超前時間進度幾個百分點（正數 = 花太快）。 */
    val paceGapPercent: Int get() = spentPercent - timePercent
}

/** 項目合計。一個項目一列之後就是那一列本身，保留型別讓畫面不用改寫。 */
data class ItemBudget(
    val item: PlanItem,
    val lines: List<LineProgress>,
) {
    val planned: Money get() = lines.sumOf { it.planned }
    val actual: Money get() = lines.sumOf { it.actual }
    val remaining: Money get() = planned - actual
}

/** 可調支出的執行控管：花費進度 vs 時間進度。 */
object BudgetProgressCalculator {

    /** 超前超過這麼多百分點就提醒「花太快」。 */
    const val AHEAD_THRESHOLD_PERCENT = 10

    fun forMonth(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<LineProgress> {
        val year = date.year
        val month = date.monthValue
        val daysInMonth = date.lengthOfMonth()
        val day = date.dayOfMonth

        val timeRatio = timeRatio(day, daysInMonth)
        val daysLeft = daysLeft(day, daysInMonth)

        return snapshot.activeItems
            .filter { it.type == FlowType.EXPENSE }
            .mapNotNull { item ->
                val planned = snapshot.plannedAmount(item.id, year, month)
                val actual = ActualCalculator.actualFor(item.id, year, month, snapshot.actuals, snapshot.ledger)
                if (planned == 0L && actual.amount == 0L) return@mapNotNull null

                val remaining = (planned - actual.amount).coerceAtLeast(0)
                val draft = LineProgress(
                    item = item,
                    groupName = snapshot.group(item.groupId)?.name.orEmpty(),
                    planned = planned,
                    actual = actual.amount,
                    timeRatio = timeRatio,
                    status = PaceStatus.ON_TRACK,
                    remaining = remaining,
                    dailyAllowance = null,
                    reported = actual.reported,
                    actualByMethod = actualByMethod(snapshot, item.id, year, month),
                )
                val status = when {
                    actual.status == ActualStatus.DONE -> PaceStatus.DONE
                    planned == 0L -> PaceStatus.UNPLANNED
                    actual.amount > planned -> PaceStatus.OVER
                    planned in 1..actual.amount -> PaceStatus.DONE
                    actual.amount == 0L -> PaceStatus.NOT_STARTED
                    // 固定項目沒有「花太快」：到期一次付掉就結束，不看時間進度。
                    draft.flexible && draft.paceGapPercent > AHEAD_THRESHOLD_PERCENT -> PaceStatus.AHEAD
                    else -> PaceStatus.ON_TRACK
                }
                draft.copy(
                    status = status,
                    dailyAllowance = if (draft.flexible && remaining > 0 && daysLeft > 0 && status != PaceStatus.DONE && planned > 0) {
                        remaining / daysLeft
                    } else {
                        null
                    },
                )
            }
            .sortedWith(compareBy<LineProgress> { it.status.severity }.thenByDescending { it.paceGapPercent })
    }

    /** 這個月這個項目實際花了多少、怎麼付的（R-MIX-04）。 */
    fun actualByMethod(snapshot: FinanceSnapshot, itemId: Long, year: Int, month: Int): List<Pair<PaymentMethod, Money>> =
        snapshot.ledger
            .filter { it.itemId == itemId && it.method != null && it.countsForBudget && it.budgetMonth.year == year && it.budgetMonth.monthValue == month }
            .groupBy { it.method!! }
            .map { (method, entries) -> method to entries.sumOf { it.amount } }
            .filter { it.second != 0L }
            .sortedByDescending { it.second }

    fun byItem(lines: List<LineProgress>): List<ItemBudget> =
        lines.groupBy { it.item.id }.values.map { ItemBudget(it.first().item, it) }

    /** 本月的時間進度（R-PER-01）：今天是第幾天 ÷ 當月天數。 */
    fun timeRatio(day: Int, daysInMonth: Int): Double = (day.toDouble() / daysInMonth).coerceIn(0.0, 1.0)

    /** 含今天在內，這個月還剩幾天。 */
    fun daysLeft(day: Int, daysInMonth: Int): Int = (daysInMonth - day + 1).coerceAtLeast(0)
}
