package tw.myfsl.app.core.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.*

class ReviewCardV3Test {
    private val today = LocalDate.of(2026, 9, 22)
    private val cycle = CardRules.cycle(YearMonth.of(2026, 9), 20, 5)
    private fun snapshot(mode: CardPayMode = CardPayMode.FULL, debt: Long = 30000L, ledger: List<LedgerEntry> = emptyList(), unassigned: Long = 0L) = FinanceSnapshot(
        today = today,
        accounts = listOf(
            Account(1, "Bank", AccountKind.BANK, balance = 100000),
            Account(3, "Card", AccountKind.CREDIT_CARD, balance = debt, balanceAsOf = today,
                statementDay = 20, paymentDueDay = 5,
                card = CardTerms(mode, 12.0, 5000L, payAccountId = 1)),
        ),
        groups = emptyList(), items = emptyList(), amountsByYear = emptyMap(), actuals = emptyList(), ledger = ledger,
        unassignedCardSpending = unassigned,
        settings = AppSettings(autoPostFrom = today.toEpochDay(), transferAccountId = 1),
    )

    private fun checkForecastCap(mode: CardPayMode) {
        // Sep 20 bill = 1,000; Sep 21 new purchase = 29,000. Oct 5 should pay only 1,000.
        val spending = LedgerEntry(date = today.minusDays(1), type = FlowType.EXPENSE, amount = 29000,
            accountId = 3, method = PaymentMethod.CREDIT_CARD)
        val s = snapshot(mode, ledger = listOf(spending))
        val expected = DueItems.paymentOptions(s, s.accounts[1], 30000, cycle, emptyList(), 30000).of(mode)
        assertEquals(1000L, expected)
        val result = CashFlowEngine.run(BaselineBuilder.build(s, 4))
        val actual = result.periods.single { it.period == Period.of(LocalDate.of(2026, 10, 5)) }.cardPayments
        assertEquals("Forecast must use the same statement cap as DueItems", expected, actual)
    }
    @Test fun freePaymentMustNotPayUnbilledSpending() = checkForecastCap(CardPayMode.FREE)
    @Test fun minimumPaymentMustNotPayUnbilledSpending() = checkForecastCap(CardPayMode.MINIMUM)

    @Test fun explicitZeroMinimumMustNotBecomeFullPayment() {
        val s = snapshot(CardPayMode.MINIMUM).copy(
            cardStatements = listOf(CardStatement(3, 2026, 9, 30000, 0, coversInterest = true)),
        )
        val payment = DueItems.list(s, cycle.due).filter { it.kind == DueKind.CARD_PAYMENT }.sumOf { it.amount }
        assertEquals("Explicit statement minimum of zero must not fall back to full balance", 0L, payment)
    }

    @Test fun unassignedSpendingAfterStatementMustStayInNextBill() {
        val spending = LedgerEntry(date = today.minusDays(1), type = FlowType.EXPENSE, amount = 1000,
            method = PaymentMethod.CREDIT_CARD, accountId = null)
        val s = snapshot(debt = 29000, ledger = listOf(spending), unassigned = 1000)
        val card = s.accounts[1]
        assertEquals(29000L, DueItems.paymentOptions(s, card, CardRules.baseBalance(s, card), cycle, emptyList(), 30000).full)
    }

    @Test fun correctedStatementMustRemainAuthoritativeAfterBalanceReconciliation() {
        // Account was reconciled Sep 22. Correction is posted Sep 20, hence excluded from current balance.
        val s = snapshot()
        val card = s.accounts[1]
        val correction = BillCorrection.correct(s, card, cycle, 29500L, 3000L)
        val entry = requireNotNull(correction.entry).copy(createdAt = 200L)
        val mark = RecordMark(today, 100L)
        val currentDebt = card.balance + listOf(entry).filter { it.isAfter(mark) }.sumOf { BalanceRules.effect(it, card.id, card.kind) }
        assertEquals(30000L, currentDebt)
        val saved = s.copy(
            ledger = listOf(entry), cardStatements = listOf(correction.statement),
            postedKeys = setOfNotNull(correction.coveredInterestKey),
            accounts = listOf(s.accounts[0], card.copy(balance = currentDebt)),
        )
        assertEquals("Entered bill, not reconstructed current debt, must control full payment", 29500L,
            DueItems.paymentOptions(saved, saved.accounts[1], currentDebt, cycle, emptyList(), currentDebt).full)
    }
}
