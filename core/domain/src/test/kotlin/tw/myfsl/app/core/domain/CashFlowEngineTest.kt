package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CashFlowEngineTest {

    private val start = Period(2026, 9, Half.FIRST)
    private val second = start.next()
    private val pool = CashFlowEngine.CARD_POOL_ID

    private fun input(accounts: List<AccountSeed>, events: List<FlowEvent>, periods: Int = 2, safety: Long = 0) =
        ForecastInput(start, periods, accounts, events, safety, mapOf(PaymentMethod.CREDIT_CARD to pool))

    private val bank = AccountSeed(1, "銀行", AccountKind.BANK, 100_000)
    private val cash = AccountSeed(2, "現金", AccountKind.CASH, 0)
    private val card = AccountSeed(pool, "信用卡", AccountKind.CREDIT_CARD, 0)

    @Test fun `保守最低水位：同一半月內支出算在收入之前`() {
        val events = listOf(
            FlowEvent(start, EventKind.INCOME, 50_000, "薪資", toAccountId = 1),
            FlowEvent(start, EventKind.EXPENSE, 15_000, "現金支出", fromAccountId = 1),
            FlowEvent(second, EventKind.EXPENSE, 15_000, "現金支出", fromAccountId = 1),
            FlowEvent(start, EventKind.EXPENSE, 10_000, "刷卡", fromAccountId = pool),
            FlowEvent(second, EventKind.EXPENSE, 10_000, "刷卡", fromAccountId = pool),
            FlowEvent(second, EventKind.TRANSFER, 15_000, "繳卡費", fromAccountId = 1, toAccountId = pool),
        )
        val result = CashFlowEngine.run(input(listOf(bank, card), events))
        val (p1, p2) = result.periods
        assertEquals(85_000L, p1.liquidLow)
        assertEquals(135_000L, p1.liquidEnd)
        assertEquals(10_000L, p1.cardDebtEnd)
        assertEquals(105_000L, p2.liquidLow)
        assertEquals(105_000L, p2.liquidEnd)
        assertEquals(5_000L, p2.cardDebtEnd)
        assertEquals(20_000L, result.periods.sumOf { it.cardSpending })
        assertEquals(15_000L, p2.cardPayments)
        assertEquals(p1, result.lowest)
        assertEquals(0L, result.structuralGapPerYear)
    }

    @Test fun `帳戶之間互轉不影響水位`() {
        val events = listOf(FlowEvent(start, EventKind.TRANSFER, 30_000, "領現", fromAccountId = 1, toAccountId = 2))
        val result = CashFlowEngine.run(input(listOf(bank, cash), events, periods = 1))
        val p = result.periods.single()
        assertEquals(0L, p.liquidOut)
        assertEquals(0L, p.liquidIn)
        assertEquals(100_000L, p.liquidLow)
        assertEquals(70_000L, p.balances[1])
        assertEquals(30_000L, p.balances[2])
    }

    @Test fun `新借款撥款視為期初到位，並可同期全額清償卡債`() {
        val loan = AccountSeed(-1000, "新貸款", AccountKind.LOAN, 0)
        val events = listOf(
            FlowEvent(start, EventKind.TRANSFER, 200_000, "撥款", fromAccountId = -1000, toAccountId = 1),
            FlowEvent(start, EventKind.TRANSFER, 0, "清償", fromAccountId = 1, toAccountId = pool, payFullBalance = true),
        )
        val accounts = listOf(bank.copy(balance = 10_000), card.copy(balance = 105_000), loan)
        val p = CashFlowEngine.run(input(accounts, events, periods = 1)).periods.single()
        assertEquals(105_000L, p.liquidLow)
        assertEquals(200_000L, p.borrowing)
        assertEquals(105_000L, p.debtPayoff)
        assertEquals(0L, p.cardPayments)
        assertEquals(0L, p.cardDebtEnd)
        assertEquals(200_000L, p.loanDebtEnd)
        assertEquals(105_000L, p.liquidEnd)
    }

    @Test fun `全額清償時欠款為零就略過`() {
        val events = listOf(FlowEvent(start, EventKind.TRANSFER, 0, "清償", fromAccountId = 1, toAccountId = pool, payFullBalance = true))
        val p = CashFlowEngine.run(input(listOf(bank, card), events, periods = 1)).periods.single()
        assertEquals(0L, p.debtPayoff)
        assertEquals(0, p.events.size)
    }

    @Test fun `貸款本金還款計入結構缺口，換算成每年`() {
        val loan = AccountSeed(5, "信貸", AccountKind.LOAN, 100_000)
        val events = listOf(
            FlowEvent(start, EventKind.INCOME, 10_000, "收入", toAccountId = 1),
            FlowEvent(start, EventKind.TRANSFER, 3_000, "本金", fromAccountId = 1, toAccountId = 5),
        )
        val result = CashFlowEngine.run(input(listOf(bank, loan), events, periods = 1))
        assertEquals(3_000L, result.totalPrincipalRepaid)
        assertEquals(168_000L, result.structuralGapPerYear)
        assertEquals(97_000L, result.endLoanDebt)
    }

    @Test fun `安全線與負水位的第一期`() {
        val events = listOf(
            FlowEvent(start, EventKind.EXPENSE, 80_000, "大額支出", fromAccountId = 1),
            FlowEvent(second, EventKind.EXPENSE, 30_000, "大額支出", fromAccountId = 1),
        )
        val result = CashFlowEngine.run(input(listOf(bank), events, safety = 30_000))
        assertEquals(start, result.firstBelowSafety?.period)
        assertEquals(second, result.firstNegative?.period)
        assertEquals(-10_000L, result.lowestLiquid)
    }

    @Test fun `找不到帳戶的事件只計入收支，不影響餘額`() {
        val events = listOf(FlowEvent(start, EventKind.EXPENSE, 1_000, "未知帳戶", fromAccountId = 999))
        val result = CashFlowEngine.run(input(listOf(bank), events, periods = 1))
        assertEquals(1_000L, result.totalExpense)
        assertEquals(100_000L, result.endLiquid)
    }

    @Test fun `沒有期別時的預設值`() {
        val result = CashFlowEngine.run(input(listOf(bank), emptyList(), periods = 0))
        assertEquals(0L, result.structuralGapPerYear)
        assertEquals(100_000L, result.lowestLiquid)
        assertNull(result.firstBelowSafety)
    }
}
