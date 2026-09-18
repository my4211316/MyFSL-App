package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ScenarioChange
import tw.myfsl.app.core.model.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 分期：刷卡當下全額佔用額度、帳單每期入帳、每筆分期的費用各自設定。
 */
class InstallmentTest {

    private val today = LocalDate.of(2026, 9, 5)
    private val start = Period(2026, 9, Half.FIRST)
    private val oct = Period(2026, 10, Half.FIRST)
    private val nov = Period(2026, 11, Half.FIRST)
    private val pool = CashFlowEngine.CARD_POOL_ID

    private val BANK = 1L
    private val CARD = 3L
    private val SALARY = 101L
    private val PHONE = 501L

    private fun installment(
        amount: Money = 36_000,
        months: Int = 12,
        fee: InstallmentFee = InstallmentFee.NONE,
        feeValue: Double = 0.0,
        card: Long? = CARD,
    ) = CardInstallment(
        id = 1,
        cardAccountId = card,
        itemId = PHONE,
        purchaseDate = today,
        amount = amount,
        months = months,
        fee = fee,
        feeValue = feeValue,
        firstPeriodIndex = oct.index,
    )

    private fun snapshot(vararg installments: CardInstallment, cardBalance: Money = 0) = FinanceSnapshot(
        today = today,
        accounts = listOf(
            Account(BANK, "銀行", AccountKind.BANK, balance = 100_000, balanceAsOf = today),
            Account(CARD, "信用卡", AccountKind.CREDIT_CARD, balance = cardBalance, balanceAsOf = today, creditLimit = 100_000),
        ),
        groups = listOf(PlanGroup(1, "收入", 1), PlanGroup(5, "生活", 5)),
        items = listOf(
            PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, timing = Timing.FIRST_HALF),
            PlanItem(PHONE, "手機", 5, FlowType.EXPENSE, timing = Timing.FIRST_HALF),
        ),
        amountsByYear = mapOf(2026 to mapOf(PlanLine(SALARY) to List(12) { 80_000L })),
        actuals = emptyList(),
        ledger = emptyList(),
        installments = installments.toList(),
        settings = AppSettings(safetyLevel = 0, horizonMonths = 24),
    )

    // ---------- 規則 ----------

    @Test fun `每期本金：除不盡的餘數放第一期`() {
        assertEquals(List(12) { 3_000L }, InstallmentRules.principals(36_000, 12))
        assertEquals(listOf(3_334L, 3_333L, 3_333L), InstallmentRules.principals(10_000, 3))
        assertTrue(InstallmentRules.principals(0, 12).isEmpty())
        assertTrue(InstallmentRules.principals(1_000, 0).isEmpty())
        assertEquals(10_000L, InstallmentRules.principals(10_000, 3).sum())
    }

    @Test fun `0 利率：每期只有本金，期別每個月往後一期`() {
        val schedule = InstallmentRules.schedule(installment())
        assertEquals(12, schedule.size)
        assertEquals(3_000L, schedule.first().total)
        assertEquals(0L, InstallmentRules.totalFees(installment()))
        assertEquals(36_000L, InstallmentRules.totalCost(installment()))
        assertEquals(oct.index, schedule[0].periodIndex)
        assertEquals(nov.index, schedule[1].periodIndex)
        assertEquals(Period(2027, 9, Half.FIRST).index, schedule.last().periodIndex)
    }

    @Test fun `每期固定手續費`() {
        val it = installment(fee = InstallmentFee.PER_PERIOD, feeValue = 50.0)
        assertEquals(3_050L, InstallmentRules.schedule(it).first().total)
        assertEquals(600L, InstallmentRules.totalFees(it))
        assertEquals(36_600L, InstallmentRules.totalCost(it))
    }

    @Test fun `總額手續費率：一次算出來後分攤，餘數放第一期`() {
        val it = installment(amount = 10_000, months = 3, fee = InstallmentFee.TOTAL_RATE, feeValue = 3.0)
        val schedule = InstallmentRules.schedule(it)
        assertEquals(300L, InstallmentRules.totalFees(it))
        assertEquals(listOf(100L, 100L, 100L), schedule.map { p -> p.fee })
        assertEquals(3_434L, schedule.first().total)
        assertEquals(10_300L, InstallmentRules.totalCost(it))
    }

    @Test fun `年利率：依剩餘本金逐期計息，越後面越少`() {
        val it = installment(fee = InstallmentFee.ANNUAL_RATE, feeValue = 12.0)
        val schedule = InstallmentRules.schedule(it)
        assertEquals(360L, schedule[0].fee)
        assertEquals(330L, schedule[1].fee)
        assertEquals(30L, schedule.last().fee)
        assertEquals(2_340L, InstallmentRules.totalFees(it))
    }

    @Test fun `未入帳本金佔用額度、剩餘期數、提前清償`() {
        val one = installment()
        assertEquals(36_000L, InstallmentRules.pendingPrincipal(one, start.index))
        assertEquals(12, InstallmentRules.remainingPeriods(one, start.index))
        // 已經入帳兩期之後
        assertEquals(30_000L, InstallmentRules.pendingPrincipal(one, Period(2026, 12, Half.FIRST).index))
        assertEquals(10, InstallmentRules.remainingPeriods(one, Period(2026, 12, Half.FIRST).index))
        assertEquals(30_000L, InstallmentRules.payoffAmount(one, Period(2026, 12, Half.FIRST).index))

        val withFee = installment(fee = InstallmentFee.PER_PERIOD, feeValue = 50.0)
        assertEquals(30_000L, InstallmentRules.payoffAmount(withFee, Period(2026, 12, Half.FIRST).index))
        assertEquals(
            "手續費不減免時要一起付",
            30_500L,
            InstallmentRules.payoffAmount(withFee, Period(2026, 12, Half.FIRST).index, includeFees = true),
        )
    }

    @Test fun `卡片摘要與說明文字`() {
        val summary = InstallmentRules.summary(listOf(installment(), installment().copy(id = 2, amount = 12_000, months = 6)), start, CARD)
        assertEquals(2, summary.count)
        assertEquals(48_000L, summary.pendingPrincipal)
        assertEquals(5_000L, summary.nextAmount)
        assertEquals(0, InstallmentRules.summary(listOf(installment().copy(settled = true)), start, CARD).count)

        assertEquals("分 12 期 · 每期約 $3,000 · 0 利率", InstallmentRules.description(installment()))
        assertEquals(
            "分 12 期 · 每期約 $3,050 · 每期手續費 $50（總共多付 $600）",
            InstallmentRules.description(installment(fee = InstallmentFee.PER_PERIOD, feeValue = 50.0)),
        )
        assertEquals(
            "分 3 期 · 每期約 $3,434 · 手續費 3%（總共多付 $300）",
            InstallmentRules.description(installment(amount = 10_000, months = 3, fee = InstallmentFee.TOTAL_RATE, feeValue = 3.0)),
        )
    }

    // ---------- 記帳與額度 ----------

    @Test fun `分期消費不會一次全額變成卡債；額度卻是全額佔用`() {
        val purchase = LedgerEntry(
            id = 9, date = today, type = FlowType.EXPENSE, amount = 36_000, itemId = PHONE,
            method = PaymentMethod.CREDIT_CARD, accountId = CARD, installmentId = 1,
        )
        assertEquals("卡片餘額不受影響", 0L, BalanceRules.effect(purchase, CARD, AccountKind.CREDIT_CARD))
        assertEquals("一般刷卡才會全額進卡債", 36_000L, BalanceRules.effect(purchase.copy(installmentId = null), CARD, AccountKind.CREDIT_CARD))

        val overview = AccountSummaryCalculator.overview(snapshot(installment()).copy(ledger = listOf(purchase)))
        val card = overview.cards.single()
        assertEquals(0L, card.account.balance)
        assertEquals(36_000L, card.pendingInstallmentPrincipal)
        assertEquals("已佔用額度含未入帳分期", 36_000L, card.usedCredit)
        assertEquals(64_000L, card.availableWithInstallments)
        assertEquals(1, card.installmentCount)
        assertEquals(3_000L, card.nextInstallmentAmount)
        assertEquals(36_000L, overview.pendingInstallmentPrincipal)

        // 未指定卡片的分期
        val unassigned = AccountSummaryCalculator.overview(snapshot(installment(card = null)))
        assertEquals(36_000L, unassigned.unassignedInstallmentPrincipal)
        assertEquals(0L, unassigned.cards.single().pendingInstallmentPrincipal)
    }

    // ---------- 試算 ----------

    @Test fun `每期入帳：卡債逐期增加，本金不重複算成支出`() {
        val result = ScenarioApplier.run(BaselineBuilder.build(snapshot(installment())), emptyList())
        val octResult = result.periods.first { it.period == oct }
        assertEquals(3_000L, octResult.installmentPosted)
        assertEquals("本金不是新的支出", 0L, octResult.expense)
        assertEquals("也不算新刷卡", 0L, octResult.cardSpending)
        assertEquals(3_000L, octResult.cardDebtEnd)
        assertEquals(6_000L, result.periods.first { it.period == nov }.cardDebtEnd)
        assertEquals(36_000L, result.totalInstallmentPosted)
        assertEquals(36_000L, result.endCardDebt)
        assertEquals("沒有任何支出，缺口只看收入", 0L, result.totalExpense)
    }

    @Test fun `手續費算成支出`() {
        val result = ScenarioApplier.run(
            BaselineBuilder.build(snapshot(installment(fee = InstallmentFee.PER_PERIOD, feeValue = 50.0))),
            emptyList(),
        )
        assertEquals(50L, result.periods.first { it.period == oct }.expense)
        assertEquals(600L, result.totalExpense)
        assertEquals(36_600L, result.endCardDebt)
    }

    @Test fun `情境：清償卡債時一併結清未到期分期`() {
        val base = BaselineBuilder.build(snapshot(installment()))
        val result = ScenarioApplier.run(base, listOf(ScenarioChange.PayOffDebts(listOf(CARD), BANK, nov.index)))
        val novResult = result.periods.first { it.period == nov }
        assertEquals("已入帳的 3,000 ＋ 未到期的 33,000", 36_000L, novResult.debtPayoff)
        assertEquals("卡上已入帳的部分", 3_000L, novResult.events.single { it.event.payFullBalance }.amount)
        assertEquals("剩下 11 期本金一次結清", 33_000L, novResult.events.single { it.event.label == "結清未到期分期" }.amount)
        assertEquals(0L, result.endCardDebt)
        assertEquals("清掉之後不再入帳", 3_000L, result.totalInstallmentPosted)
        assertEquals("當期流出 = 已入帳 3,000 ＋ 結清 33,000", 36_000L, novResult.liquidOut)

        // 不結清分期時，未到期的仍逐期入帳
        val keep = ScenarioApplier.run(
            base,
            listOf(ScenarioChange.PayOffDebts(listOf(CARD), BANK, nov.index, includeInstallments = false)),
        )
        assertEquals(36_000L, keep.totalInstallmentPosted)
        assertTrue(keep.endCardDebt > 0)
    }
}
