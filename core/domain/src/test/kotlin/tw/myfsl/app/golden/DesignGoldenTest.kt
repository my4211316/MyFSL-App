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
 * 數字由 App 引擎算出、前幾期逐筆手算核對過（見 AutoPostingTest、BaselineBuilderTest），
 * 再由 DesignNumbersDump 寫進 design/mockups/sample-numbers.json；引擎邏輯改變，這裡就會失敗。
 */
class DesignGoldenTest {

    private val base = BaselineBuilder.build(SampleHousehold.snapshot())
    private val oct = Period(2026, 10, Half.FIRST)

    private fun thousandsSeries(result: ForecastResult) = ForecastSummary.monthlyLows(result).map(ForecastSummary::thousands)

    @Test fun `現況：最低水位、首次低於安全線、結構缺口、期末負債、曲線`() {
        // 付款假設是預設的「全部當現金付」（R-MIX-02）：不靠刷卡遞延，看得出養不養得起。
        val result = ScenarioApplier.run(base, emptyList())
        assertEquals(-127_561L, result.lowestLiquid)
        assertEquals(Period(2028, 2, Half.FIRST), result.lowest?.period)
        assertEquals(Period(2027, 1, Half.FIRST), result.firstBelowSafety?.period)
        assertEquals(4, ForecastSummary.monthsUntil(base.start, result.firstBelowSafety!!.period))
        assertEquals(-60_880L, result.structuralGapPerYear)
        assertEquals("不再新增刷卡，A 卡每月繳 18,000、B 卡每月 9,000，兩張都清光", 0L, result.endCardDebt)
        assertEquals("只剩信貸與保單借款", 1_122_396L, result.endTotalDebt)
        assertEquals("卡債很快清掉，只有前兩期有沒繳清的帳單", 792L, result.totalCardInterest)
        assertEquals(
            listOf(111L, 94, 71, 56, -14, -53, 101, 104, 90, 65, -5, -18, -43, -41, -37, -32, -97, -128, 27, 30, 16, -9, -79, -92),
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
        assertEquals("10 月上半月先計 A 卡利息 403 再清償（R-ORD-01）", 14_133L, result.lowestLiquid)
        assertEquals(Period(2028, 2, Half.FIRST), result.lowest?.period)
        assertEquals(Period(2028, 2, Half.FIRST), result.firstBelowSafety?.period)
        assertEquals(-103_728L, result.structuralGapPerYear)
        assertEquals(0L, result.endCardDebt)
        assertEquals("只有 10/1 結帳一次：(50,200 − 18,000) × 1.25% = 402.5 → 403", 403L, result.totalCardInterest)
        assertEquals(1_256_468L, result.endTotalDebt)
        assertEquals(
            listOf(111L, 234, 238, 238, 169, 135, 286, 285, 263, 234, 164, 144, 118, 117, 117, 117, 48, 14, 165, 164, 142, 113, 43, 23),
            thousandsSeries(result),
        )
    }

    @Test fun `情境：可調支出減少 20%`() {
        val flexible = listOf(LIVING, SampleHousehold.FOOD_CASH, HOUSEHOLD, FUEL, LESSONS, CONTEST, RED_ENVELOPE, BIRTHDAY, TRIP)
        val result = ScenarioApplier.run(base, listOf(ScenarioChange.AdjustItems(flexible, -20.0, base.start.index)))
        assertEquals(-8_187L, result.lowestLiquid)
        assertEquals(Period(2027, 2, Half.FIRST), result.lowest?.period)
        assertEquals(Period(2027, 1, Half.FIRST), result.firstBelowSafety?.period)
        assertEquals("減 20% 之後結構由負轉正，但已經欠的還是要還", 45_876L, result.structuralGapPerYear)
        assertEquals(0L, result.endCardDebt)
        assertEquals(1_122_396L, result.endTotalDebt)
        assertEquals(792L, result.totalCardInterest)
        assertEquals(
            listOf(112L, 101, 86, 82, 16, -8, 155, 165, 162, 141, 79, 87, 66, 75, 87, 100, 42, 27, 190, 200, 197, 176, 114, 122),
            thousandsSeries(result),
        )
    }
}
