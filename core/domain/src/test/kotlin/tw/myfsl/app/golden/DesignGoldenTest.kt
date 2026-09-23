package tw.myfsl.app.golden

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.BIRTHDAY
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.CONTEST
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.RED_ENVELOPE
import tw.myfsl.app.core.sample.SampleHousehold.TRIP
import tw.myfsl.app.core.domain.BaselineBuilder
import tw.myfsl.app.core.domain.ForecastResult
import tw.myfsl.app.core.domain.ForecastSummary
import tw.myfsl.app.core.domain.ScenarioApplier
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
    private val oct = Period(2026, 10)

    private fun thousandsSeries(result: ForecastResult) = ForecastSummary.monthlyLows(result).map(ForecastSummary::thousands)

    @Test fun `現況：最低水位、首次低於安全線、結構缺口、期末負債、曲線`() {
        val result = ScenarioApplier.run(base, emptyList())
        // 一期就是一個月（R-PER-01）：水位曲線一個月一個點，就是月底餘額。
        // 9 月手算：135,000 ＋ 70,000（薪資 65,000 ＋ 教育補助 5,000）
        //   − 18,000（繳 A 卡）− 9,000（繳 B 卡）− 14,507（信貸）
        //   − 6,800（生活費現金列剩 4,600、家用 700、電費 1,500）= 156,693 → 157
        // 刷卡列（生活費 6,200、油資 1,200、汽車保養 12,000）這個月不動到現金，要等繳卡費才付（R-MIX-01）。
        assertEquals(89_465L, result.lowestLiquid)
        assertEquals(Period(2027, 1), result.lowest?.period)
        assertNull("兩年內都沒有低於安全線——但卡債一路長大，看 endCardDebt", result.firstBelowSafety)
        assertEquals(-79_936L, result.structuralGapPerYear)
        // 卡債＝帳單該繳沒繳掉的部分；未繳卡款還含最後一期刷的（2028/8 有家族旅遊，21,900 ＋ 52,180 = 74,080，R-CARD-28）
        assertEquals("每月刷 21,900、只繳 18,000，兩年後卡債從 105,000 長到 256,285", 256_285L, result.endCardDebt)
        assertEquals(330_365L, result.endCardUnpaid)
        assertEquals(74_080L, result.endCardNotDue)
        assertEquals("負債合計用未繳卡款", 1_452_761L, result.endTotalDebt)
        assertEquals("每期都沒繳清，兩年的循環利息", 38_905L, result.totalCardInterest)
        assertEquals(
            listOf(157L, 155, 155, 130, 89, 208, 227, 228, 196, 175, 138, 152, 170, 177, 186, 170, 138, 257, 276, 276, 245, 224, 187, 200),
            thousandsSeries(result),
        )
        assertEquals("−1.4萬", ForecastSummary.wanLabel(-14))
        assertEquals("5萬", ForecastSummary.wanLabel(50))
    }

    @Test fun `情境：整合卡債（新貸款、清償兩張卡、刷卡改現金）`() {
        val result = ScenarioApplier.run(
            base,
            listOf(
                ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK),
                ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index),
                ScenarioChange.ChangeMethod(listOf(LIVING, FUEL, PHONE, CAR_SERVICE, TRIP), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, oct.index),
            ),
        )
        assertEquals("10 月先計 A 卡利息 403 再清償（R-ORD-01）", 22_543L, result.lowestLiquid)
        assertEquals(Period(2028, 8), result.lowest?.period)
        assertEquals(Period(2028, 8), result.firstBelowSafety?.period)
        assertEquals(-103_728L, result.structuralGapPerYear)
        assertEquals(0L, result.endCardDebt)
        assertEquals("只有 10/1 結帳一次：(50,200 − 18,000) × 1.25% = 402.5 → 403", 403L, result.totalCardInterest)
        assertEquals(1_256_468L, result.endTotalDebt)
        assertEquals(
            listOf(157L, 263, 264, 240, 200, 311, 310, 303, 263, 234, 190, 144, 142, 142, 143, 119, 79, 190, 189, 182, 142, 113, 69, 23),
            thousandsSeries(result),
        )
    }

    @Test fun `情境：可調支出減少 20%`() {
        val flexible = listOf(LIVING, HOUSEHOLD, FUEL, LESSONS, CONTEST, RED_ENVELOPE, BIRTHDAY, TRIP)
        val result = ScenarioApplier.run(base, listOf(ScenarioChange.AdjustItems(flexible, -20.0, base.start.index)))
        assertEquals(105_525L, result.lowestLiquid)
        assertEquals(Period(2027, 1), result.lowest?.period)
        assertNull("減 20% 之後不會低於安全線", result.firstBelowSafety)
        assertEquals("減 20% 之後結構由負轉正，但已經欠的還是要還", 33_945L, result.structuralGapPerYear)
        // 可調項目減 20%（手機網路不是可調，沒減）：2028/8 刷卡 (16,000+3,500)×0.8 ＋ 2,400 ＋ 52,180×0.8 = 59,744
        assertEquals("刷卡列也減了，卡債長得比較慢，但還是在長", 144_319L, result.endCardDebt)
        assertEquals(204_063L, result.endCardUnpaid)
        assertEquals(59_744L, result.endCardNotDue)
        assertEquals(1_326_459L, result.endTotalDebt)
        assertEquals(24_655L, result.totalCardInterest)
        assertEquals(
            listOf(158L, 161, 165, 143, 106, 237, 258, 263, 235, 218, 184, 201, 222, 235, 247, 234, 206, 337, 359, 364, 336, 319, 285, 302),
            thousandsSeries(result),
        )
    }
}
