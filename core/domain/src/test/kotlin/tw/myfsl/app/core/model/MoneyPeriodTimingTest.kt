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

    @Test fun `日期換算期別：一期就是一個月`() {
        assertEquals(Period(2026, 9), Period.of(LocalDate.of(2026, 9, 1)))
        assertEquals(Period(2026, 9), Period.of(LocalDate.of(2026, 9, 15)))
        assertEquals(Period(2026, 9), Period.of(LocalDate.of(2026, 9, 16)))
        assertEquals(Period(2026, 9), Period.of(LocalDate.of(2026, 9, 30)))
        assertEquals(Period(2026, 10), Period.of(LocalDate.of(2026, 10, 1)))
    }

    @Test fun `期別加減與跨年`() {
        val sep = Period(2026, 9)
        assertEquals(Period(2026, 10), sep.plus(1))
        assertEquals(Period(2026, 11), sep.plus(2))
        assertEquals(Period(2027, 1), Period(2026, 12).next())
        assertEquals(Period(2026, 12), Period(2027, 1).plus(-1))
        listOf(sep, Period(2028, 2), Period(2030, 12)).forEach {
            assertEquals(it, Period.fromIndex(it.index))
        }
        assertEquals(24, Period.range(sep, 24).size)
        assertEquals(Period(2028, 8), Period.range(sep, 24).last())
    }

    @Test fun `期別標籤與起訖日（含閏年二月）`() {
        assertEquals("9月", Period(2026, 9).label)
        assertEquals("2026年9月", Period(2026, 9).fullLabel)
        assertEquals(LocalDate.of(2028, 2, 1), Period(2028, 2).startDate)
        assertEquals(LocalDate.of(2028, 2, 29), Period(2028, 2).endDate)
        assertEquals(LocalDate.of(2026, 9, 30), Period(2026, 9).endDate)
    }

    @Test fun `付款日：有填才有到期日，短月份取月底`() {
        val undated = PlanItem(1, "生活費", 5, FlowType.EXPENSE)
        assertNull(undated.dueDateIn(2026, 9))
        val on5th = undated.copy(dueDay = 5)
        assertEquals(LocalDate.of(2026, 9, 5), on5th.dueDateIn(2026, 9))
        val on31st = undated.copy(dueDay = 31)
        assertEquals(LocalDate.of(2026, 2, 28), on31st.dueDateIn(2026, 2))
        assertEquals(LocalDate.of(2028, 2, 29), on31st.dueDateIn(2028, 2))
    }
}
