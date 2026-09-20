package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 支付結構（R-MIX-02／R-MIX-03）：預算只有一個金額，試算靠這裡決定哪些錢會變卡債。 */
class MethodMixTest {

    private val snapshot = SampleHousehold.snapshot()
    private val living = snapshot.item(LIVING)!!

    /** 6 月到 8 月的實際記帳；7 月以前不算在取樣範圍（本月之前 3 個完整月＝6、7、8 月）。 */
    private fun spend(month: Int, day: Int, amount: Long, method: PaymentMethod, id: Long) = LedgerEntry(
        id = id,
        date = LocalDate.of(2026, month, day),
        type = FlowType.EXPENSE,
        amount = amount,
        itemId = LIVING,
        method = method,
        accountId = if (method == PaymentMethod.CREDIT_CARD) CARD_A else CASH,
    )

    @Test fun `沒有設定支付方式時當成現金`() {
        val mix = MethodMixRules.fromItem(living.copy(method = null))
        assertEquals(PaymentMethod.CASH, mix.rest)
        assertEquals(0.0, mix.cardRatio, 0.0)
    }

    @Test fun `照項目設定：刷卡 100%，其餘 0%`() {
        val card = MethodMixRules.of(snapshot, living)
        assertFalse("實際紀錄不足，照項目設定", card.fromActual)
        assertEquals(1.0, card.cardRatio, 0.0)
        assertEquals(PaymentMethod.CREDIT_CARD, card.singleMethod)
        assertEquals("整筆都算刷卡", 10_000L to 0L, card.split(10_000))

        val cash = MethodMixRules.of(snapshot, living.copy(method = PaymentMethod.CASH))
        assertEquals(0.0, cash.cardRatio, 0.0)
        assertEquals(PaymentMethod.CASH, cash.singleMethod)
        assertEquals(0L to 10_000L, cash.split(10_000))
    }

    @Test fun `筆數夠時改用實際刷卡比例，並記下取樣內容`() {
        val sample = listOf(
            spend(6, 5, 3_000, PaymentMethod.CREDIT_CARD, 201),
            spend(6, 20, 1_000, PaymentMethod.CASH, 202),
            spend(7, 5, 3_000, PaymentMethod.CREDIT_CARD, 203),
            spend(7, 20, 1_000, PaymentMethod.CASH, 204),
            spend(8, 5, 2_000, PaymentMethod.CREDIT_CARD, 205),
            spend(8, 20, 2_000, PaymentMethod.CASH, 206),
        )
        val mix = MethodMixRules.of(snapshot.copy(ledger = sample), living)
        assertTrue(mix.fromActual)
        assertEquals("刷卡 8,000 ÷ 全部 12,000", 8_000.0 / 12_000, mix.cardRatio, 1e-9)
        assertEquals(67, mix.cardPercent)
        assertEquals(6, mix.sampleCount)
        assertEquals(8_000L, mix.sampleCard)
        assertEquals(12_000L, mix.sampleTotal)
        assertEquals(PaymentMethod.CASH, mix.rest)
        assertNull("混合時沒有單一方式", mix.singleMethod)
        assertEquals("10,000 × 2/3，餘數留給非刷卡", 6_667L to 3_333L, mix.split(10_000))
    }

    @Test fun `少一筆就不採用；項目關掉時也不採用`() {
        val five = listOf(
            spend(6, 5, 3_000, PaymentMethod.CREDIT_CARD, 201),
            spend(6, 20, 1_000, PaymentMethod.CASH, 202),
            spend(7, 5, 3_000, PaymentMethod.CREDIT_CARD, 203),
            spend(7, 20, 1_000, PaymentMethod.CASH, 204),
            spend(8, 5, 2_000, PaymentMethod.CREDIT_CARD, 205),
        )
        assertFalse(MethodMixRules.of(snapshot.copy(ledger = five), living).fromActual)

        val enough = five + spend(8, 20, 2_000, PaymentMethod.CASH, 206)
        assertTrue(MethodMixRules.of(snapshot.copy(ledger = enough), living).fromActual)
        assertFalse(
            "項目關掉依實際比例",
            MethodMixRules.of(snapshot.copy(ledger = enough), living.copy(useActualMix = false)).fromActual,
        )
    }

    @Test fun `取樣只看本月之前的 3 個完整月`() {
        val tooOld = List(6) { spend(5, it + 1, 1_000, PaymentMethod.CASH, 300L + it) }
        assertFalse("5 月已經超出取樣範圍", MethodMixRules.of(snapshot.copy(ledger = tooOld), living).fromActual)
        val thisMonth = List(6) { spend(9, it + 1, 1_000, PaymentMethod.CASH, 400L + it) }
        assertFalse("本月還沒過完，不算", MethodMixRules.of(snapshot.copy(ledger = thisMonth), living).fromActual)
    }

    @Test fun `試算照實際比例把一筆預算拆成刷卡與現金兩筆`() {
        val sample = List(3) { spend(8, it + 1, 4_000, PaymentMethod.CREDIT_CARD, 500L + it) } +
            List(3) { spend(8, it + 10, 4_000, PaymentMethod.CASH, 600L + it) }
        val s = snapshot.copy(ledger = snapshot.ledger + sample)
        val october = BaselineBuilder.build(s).events
            .filter { it.itemId == LIVING && it.period.year == 2026 && it.period.month == 10 }
        assertEquals("刷卡一半、現金一半", 8_000L, october.filter { it.method == PaymentMethod.CREDIT_CARD }.sumOf { it.amount })
        assertEquals(8_000L, october.filter { it.method == PaymentMethod.CASH }.sumOf { it.amount })
    }

    @Test fun `本週檢查的漏記差額也算進實際比例的取樣`() {
        val missed = List(6) {
            spend(8, it + 1, 1_000, PaymentMethod.CASH, 700L + it).copy(source = EntrySource.MISSED)
        }
        assertTrue("漏記差額也是實際花費，一樣算", MethodMixRules.of(snapshot.copy(ledger = missed), living).fromActual)
    }
}
