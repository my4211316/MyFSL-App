package tw.myfsl.app.core.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.*

/**
 * cde604d 複審的原始案例。v3.2 起卡片不設預設繳款方式（R-CARD-26），為了保持案例的用意，把原本的方式換成等價的資料：
 * - FREE（原本預估 5,000）→ 一筆 7/5 只繳 5,000 的紀錄（6/20 那期沒繳清），推估之後每期繳 5,000；
 * - MINIMUM、FULL → 沒有繳款紀錄（有輸入帳單最低時照帳單最低，都沒有時全額）。
 * 建議金額改讀 CardRules.suggested（原本是 PaymentOptions.of(mode)）。斷言的數字與用意不變。
 */
class ReviewCardV3Test {
    private val today = LocalDate.of(2026, 9, 22)
    private val cycle = CardRules.cycle(YearMonth.of(2026, 9), 20, 5)
    private fun snapshot(mode: CardPayMode = CardPayMode.FULL, debt: Long = 30000L, ledger: List<LedgerEntry> = emptyList(), unassigned: Long = 0L) = FinanceSnapshot(
        today = today,
        accounts = listOf(
            Account(1, "Bank", AccountKind.BANK, balance = 100000),
            Account(3, "Card", AccountKind.CREDIT_CARD, balance = debt, balanceAsOf = today,
                statementDay = 20, paymentDueDay = 5,
                card = CardTerms(12.0, payAccountId = 1)),
        ),
        groups = emptyList(), items = emptyList(), amountsByYear = emptyMap(), actuals = emptyList(),
        ledger = (if (mode == CardPayMode.FREE) listOf(LedgerEntry(id = 900, date = LocalDate.of(2026, 7, 5), type = FlowType.TRANSFER,
            amount = 5000, accountId = 1, toAccountId = 3, source = EntrySource.DUE, postingKey = "cardpay:3:2026-06")) else emptyList()) + ledger,
        unassignedCardSpending = unassigned,
        settings = AppSettings(autoPostFrom = today.toEpochDay(), transferAccountId = 1),
    )

    private fun checkForecastCap(mode: CardPayMode) {
        // Sep 20 bill = 1,000; Sep 21 new purchase = 29,000. Oct 5 should pay only 1,000.
        val spending = LedgerEntry(date = today.minusDays(1), type = FlowType.EXPENSE, amount = 29000,
            accountId = 3, method = PaymentMethod.CREDIT_CARD)
        val s = snapshot(mode, ledger = listOf(spending))
        val expected = CardRules.suggested(CardRules.assumption(s, s.accounts[1]), DueItems.paymentOptions(s, s.accounts[1], 30000, cycle, emptyList(), 30000))
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
