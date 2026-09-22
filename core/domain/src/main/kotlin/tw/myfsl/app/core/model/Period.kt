package tw.myfsl.app.core.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * 現金流的計算單位：一個月（R-PER-01）。
 *
 * v3.6 之前是半月（一年 24 期）。半月要多一個「時點」欄位來決定金額落在上半月還是下半月，
 * 概念多、標籤難讀，而且最低水位得靠「同一半月支出都在收入之前」這種硬假設。
 * 改成月之後：沒填日期的項目就是「這個月的收支」，不用替使用者猜哪一天；
 * 有填日期的項目，日期仍然用在本月到期、提醒與卡片的結帳週期。
 */
data class Period(val year: Int, val month: Int) : Comparable<Period> {

    init {
        require(month in 1..12) { "month must be 1..12, was $month" }
    }

    /** 連續編號，方便排序、比較與序列化。 */
    val index: Int get() = year * 12 + (month - 1)

    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    val startDate: LocalDate get() = yearMonth.atDay(1)

    val endDate: LocalDate get() = yearMonth.atEndOfMonth()

    /** 例如「9月」。 */
    val label: String get() = "${month}月"

    /** 例如「2026年9月」。 */
    val fullLabel: String get() = "${year}年${month}月"

    fun plus(periods: Int): Period = fromIndex(index + periods)

    fun next(): Period = plus(1)

    operator fun contains(date: LocalDate): Boolean = date.year == year && date.monthValue == month

    override fun compareTo(other: Period): Int = index.compareTo(other.index)

    companion object {
        fun fromIndex(index: Int): Period {
            val year = Math.floorDiv(index, 12)
            return Period(year, Math.floorMod(index, 12) + 1)
        }

        fun of(date: LocalDate): Period = Period(date.year, date.monthValue)

        fun of(yearMonth: YearMonth): Period = Period(yearMonth.year, yearMonth.monthValue)

        fun range(start: Period, count: Int): List<Period> = List(count) { start.plus(it) }
    }
}
