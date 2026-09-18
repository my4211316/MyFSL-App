package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.Period
import java.util.Locale
import kotlin.math.abs

/** 試算結果的顯示規則。 */
object ForecastSummary {

    /** 每月最低水位：同一個月兩個半月的保守最低點取較小者，依月份排列。 */
    fun monthlyLows(result: ForecastResult): List<Money> =
        result.periods.groupBy { it.period.yearMonth }.values.map { halves -> halves.minOf { it.liquidLow } }

    /** 從 [from] 到 [to] 相隔幾個月（只看年月）。 */
    fun monthsUntil(from: Period, to: Period): Int = (to.year - from.year) * 12 + (to.month - from.month)

    /** 圖表用：換算成千元並四捨五入。 */
    fun thousands(value: Money): Long = Math.round(value / 1000.0)

    /** 圖表標籤：千元數字轉成「萬」，保留一位小數並去掉 .0，例如 −49 → −4.9萬、130 → 13萬。 */
    fun wanLabel(thousands: Long): String {
        val sign = if (thousands < 0) "−" else ""
        val text = String.format(Locale.US, "%.1f", abs(thousands) / 10.0).removeSuffix(".0")
        return "$sign${text}萬"
    }

    /** 期別的精簡標籤，例如 2027/2。 */
    fun shortLabel(period: Period): String = "${period.year}/${period.month}"
}
