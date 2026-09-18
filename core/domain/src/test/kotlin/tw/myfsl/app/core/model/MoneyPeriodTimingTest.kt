package tw.myfsl.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MoneyPeriodTimingTest {

    @Test fun `金額格式：千分位、負號、正負號`() {
        assertEquals("1,234,567", MoneyFormat.plain(1_234_567))
        assertEquals("$4,500", MoneyFormat.currency(4_500))
        assertEquals("−$4,500", MoneyFormat.currency(-4_500))
        assertEquals("+$100", MoneyFormat.signed(100))
        assertEquals("$0", MoneyFormat.signed(0))
        assertEquals("−$9", MoneyFormat.signed(-9))
    }

    @Test fun `金額精簡格式：一萬以下原樣，以上換成萬並去掉點零`() {
        assertEquals("9,999", MoneyFormat.compact(9_999))
        assertEquals("12.3萬", MoneyFormat.compact(123_456))
        assertEquals("12萬", MoneyFormat.compact(120_000))
        assertEquals("−4.9萬", MoneyFormat.compact(-48_639))
        assertEquals("150萬", MoneyFormat.compact(1_500_000))
    }

    @Test fun `金額輸入解析`() {
        assertEquals(12_000L, MoneyFormat.parse("12,000"))
        assertEquals(500L, MoneyFormat.parse(" 5 00 "))
        assertNull(MoneyFormat.parse(""))
        assertNull(MoneyFormat.parse("abc"))
    }

    @Test fun `日期換算半月：15 日含在上半月`() {
        assertEquals(Period(2026, 9, Half.FIRST), Period.of(LocalDate.of(2026, 9, 1)))
        assertEquals(Period(2026, 9, Half.FIRST), Period.of(LocalDate.of(2026, 9, 15)))
        assertEquals(Period(2026, 9, Half.SECOND), Period.of(LocalDate.of(2026, 9, 16)))
        assertEquals(Period(2026, 9, Half.SECOND), Period.of(LocalDate.of(2026, 9, 30)))
    }

    @Test fun `期別加減與跨年`() {
        val sep = Period(2026, 9, Half.FIRST)
        assertEquals(Period(2026, 9, Half.SECOND), sep.plus(1))
        assertEquals(Period(2026, 10, Half.FIRST), sep.plus(2))
        assertEquals(Period(2027, 1, Half.FIRST), Period(2026, 12, Half.SECOND).next())
        assertEquals(Period(2026, 12, Half.SECOND), Period(2027, 1, Half.FIRST).plus(-1))
        listOf(sep, Period(2028, 2, Half.SECOND), Period(2030, 12, Half.SECOND)).forEach {
            assertEquals(it, Period.fromIndex(it.index))
        }
        assertEquals(48, Period.range(sep, 48).size)
        assertEquals(Period(2028, 8, Half.SECOND), Period.range(sep, 48).last())
    }

    @Test fun `期別標籤與起訖日（含閏年二月）`() {
        assertEquals("9月上", Period(2026, 9, Half.FIRST).label)
        assertEquals("2026年9月上半月", Period(2026, 9, Half.FIRST).fullLabel)
        assertEquals(LocalDate.of(2028, 2, 16), Period(2028, 2, Half.SECOND).startDate)
        assertEquals(LocalDate.of(2028, 2, 29), Period(2028, 2, Half.SECOND).endDate)
        assertEquals(LocalDate.of(2026, 9, 15), Period(2026, 9, Half.FIRST).endDate)
    }

    @Test fun `時點拆分：平分時奇數的一元在下半月`() {
        assertEquals(4_500L to 4_501L, Timing.SPLIT.split(9_001))
        assertEquals(9_000L to 0L, Timing.FIRST_HALF.split(9_000))
        assertEquals(0L to 9_000L, Timing.SECOND_HALF.split(9_000))
        assertEquals(4_501L, Timing.SPLIT.amountIn(Half.SECOND, 9_001))
    }
}
