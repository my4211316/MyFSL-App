package tw.myfsl.app.golden

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.BIRTHDAY
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.CONTEST
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.RED_ENVELOPE
import tw.myfsl.app.core.sample.SampleHousehold.TRIP
import tw.myfsl.app.core.domain.BaselineBuilder
import tw.myfsl.app.core.domain.ForecastResult
import tw.myfsl.app.core.domain.ForecastSummary
import tw.myfsl.app.core.domain.ScenarioApplier
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 黃金測試：App 的計算引擎要算出和設計稿（design/mockups）完全相同的數字。
 * 設計稿數字由 sample-model.mjs 產生；兩邊任何一邊的邏輯改變，這裡就會失敗。
 */
class DesignGoldenTest {

    private val base = BaselineBuilder.build(SampleHousehold.snapshot())
    private val oct = Period(2026, 10, Half.FIRST)

    private fun thousandsSeries(result: ForecastResult) = ForecastSummary.monthlyLows(result).map(ForecastSummary::thousands)

    @Test fun `現況：最低水位、首次低於安全線、結構缺口、期末負債、曲線`() {
        val result = ScenarioApplier.run(base, emptyList())
        assertEquals(-48_639L, result.lowestLiquid)
        assertEquals(Period(2028, 2, Half.FIRST), result.lowest?.period)
        assertEquals(Period(2027, 2, Half.FIRST), result.firstBelowSafety?.period)
        assertEquals(5, ForecastSummary.monthsUntil(base.start, result.firstBelowSafety!!.period))
        assertEquals(-80_356L, result.structuralGapPerYear)
        assertEquals(152_205L, result.endCardDebt)
        assertEquals(1_274_601L, result.endTotalDebt)
        assertEquals("兩年循環利息", 29_345L, result.totalCardInterest)
        assertEquals(
            listOf(106L, 118, 116, 116, 46, 10, 160, 170, 152, 121, 46, 45, 50, 59, 57, 57, -13, -49, 101, 111, 93, 62, -13, -14),
            thousandsSeries(result),
        )
        assertEquals("−1.4萬", ForecastSummary.wanLabel(-14))
        assertEquals("5萬", ForecastSummary.wanLabel(50))
    }

    @Test fun `情境：整合卡債（新貸款、清償兩張卡、刷卡改現金）`() {
        val result = ScenarioApplier.run(
            base,
            listOf(
                ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK, Half.SECOND),
                ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index),
                ScenarioChange.ChangeMethod(listOf(LIVING, FUEL, PHONE, CAR_SERVICE, TRIP), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, oct.index),
            ),
        )
        assertEquals(2_880L, result.lowestLiquid)
        assertEquals(Period(2028, 2, Half.FIRST), result.lowest?.period)
        assertEquals(Period(2028, 2, Half.FIRST), result.firstBelowSafety?.period)
        assertEquals(-109_355L, result.structuralGapPerYear)
        assertEquals(0L, result.endCardDebt)
        assertEquals("清掉卡債後只剩清償前的利息", 1_256L, result.totalCardInterest)
        assertEquals(1_256_468L, result.endTotalDebt)
        assertEquals(
            listOf(106L, 222, 226, 227, 158, 124, 275, 274, 252, 223, 153, 132, 107, 105, 105, 106, 37, 3, 154, 153, 131, 102, 32, 11),
            thousandsSeries(result),
        )
    }

    @Test fun `情境：可調支出減少 20%`() {
        val flexible = listOf(LIVING, HOUSEHOLD, FUEL, LESSONS, CONTEST, RED_ENVELOPE, BIRTHDAY, TRIP)
        val result = ScenarioApplier.run(base, listOf(ScenarioChange.AdjustItems(flexible, -20.0, base.start.index)))
        assertEquals(30_621L, result.lowestLiquid)
        assertEquals(Period(2028, 2, Half.FIRST), result.lowest?.period)
        assertNull(result.firstBelowSafety)
        assertEquals(34_416L, result.structuralGapPerYear)
        assertEquals(25_320L, result.endCardDebt)
        assertEquals(1_147_716L, result.endTotalDebt)
        assertEquals(14_512L, result.totalCardInterest)
        assertEquals(
            listOf(108L, 122, 125, 129, 62, 38, 192, 205, 192, 163, 92, 95, 103, 115, 118, 122, 55, 31, 185, 197, 185, 156, 85, 87),
            thousandsSeries(result),
        )
    }
}
