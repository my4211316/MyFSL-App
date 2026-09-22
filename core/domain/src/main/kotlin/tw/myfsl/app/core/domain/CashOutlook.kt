package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.Period

/**
 * 未來現金水位（R-PLS-09）：照現在的計畫走下去，每個月底手上還有多少可動用的錢。
 *
 * 一期就是一個月（R-PER-01），所以一個月一個點，就是月底餘額；月內誰先誰後不猜。
 * 這是「這份計畫會怎樣」，所以放在計畫畫面；要比較「改了會怎樣」請到試算開情境（R-SCN）。
 */
data class CashOutlook(
    /** 每個月底的可動用餘額，第一個值是今天所在的月份。 */
    val monthlyLows: List<Money>,
    val monthLabels: List<String>,
    val safetyLevel: Money,
    val lowest: Money,
    val lowestLabel: String?,
    /** 首次低於安全線距今幾個月；不會低於時為 null。 */
    val monthsUntilBelowSafety: Int?,
    /** 總水位夠、但某個扣款帳戶會不夠扣時的提醒（R-FC-12）。 */
    val shortfall: String?,
) {
    val hasCurve: Boolean get() = monthlyLows.size > 1
}

object CashOutlookCalculator {

    fun build(snapshot: FinanceSnapshot): CashOutlook = from(CashFlowEngine.run(BaselineBuilder.build(snapshot)), snapshot)

    fun from(result: ForecastResult, snapshot: FinanceSnapshot): CashOutlook {
        val start = Period.of(snapshot.today)
        return CashOutlook(
            monthlyLows = ForecastSummary.monthlyLows(result),
            monthLabels = result.periods.map { ForecastSummary.shortLabel(it.period) },
            safetyLevel = snapshot.settings.safetyLevel,
            lowest = result.lowestLiquid,
            lowestLabel = result.lowest?.period?.let { ForecastSummary.shortLabel(it) },
            monthsUntilBelowSafety = result.firstBelowSafety?.let { ForecastSummary.monthsUntil(start, it.period) },
            shortfall = result.firstShortfall?.let { (p, id) ->
                "「${result.input.accountName(id)}」約 ${ForecastSummary.shortLabel(p.period)} 會不夠扣款，記得先從其他帳戶轉入"
            },
        )
    }
}
