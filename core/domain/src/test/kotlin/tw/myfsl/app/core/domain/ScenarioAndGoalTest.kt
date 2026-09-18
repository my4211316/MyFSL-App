package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.PAY_CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
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
    private val oct = Period(2026, 10, Half.FIRST)
    private val pool = CashFlowEngine.CARD_POOL_ID

    @Test fun `調整項目：只影響指定項目與起始期之後`() {
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.AdjustItems(listOf(LIVING), -20.0, oct.index)))
        val sepCash = applied.events.first { it.itemId == LIVING && it.method == PaymentMethod.CASH && it.period.month == 9 }
        val octCash = applied.events.first { it.itemId == LIVING && it.method == PaymentMethod.CASH && it.period == oct }
        assertEquals(2_300L, sepCash.amount)
        assertEquals(3_600L, octCash.amount)
        assertEquals(base.events.first { it.itemId == SALARY }.amount, applied.events.first { it.itemId == SALARY }.amount)
    }

    @Test fun `停止項目`() {
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.StopItem(SALARY, oct.index)))
        assertTrue(applied.events.none { it.itemId == SALARY && it.period >= oct })
        assertTrue(applied.events.any { it.itemId == SALARY && it.period < oct })
    }

    @Test fun `改支付方式：刷卡改現金後改由現金帳戶扣款`() {
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
        assertEquals(pool, added.single { it.kind == EventKind.EXPENSE }.fromAccountId)
    }

    @Test fun `清償信用卡：兩張卡合併成一筆清償，並停止之後的繳卡費`() {
        val applied = ScenarioApplier.apply(base, listOf(ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index)))
        val payoff = applied.events.filter { it.payFullBalance }
        assertEquals(1, payoff.size)
        assertEquals(pool, payoff.single().toAccountId)
        assertTrue(applied.events.none { it.itemId == PAY_CARD_A && it.period >= oct })
        assertTrue(applied.events.any { it.itemId == PAY_CARD_A && it.period < oct })
        val result = CashFlowEngine.run(applied)
        val octResult = result.periods.first { it.period == oct }
        val debtBefore = result.periods.first { it.period == oct.plus(-1) }.cardDebtEnd
        assertEquals(debtBefore, octResult.debtPayoff)
        assertEquals(0L, octResult.cardPayments)
        // 期初清償後，同一個半月的刷卡成為新的卡債
        assertEquals(octResult.cardSpending, octResult.balances[pool])
    }

    @Test fun `新增貸款：撥款入帳，下個月同半月開始繳款，只保留試算期間內的期數`() {
        val change = ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK, Half.SECOND)
        val applied = ScenarioApplier.apply(base, listOf(change))
        val loanId = ScenarioApplier.SCENARIO_ACCOUNT_BASE
        assertEquals(AccountKind.LOAN, applied.accounts.single { it.id == loanId }.kind)
        val disbursement = applied.events.single { it.fromAccountId == loanId }
        assertEquals(oct, disbursement.period)
        val payments = applied.events.filter { it.toAccountId == loanId }
        assertEquals(Period(2026, 11, Half.SECOND), payments.minOf { it.period })
        assertEquals(22, payments.size)
    }

    @Test fun `反推：已達標時不需調整`() {
        val result = GoalSeeker.seek(base, setOf(LIVING), GoalTarget.MinLiquid(-1_000_000))
        assertEquals(0.0, result.cutPercent, 0.0)
        assertTrue(result.achievable)
        assertTrue(result.cutsPerYear.isEmpty())
    }

    @Test fun `反推：選的項目全砍也達不到時回報做不到`() {
        val result = GoalSeeker.seek(base, setOf(SampleHousehold.HOUSEHOLD), GoalTarget.MinLiquid(1_000_000))
        assertEquals(100.0, result.cutPercent, 0.0)
        assertFalse(result.achievable)
    }

    @Test fun `反推收支打平：收入兩萬、可調支出兩萬五，剛好要減 20%`() {
        val start = Period(2026, 9, Half.FIRST)
        val events = (0 until 24).flatMap { i ->
            val p = start.plus(i * 2)
            listOf(
                FlowEvent(p, EventKind.INCOME, 20_000, "收入", toAccountId = 1),
                FlowEvent(p, EventKind.EXPENSE, 25_000, "生活", fromAccountId = 1, itemId = 7, flexible = true),
            )
        }
        val input = ForecastInput(start, 48, listOf(AccountSeed(1, "銀行", AccountKind.BANK, 0)), events, 0)
        val result = GoalSeeker.seek(input, setOf(7), GoalTarget.NoStructuralGap)
        assertEquals(20.0, result.cutPercent, 0.0)
        assertTrue(result.achievable)
        assertEquals(60_000L, result.cutsPerYear[7L])
    }

    @Test fun `反推最低水位：結果達標，少 0_2 個百分點就不達標`() {
        val target = GoalTarget.MinLiquid(-20_000)
        val ids = setOf(LIVING, SampleHousehold.HOUSEHOLD, SampleHousehold.FUEL)
        val result = GoalSeeker.seek(base, ids, target)
        assertTrue(result.achievable)
        assertTrue(result.result.lowestLiquid >= -20_000)
        val less = ScenarioApplier.run(base, listOf(ScenarioChange.AdjustItems(ids.toList(), -(result.cutPercent - 0.2), base.start.index)))
        assertTrue(less.lowestLiquid < -20_000)
    }
}
