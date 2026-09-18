package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.Timing
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

/** 可調支出某一列（項目 × 支付方式）的本月進度。 */
data class LineProgress(
    val item: PlanItem,
    val method: PaymentMethod,
    val groupName: String,
    val planned: Money,
    val actual: Money,
    /** 這一列在本月的時間進度 0–1。 */
    val timeRatio: Double,
    val status: PaceStatus,
    val remaining: Money,
    /** 剩下每天可用；已用完、已完成或時間已過時為 null。 */
    val dailyAllowance: Money?,
    val reported: Boolean,
) {
    val line: PlanLine get() = PlanLine(item.id, method)
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

/** 項目合計：沒有規劃的支付方式花費也算進項目總額。 */
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

        return snapshot.activeItems
            .filter { it.type == FlowType.EXPENSE && it.flexibility == Flexibility.FLEXIBLE }
            .flatMap { item ->
                val methods = (snapshot.linesOf(item.id).mapNotNull { it.method } +
                    snapshot.ledger.filter { it.itemId == item.id && it.date.year == year && it.date.monthValue == month }.mapNotNull { it.method } +
                    snapshot.actuals.filter { it.itemId == item.id && it.year == year && it.month == month }.mapNotNull { it.method })
                    .distinct()
                    .sortedBy { it.ordinal }

                methods.mapNotNull { method ->
                    val line = PlanLine(item.id, method)
                    val planned = snapshot.planAmount(line, year, month)
                    val actual = ActualCalculator.actualFor(line, year, month, snapshot.actuals, snapshot.ledger)
                    if (planned == 0L && actual.amount == 0L) return@mapNotNull null

                    val timeRatio = timeRatio(item.timing, day, daysInMonth)
                    val remaining = (planned - actual.amount).coerceAtLeast(0)
                    val draft = LineProgress(
                        item = item,
                        method = method,
                        groupName = snapshot.group(item.groupId)?.name.orEmpty(),
                        planned = planned,
                        actual = actual.amount,
                        timeRatio = timeRatio,
                        status = PaceStatus.ON_TRACK,
                        remaining = remaining,
                        dailyAllowance = null,
                        reported = actual.reported,
                    )
                    val status = when {
                        actual.status == ActualStatus.DONE -> PaceStatus.DONE
                        planned == 0L -> PaceStatus.UNPLANNED
                        actual.amount > planned -> PaceStatus.OVER
                        draft.timePercent == 0 && actual.amount == 0L -> PaceStatus.NOT_STARTED
                        draft.paceGapPercent > AHEAD_THRESHOLD_PERCENT -> PaceStatus.AHEAD
                        else -> PaceStatus.ON_TRACK
                    }
                    val daysLeft = daysLeft(item.timing, day, daysInMonth)
                    draft.copy(
                        status = status,
                        dailyAllowance = if (remaining > 0 && daysLeft > 0 && status != PaceStatus.DONE && planned > 0) remaining / daysLeft else null,
                    )
                }
            }
            .sortedWith(compareBy<LineProgress> { it.status.severity }.thenByDescending { it.paceGapPercent })
    }

    fun byItem(lines: List<LineProgress>): List<ItemBudget> =
        lines.groupBy { it.item.id }.values.map { ItemBudget(it.first().item, it.sortedBy { line -> line.method.ordinal }) }

    fun timeRatio(timing: Timing, day: Int, daysInMonth: Int): Double = when (timing) {
        Timing.SPLIT -> day.toDouble() / daysInMonth
        Timing.FIRST_HALF -> (day / 15.0).coerceAtMost(1.0)
        Timing.SECOND_HALF -> ((day - 15).coerceAtLeast(0).toDouble() / (daysInMonth - 15)).coerceAtMost(1.0)
    }

    /** 含今天在內剩下的天數。 */
    fun daysLeft(timing: Timing, day: Int, daysInMonth: Int): Int = when (timing) {
        Timing.SPLIT -> daysInMonth - day + 1
        Timing.FIRST_HALF -> (15 - day + 1).coerceAtLeast(0)
        Timing.SECOND_HALF -> daysInMonth - maxOf(day, 16) + 1
    }
}
