package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.PAY_CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioAndGoalTest {

    private val base = BaselineBuilder.build(SampleHousehold.snapshot())
    private val oct = Period(2026, 10)

    @Test fun `調整項目：只影響指定項目與起始期之後`() {
        val food = SampleHousehold.LIVING
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.AdjustItems(listOf(food), -20.0, oct.index)))
        val sepCash = applied.events.first { it.itemId == food && it.method == PaymentMethod.CASH && it.period.month == 9 }
        val octCash = applied.events.first { it.itemId == food && it.method == PaymentMethod.CASH && it.period == oct }
        assertEquals("9 月只剩 9,000 − 4,400，不受影響", 4_600L, sepCash.amount)
        assertEquals("9,000 × 0.8", 7_200L, octCash.amount)
        assertEquals(base.events.first { it.itemId == SALARY }.amount, applied.events.first { it.itemId == SALARY }.amount)
    }

    @Test fun `停止項目`() {
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.StopItem(SALARY, oct.index)))
        assertTrue(applied.events.none { it.itemId == SALARY && it.period >= oct })
        assertTrue(applied.events.any { it.itemId == SALARY && it.period < oct })
    }

    @Test fun `改支付方式：刷卡改現金後改由現金帳戶扣款`() {
        // 生活費本來就有刷卡列（R-MIX-01），所以基準線裡有東西可以改
        val applied = ScenarioApplier.apply(
            base,
            listOf(ScenarioChange.ChangeMethod(listOf(LIVING), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, oct.index)),
        )
        val moved = applied.events.filter { it.itemId == LIVING && it.period >= oct }
        assertTrue(moved.none { it.method == PaymentMethod.CREDIT_CARD })
        assertTrue(moved.all { it.fromAccountId == CASH })
        assertTrue(applied.events.any { it.itemId == LIVING && it.method == PaymentMethod.CREDIT_CARD && it.period.month == 9 })
    }

    @Test fun `一次性收支`() {
        val applied = ScenarioApplier.apply(
            base,
            listOf(
                ScenarioChange.OneOff("退稅", oct.index, FlowType.INCOME, 8_000, accountId = BANK),
                ScenarioChange.OneOff("修車", oct.index, FlowType.EXPENSE, 20_000, method = PaymentMethod.CREDIT_CARD),
            ),
        )
        val added = applied.events.filter { it.source == EventSource.SCENARIO }
        assertEquals(BANK, added.single { it.kind == EventKind.INCOME }.toAccountId)
        assertEquals("刷卡的一次性支出算在預設卡片", CARD_A, added.single { it.kind == EventKind.EXPENSE }.fromAccountId)
    }

    @Test fun `清償信用卡：每張卡各自清償，並停止之後的繳卡費`() {
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index)))
        val payoff = applied.events.filter { it.payFullBalance }
        assertEquals(setOf(CARD_A, CARD_B), payoff.map { it.toAccountId }.toSet())
        assertTrue(applied.events.none { it.itemId == PAY_CARD_B && it.period >= oct })
        assertTrue(applied.events.any { it.itemId == PAY_CARD_B && it.period < oct })
        // F12：卡片的合約（計息與繳款）照舊，清償後欠款是 0 所以金額是 0；有新刷卡時依原條件計息
        assertTrue("A 卡的合約照舊", applied.events.any { it.relatedAccountId == CARD_A && it.source == EventSource.CARD_SCHEDULE && it.period > oct })
        val result = CashFlowEngine.run(applied)
        val octResult = result.periods.first { it.period == oct }
        // 清償付的是**全部餘額**（未繳卡款），不只帳單沒繳掉的那部分（R-CARD-28）
        val debtBefore = result.periods.first { it.period == oct.plus(-1) }.cardUnpaidEnd
        // 10/1 結帳和 10/15 截止同一個半月：先計 A 卡利息（9/1 帳單 50,200 − 9/15 已繳 18,000 = 32,200 × 15% ÷ 12 = 402.5 → 403）再清償（R-ORD-01）
        assertEquals(debtBefore + 403, octResult.debtPayoff)
        assertEquals(0L, octResult.cardPayments)
        // 期初清償後，同一個半月的刷卡成為新的卡債
        assertEquals("刷卡都在預設卡片 A", octResult.cardSpending, octResult.balances[CARD_A])
        assertEquals(0L, octResult.balances[CARD_B])
    }

    @Test fun `新增貸款：撥款入帳，下個月同半月開始繳款，只保留試算期間內的期數`() {
        val change = ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK)
        val applied = ScenarioApplier.apply(base, listOf(change))
        val loanId = ScenarioApplier.SCENARIO_ACCOUNT_BASE
        assertEquals(AccountKind.LOAN, applied.accounts.single { it.id == loanId }.kind)
        val disbursement = applied.events.single { it.fromAccountId == loanId }
        assertEquals(oct, disbursement.period)
        val payments = applied.events.filter { it.toAccountId == loanId }
        assertEquals(Period(2026, 11), payments.minOf { it.period })
        assertEquals(22, payments.size)
    }

    @Test fun `反推：已達標時不需調整`() {
        val result = GoalSeeker.seek(base, setOf(LIVING), GoalTarget.MinLiquid(-1_000_000))
        assertEquals(0.0, result.cutPercent, 0.0)
        assertTrue(result.achievable)
        assertTrue(result.cutsPerYear.isEmpty())
    }

    @Test fun `反推：沒選項目或選的項目沒有金額，直接說沒得減（R-GS-04）`() {
        val none = GoalSeeker.seek(base, emptySet(), GoalTarget.MinLiquid(110_000))
        assertTrue(none.nothingToCut)
        assertFalse(none.achievable)
        assertEquals(0.0, none.cutPercent, 0.0)
        assertTrue("沒有這個項目", GoalSeeker.seek(base, setOf(999L), GoalTarget.MinLiquid(110_000)).nothingToCut)
    }

    @Test fun `反推：最低點在開始減少之前，怎麼減都來不及（R-GS-04）`() {
        // 現況最低 89,465 在 2027/1；從 2027/6 才開始減，救不到
        val late = GoalSeeker.seek(base, setOf(LIVING), GoalTarget.MinLiquid(110_000), fromIndex = Period(2027, 6).index)
        assertFalse(late.achievable)
        assertTrue(late.lowBeforeStart)
        // 從現在開始減就來得及
        val now = GoalSeeker.seek(base, setOf(LIVING), GoalTarget.MinLiquid(110_000))
        assertTrue(now.achievable)
        assertFalse(now.lowBeforeStart)
    }

    @Test fun `反推：選的項目全砍也達不到時回報做不到`() {
        val result = GoalSeeker.seek(base, setOf(SampleHousehold.HOUSEHOLD), GoalTarget.MinLiquid(1_000_000))
        assertEquals(100.0, result.cutPercent, 0.0)
        assertFalse(result.achievable)
    }

    @Test fun `反推收支打平：收入兩萬、可調支出兩萬五，剛好要減 20%`() {
        val start = Period(2026, 9)
        val events = (0 until 24).flatMap { i ->
            val p = start.plus(i)
            listOf(
                FlowEvent(p, EventKind.INCOME, 20_000, "收入", toAccountId = 1),
                FlowEvent(p, EventKind.EXPENSE, 25_000, "生活", fromAccountId = 1, itemId = 7, flexible = true),
            )
        }
        val input = ForecastInput(start, 24, listOf(AccountSeed(1, "銀行", AccountKind.BANK, 0)), events, 0)
        val result = GoalSeeker.seek(input, setOf(7), GoalTarget.NoStructuralGap)
        assertEquals(20.0, result.cutPercent, 0.0)
        assertTrue(result.achievable)
        assertEquals("每月省 5,000 × 12", 60_000L, result.cutsPerYear[7L])
    }

    @Test fun `反推最低水位：結果達標，少 0_2 個百分點就不達標`() {
        // 現況最低 89,465，目標拉到 110,000
        val target = GoalTarget.MinLiquid(110_000)
        val ids = setOf(LIVING, SampleHousehold.HOUSEHOLD, SampleHousehold.FUEL)
        val result = GoalSeeker.seek(base, ids, target)
        assertTrue(result.achievable)
        assertTrue(result.cutPercent > 0)
        assertTrue(result.result.lowestLiquid >= 110_000)
        val less = ScenarioApplier.run(base, listOf(ScenarioChange.AdjustItems(ids.toList(), -(result.cutPercent - 0.2), base.start.index)))
        assertTrue(less.lowestLiquid < 110_000)
    }
}
