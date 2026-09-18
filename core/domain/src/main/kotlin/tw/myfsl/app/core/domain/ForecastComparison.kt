package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.Scenario

/** 比較表的一欄：現況或某個情境。 */
data class ScenarioOutcome(
    /** 現況為 null。 */
    val scenario: Scenario?,
    val name: String,
    val monthlyLows: List<Money>,
    val lowest: Money,
    val lowestLabel: String?,
    /** 首次低於安全線，例如 2027/2；不會低於為 null。 */
    val firstBelowSafetyLabel: String?,
    val structuralGapPerYear: Money,
    val endCardDebt: Money,
    val endTotalDebt: Money,
    val cardInterest: Money,
)

data class Comparison(
    val months: Int,
    val labels: List<String>,
    val safetyLevel: Money,
    val outcomes: List<ScenarioOutcome>,
) {
    /** 各指標的最佳值（最低水位與缺口越大越好，負債與利息越小越好）。 */
    val bestLowest: Money? get() = outcomes.maxOfOrNull { it.lowest }
    val bestGap: Money? get() = outcomes.maxOfOrNull { it.structuralGapPerYear }
    val bestCardDebt: Money? get() = outcomes.minOfOrNull { it.endCardDebt }
    val bestTotalDebt: Money? get() = outcomes.minOfOrNull { it.endTotalDebt }
    val bestInterest: Money? get() = outcomes.minOfOrNull { it.cardInterest }
}

object ForecastComparisonCalculator {

    fun compare(snapshot: FinanceSnapshot, scenarios: List<Scenario>, months: Int): Comparison {
        val base = BaselineBuilder.build(snapshot, months * 2)
        val baseResult = CashFlowEngine.run(base)
        val labels = baseResult.periods.map { it.period }.distinctBy { it.yearMonth }.map { ForecastSummary.shortLabel(it) }
        val outcomes = listOf(outcome(null, "現況", baseResult)) +
            scenarios.map { outcome(it, it.name, ScenarioApplier.run(base, it.changes)) }
        return Comparison(months, labels, snapshot.settings.safetyLevel, outcomes)
    }

    private fun outcome(scenario: Scenario?, name: String, result: ForecastResult) = ScenarioOutcome(
        scenario = scenario,
        name = name,
        monthlyLows = ForecastSummary.monthlyLows(result),
        lowest = result.lowestLiquid,
        lowestLabel = result.lowest?.period?.let(ForecastSummary::shortLabel),
        firstBelowSafetyLabel = result.firstBelowSafety?.period?.let(ForecastSummary::shortLabel),
        structuralGapPerYear = result.structuralGapPerYear,
        endCardDebt = result.endCardDebt,
        endTotalDebt = result.endTotalDebt,
        cardInterest = result.totalCardInterest,
    )

    /** 反推：選定項目要減多少才能達成目標。 */
    fun seek(snapshot: FinanceSnapshot, months: Int, itemIds: Set<Long>, target: GoalTarget, fromMonthOffset: Int = 1): GoalSeekResult {
        val base = BaselineBuilder.build(snapshot, months * 2)
        val from = maxOf(ScenarioForm.indexFor(snapshot.today, fromMonthOffset), Period.of(snapshot.today).index)
        return GoalSeeker.seek(base, itemIds, target, from)
    }
}
