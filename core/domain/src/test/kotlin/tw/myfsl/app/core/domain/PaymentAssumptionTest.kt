package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.FOOD_CASH
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 試算的付款假設（R-MIX-02）：預算不分支付方式，水位圖要知道錢哪一天離開帳戶，
 * 所以設定裡有一個「全部當現金付／依比例刷卡」的假設。
 */
class PaymentAssumptionTest {

    private val snapshot = SampleHousehold.snapshot()

    private fun withPercent(percent: Int) = snapshot.copy(settings = snapshot.settings.copy(forecastCardPercent = percent))

    @Test fun `預設是全部當現金付`() {
        val mix = PaymentAssumption.of(tw.myfsl.app.core.model.AppSettings())
        assertTrue(mix.allCash)
        assertEquals(0, mix.cardPercent)
        assertEquals("整筆當月從帳戶扣", 0L to 10_000L, mix.split(10_000))
        assertEquals(PaymentMethod.CASH, mix.rest)
    }

    @Test fun `依比例刷卡：餘數留給非刷卡那一份`() {
        val mix = PaymentAssumption.of(withPercent(40))
        assertFalse(mix.allCash)
        assertEquals(40, mix.cardPercent)
        assertEquals(4_000L to 6_000L, mix.split(10_000))
        assertEquals("1,001 × 40% = 400.4 → 400", 400L to 601L, mix.split(1_001))
        assertEquals(0L to 0L, mix.split(0))
    }

    @Test fun `超出範圍的比例會夾回 0 到 100`() {
        assertEquals(0, PaymentAssumption.of(withPercent(-10)).cardPercent)
        assertEquals(100, PaymentAssumption.of(withPercent(250)).cardPercent)
        assertEquals(10_000L to 0L, PaymentAssumption.of(withPercent(100)).split(10_000))
    }

    @Test fun `全部當現金付時，試算不會把計畫的支出算成刷卡`() {
        val allCash = withPercent(0)
        val october = BaselineBuilder.build(allCash).events
            .filter { it.period.year == 2026 && it.period.month == 10 && it.itemId != null }
        assertTrue("計畫的支出沒有一筆算成刷卡", october.none { it.method == PaymentMethod.CREDIT_CARD })
        // 生活費 16,000 ＋ 現金伙食 9,000 都當月從帳戶扣
        assertEquals(16_000L, october.filter { it.itemId == LIVING }.sumOf { it.amount })
        assertEquals(9_000L, october.filter { it.itemId == FOOD_CASH }.sumOf { it.amount })
    }

    @Test fun `依比例刷卡時，每一筆計畫支出都照比例拆成兩筆`() {
        val october = BaselineBuilder.build(withPercent(40)).events
            .filter { it.period.year == 2026 && it.period.month == 10 && it.itemId == LIVING }
        assertEquals("16,000 × 40%", 6_400L, october.filter { it.method == PaymentMethod.CREDIT_CARD }.sumOf { it.amount })
        assertEquals(9_600L, october.filter { it.method == PaymentMethod.CASH }.sumOf { it.amount })
    }

    @Test fun `付款假設不影響已經欠的卡債與每期卡費`() {
        val cardEvents = { percent: Int ->
            BaselineBuilder.build(withPercent(percent)).events
                .filter { it.source == EventSource.CARD_SCHEDULE }
                .sumOf { it.amount }
        }
        assertEquals("已經發生的卡費與利息照合約算，不受假設影響", cardEvents(0), cardEvents(40))
    }

    @Test fun `說明文字`() {
        assertEquals("全部當現金付", PaymentAssumption.label(snapshot.settings))
        assertEquals("刷卡 40%", PaymentAssumption.label(withPercent(40).settings))
        assertTrue(PaymentAssumption.hint(tw.myfsl.app.core.model.AppSettings()).contains("消費當月就從帳戶扣"))
        assertTrue(PaymentAssumption.hint(withPercent(40).settings).contains("40%"))
    }
}
