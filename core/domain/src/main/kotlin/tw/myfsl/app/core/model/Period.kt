package tw.myfsl.app.core.model

import java.time.LocalDate
import java.time.YearMonth

/** 上半月（1–15 日）或下半月（16 日–月底）。 */
enum class Half(val label: String, val shortLabel: String) {
    FIRST("上半月", "上"),
    SECOND("下半月", "下"),
}

/** 現金流的最小計算單位：某年某月的上半月或下半月，每年 24 期。 */
data class Period(val year: Int, val month: Int, val half: Half) : Comparable<Period> {

    init {
        require(month in 1..12) { "month must be 1..12, was $month" }
    }

    /** 連續編號，方便排序、比較與序列化。 */
    val index: Int get() = year * 24 + (month - 1) * 2 + half.ordinal

    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    val startDate: LocalDate
        get() = LocalDate.of(year, month, if (half == Half.FIRST) 1 else 16)

    val endDate: LocalDate
        get() = if (half == Half.FIRST) LocalDate.of(year, month, 15) else yearMonth.atEndOfMonth()

    /** 例如「9月上」。 */
    val label: String get() = "${month}月${half.shortLabel}"

    /** 例如「2026年9月上半月」。 */
    val fullLabel: String get() = "${year}年${month}月${half.label}"

    fun plus(periods: Int): Period = fromIndex(index + periods)

    fun next(): Period = plus(1)

    override fun compareTo(other: Period): Int = index.compareTo(other.index)

    companion object {
        fun fromIndex(index: Int): Period {
            val year = Math.floorDiv(index, 24)
            val rest = Math.floorMod(index, 24)
            return Period(year, rest / 2 + 1, Half.entries[rest % 2])
        }

        fun of(date: LocalDate): Period = Period(date.year, date.monthValue, halfOfDay(date.dayOfMonth))

        fun halfOfDay(day: Int): Half = if (day <= 15) Half.FIRST else Half.SECOND

        fun range(start: Period, count: Int): List<Period> = List(count) { start.plus(it) }
    }
}
