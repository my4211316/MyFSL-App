package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.ScenarioChange
import kotlin.math.ceil

sealed interface GoalTarget {
    /** 整個試算期間的最低現金水位不低於 [amount]。 */
    data class MinLiquid(val amount: Money) : GoalTarget

    /** 每年結構缺口不小於 0（收支打平）。 */
    data object NoStructuralGap : GoalTarget
}

data class GoalSeekResult(
    /** 需要減少的百分比（0–100）。 */
    val cutPercent: Double,
    val achievable: Boolean,
    /** 每個項目每年需要減少的金額。 */
    val cutsPerYear: Map<Long, Money>,
    /** 用進位後的比例重跑的結果；[achievable] 依這個結果判斷。 */
    val result: ForecastResult,
    /** 最低點發生在開始減少之前，怎麼減都來不及（R-GS-04）。 */
    val lowBeforeStart: Boolean = false,
    /** 選的項目在期間內沒有可以減的金額（R-GS-04）。 */
    val nothingToCut: Boolean = false,
)

/** 反推「選定項目要減少多少百分比才能達成目標」。 */
object GoalSeeker {

    fun seek(
        input: ForecastInput,
        itemIds: Set<Long>,
        target: GoalTarget,
        fromIndex: Int = input.start.index,
    ): GoalSeekResult {
        fun run(percent: Double) = ScenarioApplier.run(
            input,
            listOf(ScenarioChange.AdjustItems(itemIds.toList(), -percent, fromIndex)),
        )

        fun meets(result: ForecastResult) = when (target) {
            is GoalTarget.MinLiquid -> result.lowestLiquid >= target.amount
            GoalTarget.NoStructuralGap -> result.structuralGapPerYear >= 0
        }

        fun resultFor(percent: Double): GoalSeekResult {
            val result = run(percent)
            return GoalSeekResult(percent, meets(result), cutsPerYear(input, itemIds, percent, fromIndex), result)
        }

        val base = run(0.0)
        if (meets(base)) return GoalSeekResult(0.0, true, emptyMap(), base)
        val cuttable = input.events.any { it.source == EventSource.PLAN && it.itemId in itemIds && it.period.index >= fromIndex && it.amount > 0 }
        if (itemIds.isEmpty() || !cuttable) return GoalSeekResult(0.0, false, emptyMap(), base, nothingToCut = true)
        val all = run(100.0)
        if (!meets(all)) {
            val early = target is GoalTarget.MinLiquid &&
                all.periods.filter { it.period.index < fromIndex }.any { it.liquidLow < target.amount }
            return resultFor(100.0).copy(lowBeforeStart = early)
        }

        var low = 0.0
        var high = 100.0
        repeat(40) {
            val mid = (low + high) / 2
            if (meets(run(mid))) high = mid else low = mid
        }
        // 無條件進位到 0.1%；先扣掉極小誤差，避免 20.0000001 被進位成 20.1。
        val rounded = (ceil(high * 10 - 1e-6) / 10).coerceAtMost(100.0)
        return resultFor(rounded)
    }

    private fun cutsPerYear(input: ForecastInput, itemIds: Set<Long>, percent: Double, fromIndex: Int): Map<Long, Money> {
        if (input.periodCount == 0) return emptyMap()
        return input.events
            .filter { it.source == EventSource.PLAN && it.itemId in itemIds && it.period.index >= fromIndex }
            .groupBy { it.itemId!! }
            .mapValues { (_, events) ->
                Math.round(events.sumOf { it.amount } * percent / 100.0 * 24 / input.periodCount)
            }
    }
}
