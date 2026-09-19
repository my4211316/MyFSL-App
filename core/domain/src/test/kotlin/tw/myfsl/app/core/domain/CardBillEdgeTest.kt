package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RecordMark
import java.time.LocalDate
import java.time.YearMonth

/**
 * cde604d 複審（V3-01～V3-04）的交叉情境，逐筆手算。
 * 卡片 20 日結帳、次月 5 日截止、年利率 12%（月 1%）；今天 9/22、起算日 9/22；9/20 那期 10/5 截止。
 */
class CardBillEdgeTest {

    private val today = LocalDate.of(2026, 9, 22)
    private val cycle = CardRules.cycle(YearMonth.of(2026, 9), 20, 5)
    private val BANK = 1L
    private val CARD = 3L

    private fun snapshot(
        mode: CardPayMode = CardPayMode.FULL,
        debt: Long = 30_000,
        ledger: List<LedgerEntry> = emptyList(),
        unassigned: Long = 0,
        mark: RecordMark? = null,
        statements: List<CardStatement> = emptyList(),
    ) = FinanceSnapshot.empty(today).copy(
        accounts = listOf(
            Account(BANK, "銀行", AccountKind.BANK, balance = 100_000),
            Account(CARD, "卡", AccountKind.CREDIT_CARD, balance = debt, statementDay = 20, paymentDueDay = 5,
                card = CardTerms(mode, 12.0, 5_000, payAccountId = BANK)),
        ),
        ledger = ledger,
        unassignedCardSpending = unassigned,
        fullCardReconcile = mark,
        cardStatements = statements,
        settings = AppSettings(autoPostFrom = today.toEpochDay(), transferAccountId = BANK),
    )

    private fun spend(day: Int, amount: Long, card: Long?) = LedgerEntry(
        date = LocalDate.of(2026, 9, day), type = FlowType.EXPENSE, amount = amount, method = PaymentMethod.CREDIT_CARD, accountId = card,
    )

    private fun full(s: FinanceSnapshot): Long {
        val card = s.account(CARD)!!
        val base = CardRules.baseBalance(s, card)
        // 和本月到期一樣：上限是含未指定卡片刷卡的欠款
        return DueItems.paymentOptions(s, card, base, cycle, emptyList(), base).full
    }

    // ---------- V3-02：指定／未指定、結帳前／後、已被全部卡片對帳吸收 ----------

    @Test fun `未指定卡片的刷卡：結帳後的算下一期，結帳前的算這一期，被全部卡片對帳吸收的不再另外算`() {
        // 指定卡片、結帳後：30,000 − 1,000
        assertEquals(29_000L, full(snapshot(ledger = listOf(spend(21, 1_000, CARD)))))
        // 未指定、結帳後：預設卡欠款 29,000 ＋ 未指定 1,000，回推時扣回 → 29,000
        assertEquals(29_000L, full(snapshot(debt = 29_000, ledger = listOf(spend(21, 1_000, null)), unassigned = 1_000)))
        // 未指定、結帳前：算在這一期 → 30,000
        assertEquals(30_000L, full(snapshot(debt = 29_000, ledger = listOf(spend(19, 1_000, null)), unassigned = 1_000)))
        // 9/21 刷的未指定 1,000 已經被 9/22 全部卡片對帳吸收（在欠款 30,000 裡、不在未指定合計裡）：只能照欠款算 → 30,000
        val absorbed = spend(21, 1_000, null).copy(createdAt = 100)
        assertEquals(30_000L, full(snapshot(ledger = listOf(absorbed), mark = RecordMark(today, 200))))
    }

    // ---------- V3-03：輸入的帳單就是這一期的帳單 ----------

    @Test fun `帳單校正：先對帳或後對帳、結帳後已繳、重複校正、刪除校正，全額都以帳單為準`() {
        val bill = CardStatement(CARD, 2026, 9, 29_500, 3_000, coversInterest = true)
        // 已輸入帳單 29,500：不管目前欠款是多少（例如之後又校正成 30,000），全額 = 29,500
        assertEquals(29_500L, full(snapshot(statements = listOf(bill))))
        assertEquals(29_500L, full(snapshot(debt = 45_000, statements = listOf(bill))))
        // 結帳後已經繳了 10,000：全額 = 29,500 − 10,000
        val paid = LedgerEntry(date = today, type = FlowType.TRANSFER, amount = 10_000, accountId = BANK, toAccountId = CARD)
        assertEquals(19_500L, full(snapshot(debt = 20_000, ledger = listOf(paid), statements = listOf(bill))))
        // 重複校正：換成新的帳單金額
        assertEquals(29_200L, full(snapshot(statements = listOf(bill.copy(amount = 29_200)))))
        // 刪除校正：回到從欠款回推
        assertEquals(30_000L, full(snapshot()))
    }

    @Test fun `帳單校正：下一期的利息也以輸入的帳單為準`() {
        // 自由繳 5,000；9/20 帳單輸入 29,500；10/5 繳 5,000 → 10/20 利息 (29,500 − 5,000) × 1% = 245
        val bill = CardStatement(CARD, 2026, 9, 29_500, coversInterest = true)
        val s = snapshot(CardPayMode.FREE, statements = listOf(bill))
        val dues = DueItems.list(s, through = LocalDate.of(2026, 10, 31))
        assertEquals(5_000L, dues.single { it.key == "cardpay:3:2026-09" }.amount)
        assertEquals(245L, dues.single { it.key == "cardint:3:2026-10" }.amount)
    }

    // ---------- V3-01：試算的自由與最低也不超過帳單 ----------

    @Test fun `試算：自由、最低不會提早繳還沒出帳的刷卡；全額照舊`() {
        fun octPayment(mode: CardPayMode, statements: List<CardStatement> = emptyList()): Long {
            val s = snapshot(mode, ledger = listOf(spend(21, 29_000, CARD)), statements = statements)
            return CashFlowEngine.run(BaselineBuilder.build(s, 4)).periods.single { it.period == Period.of(LocalDate.of(2026, 10, 5)) }.cardPayments
        }
        assertEquals("9/20 帳單只有 1,000", 1_000L, octPayment(CardPayMode.FREE))
        assertEquals(1_000L, octPayment(CardPayMode.MINIMUM))
        assertEquals(1_000L, octPayment(CardPayMode.FULL))
        // 帳單比預估大時，自由照預估繳 5,000
        assertEquals(5_000L, octPayment(CardPayMode.FREE, listOf(CardStatement(CARD, 2026, 9, 20_000))))
    }

    // ---------- V3-04：帳單上的最低應繳是 0 ----------

    @Test fun `最低應繳 0：本期不用繳、不列也不提醒；沒輸入帳單時照預估`() {
        val zero = snapshot(CardPayMode.MINIMUM, statements = listOf(CardStatement(CARD, 2026, 9, 30_000, 0, coversInterest = true)))
        assertTrue(DueItems.list(zero, through = cycle.due).none { it.kind == DueKind.CARD_PAYMENT })
        assertTrue(Reminders.forDate(zero, LocalDate.of(2026, 9, 28)).none { it.id.startsWith("cardpay:") })
        assertEquals("沒輸入帳單：最低用預估 5,000", 5_000L,
            DueItems.list(snapshot(CardPayMode.MINIMUM), through = cycle.due).single { it.kind == DueKind.CARD_PAYMENT }.amount)
        // 試算也不繳
        val payments = CashFlowEngine.run(BaselineBuilder.build(zero, 4)).periods.single { it.period == Period.of(cycle.due) }.cardPayments
        assertEquals(0L, payments)
    }
}
