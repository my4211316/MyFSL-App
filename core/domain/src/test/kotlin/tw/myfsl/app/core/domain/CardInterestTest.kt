package tw.myfsl.app.core.domain

import tw.myfsl.app.core.domain.CardRules.PaymentAssumption.Source
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.ScenarioChange
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 信用卡依帳單繳款：全額／自由／最低、逐期計息、帳單校正、要留給卡費的現金（R-CARD-20–25）。
 * 用獨立的示意資料（不是設計稿那份）。每個數字都可以手算：
 * 卡片 20 日結帳、次月 5 日截止，年利率 15%（月 1.25%）；今天 9/1、起算日 9/1。
 * 8/20 那期的截止日 9/5 在起算之後，所以試算開始時「目前這一期」的帳單 = 目前欠款。
 * 卡片不設預設繳款方式（R-CARD-26）：要推估「每期繳 X」時，放一筆 7/5 只繳 X 的紀錄（6/20 那期沒繳清）；沒有紀錄時推估全額。
 */
class CardInterestTest {

    private val today = LocalDate.of(2026, 9, 1)
    private val sep1 = Period(2026, 9, Half.FIRST)
    private val sep2 = Period(2026, 9, Half.SECOND)
    private val oct1 = Period(2026, 10, Half.FIRST)
    private val oct2 = Period(2026, 10, Half.SECOND)

    private val BANK = 1L
    private val CARD = 3L
    private val SALARY = 101L
    private val LIVING = 501L
    private val PAY_CARD = 801L

    private fun terms(rate: Double? = 15.0) = CardTerms(rate, payAccountId = BANK)

    /** 某張卡過去一期只繳了 [amount]（沒繳清）的紀錄：之後每期推估繳這麼多。 */
    private fun paidBefore(card: Long, amount: Money, statementMonth: Int = 6, dueDay: Int = 5, dueMonth: Int = 7) = LedgerEntry(
        id = 900 + card, date = LocalDate.of(2026, dueMonth, dueDay), type = FlowType.TRANSFER, amount = amount,
        accountId = BANK, toAccountId = card, source = EntrySource.DUE, postingKey = "cardpay:$card:2026-%02d".format(statementMonth),
    )

    private fun snapshot(
        cardTerms: CardTerms? = terms(),
        cardDebt: Money = 400_000,
        payCardPlan: Money = 0,
        living: Money = 20_000,
        paying: Money? = 18_000,
    ): FinanceSnapshot {
        val amounts: MonthlyAmounts = buildMap {
            put(PlanLine(SALARY), List(12) { 80_000L })
            if (living > 0) put(PlanLine(LIVING, PaymentMethod.CREDIT_CARD), List(12) { living })
            if (payCardPlan > 0) put(PlanLine(PAY_CARD), List(12) { payCardPlan })
        }
        return FinanceSnapshot(
            today = today,
            accounts = listOf(
                Account(BANK, "銀行", AccountKind.BANK, balance = 100_000, balanceAsOf = today),
                Account(
                    CARD, "信用卡", AccountKind.CREDIT_CARD, balance = cardDebt, balanceAsOf = today, creditLimit = 500_000,
                    statementDay = 20, paymentDueDay = 5, card = cardTerms,
                ),
            ),
            groups = listOf(PlanGroup(1, "收入", 1), PlanGroup(5, "生活", 5), PlanGroup(8, "繳款", 8)),
            items = listOfNotNull(
                PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, timing = Timing.FIRST_HALF, dueDay = 5),
                PlanItem(LIVING, "生活費", 5, FlowType.EXPENSE, timing = Timing.SPLIT, flexibility = Flexibility.FLEXIBLE, tracking = TrackingMode.LEDGER)
                    .takeIf { living > 0 },
                PlanItem(PAY_CARD, "繳信用卡", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, timing = Timing.FIRST_HALF, dueDay = 5)
                    .takeIf { payCardPlan > 0 },
            ),
            amountsByYear = mapOf(2026 to amounts, 2027 to amounts, 2028 to amounts),
            actuals = emptyList(),
            ledger = listOfNotNull(paying?.let { paidBefore(CARD, it) }),
            settings = AppSettings(safetyLevel = 0, horizonMonths = 24, autoPostFrom = today.toEpochDay(), transferAccountId = BANK),
        )
    }

    private fun run(s: FinanceSnapshot, changes: List<ScenarioChange> = emptyList()) = ScenarioApplier.run(BaselineBuilder.build(s), changes)

    // ---------- 規則 ----------

    @Test fun `循環利息：沒繳清的帳單 × 年利率 ÷ 12；沒填利率不計息`() {
        assertEquals(5_000L, CardRules.monthlyInterest(400_000, 15.0))
        assertEquals(0L, CardRules.monthlyInterest(400_000, null))
        assertEquals(0L, CardRules.monthlyInterest(400_000, 0.0))
        assertEquals(0L, CardRules.monthlyInterest(-1_000, 15.0))
    }

    @Test fun `期別：截止日是結帳日之後的第一個繳款日；短月份取月底`() {
        assertEquals(
            CardRules.Cycle(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 7)),
            CardRules.cycle(YearMonth.of(2026, 9), 22, 7),
        )
        assertEquals("樂天：5 日結帳、同月 26 日截止", LocalDate.of(2026, 9, 26), CardRules.cycle(YearMonth.of(2026, 9), 5, 26).due)
        assertEquals(LocalDate.of(2027, 2, 28), CardRules.cycle(YearMonth.of(2027, 2), 31, 15).statement)
        val card = snapshot().account(CARD)!!
        assertEquals("9/1 最近一次結帳是 8/20", LocalDate.of(2026, 8, 20), CardRules.latestCycle(card, today)!!.statement)
    }

    @Test fun `繳款選項與建議金額：都不超過帳單還沒繳的部分`() {
        assertEquals("還沒輸入帳單：沒有最低", CardRules.PaymentOptions(full = 30_000, minimum = null), CardRules.options(30_000, 50_000, null))
        assertEquals("帳單上的最低應繳", CardRules.PaymentOptions(30_000, 3_000), CardRules.options(30_000, 50_000, 3_000))
        assertEquals("最低比帳單多：最多繳帳單", CardRules.PaymentOptions(10_000, 10_000), CardRules.options(10_000, 50_000, 12_000))
        val options = CardRules.PaymentOptions(10_000, 2_000)
        assertEquals(10_000L, CardRules.suggested(CardRules.PaymentAssumption(Source.NO_RECORD), options))
        assertEquals(10_000L, CardRules.suggested(CardRules.PaymentAssumption(Source.LAST_AMOUNT, 18_000), options))
        assertEquals(2_000L, CardRules.suggested(CardRules.PaymentAssumption(Source.STATEMENT_MINIMUM, 3_000), options))
    }

    @Test fun `繳款推估：上一期全額→全額；上一期部分→同樣金額；沒紀錄有帳單→帳單最低；都沒有→全額並提醒輸入帳單`() {
        fun assume(s: FinanceSnapshot) = CardRules.assumption(s, s.account(CARD)!!)
        assertEquals(CardRules.PaymentAssumption(Source.LAST_AMOUNT, 18_000), assume(snapshot()))
        // 目前欠款 0；6/20 帳單 = 0 ＋ 7/5 繳的 30,000 = 30,000，7/5 繳 30,000 → 繳清
        val fullPaid = snapshot(cardDebt = 0, paying = 30_000)
        assertEquals(Source.LAST_FULL, assume(fullPaid).source)
        val withBill = snapshot(paying = null).copy(cardStatements = listOf(CardStatement(CARD, 2026, 8, 400_000, 12_000)))
        assertEquals(CardRules.PaymentAssumption(Source.STATEMENT_MINIMUM, 12_000), assume(withBill))
        assertEquals(CardRules.PaymentAssumption(Source.NO_RECORD, needsBill = true), assume(snapshot(paying = null)))
        assertEquals("沒填利率：不提醒", CardRules.PaymentAssumption(Source.NO_RECORD), assume(snapshot(cardTerms = terms(null), paying = null)))
        assertEquals(
            "信用卡：有欠款，請輸入最近一期帳單（含最低應繳），試算才知道每期至少要繳多少；目前先假設全額繳清",
            assume(snapshot(paying = null)).describe("信用卡"),
        )
    }

    // ---------- 試算 ----------

    @Test fun `全額：每期繳前一期帳單，永遠不計息；結帳後的新刷卡下一期才繳`() {
        val result = run(snapshot(cardTerms = terms(null), cardDebt = 30_000, paying = null))
        // 9/5 繳 8/20 帳單 30,000；9 月兩個半月各刷 10,000 → 9/20 帳單 20,000 → 10/5 繳 20,000
        assertEquals(30_000L, result.periods.first { it.period == sep1 }.cardPayments)
        assertEquals(20_000L, result.periods.first { it.period == oct1 }.cardPayments)
        assertEquals(0L, result.totalCardInterest)
        assertEquals("10 月兩個半月又刷 20,000，10/20 結帳後欠 20,000", 20_000L, result.periods.first { it.period == oct2 }.cardDebtEnd)
    }

    @Test fun `上一期只繳一部分：之後每期繳同樣金額，沒繳清的部分在下一個結帳日計息`() {
        val result = run(snapshot(living = 0))
        // 9/5 繳 18,000；9/20 結帳：(400,000 − 18,000) × 1.25% = 4,775 → 帳單 386,775
        assertEquals(18_000L, result.periods.first { it.period == sep1 }.cardPayments)
        result.periods.first { it.period == sep2 }.run {
            assertEquals(4_775L, cardInterest)
            assertEquals(386_775L, cardDebtEnd)
        }
        // 10/5 再繳 18,000；10/20：(386,775 − 18,000) × 1.25% = 4,609.69 → 4,610
        assertEquals(4_610L, result.periods.first { it.period == oct2 }.cardInterest)
    }

    @Test fun `繳的比利息少：卡債一直長大，計畫檢查會提醒`() {
        val s = snapshot(paying = 3_000, living = 0)
        val result = run(s)
        assertTrue(result.periods.last().cardDebtEnd > 400_000)
        val assumption = CardRules.assumption(s, s.account(CARD)!!)
        val outlook = CardRules.outlook(400_000, s.account(CARD)!!.card!!, assumption, monthlySpending = 0)
        assertEquals("(400,000 − 3,000) × 1.25%", 4_963L, outlook.interest)
        assertEquals(
            "繳的錢還不夠付循環利息 $4,963，卡債只會變多；每月至少要多繳 $1,963 卡債才不會再增加",
            CardRules.warning(outlook),
        )
        assertNull("永遠還不完", CardRules.monthsToClear(400_000, s.account(CARD)!!.card!!, assumption))
    }

    @Test fun `沒有設定依帳單繳款的卡：不計息，繳款完全看計畫`() {
        val result = run(snapshot(cardTerms = null, payCardPlan = 18_000))
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals(0L, p1.cardInterest)
        assertEquals(18_000L, p1.cardPayments)
        assertEquals(392_000L, p1.cardDebtEnd)
        assertEquals(0L, result.totalCardInterest)
    }

    @Test fun `情境：借貸款清掉卡債之後就不再產生循環利息`() {
        val payoff = run(
            snapshot(),
            listOf(
                ScenarioChange.AddLoan("整合貸款", 420_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, sep1.index, BANK, BANK, Half.SECOND),
                ScenarioChange.PayOffDebts(listOf(CARD), BANK, sep1.index),
                ScenarioChange.ChangeMethod(listOf(LIVING), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, sep1.index),
            ),
        )
        val current = run(snapshot())
        assertEquals(0L, payoff.endCardDebt)
        assertEquals("清掉後不再有循環利息", 0L, payoff.totalCardInterest)
        assertTrue("整合後付的利息比繼續循環少", payoff.totalCardInterest < current.totalCardInterest)
    }

    @Test fun `逐卡：每張卡用自己的結帳日、截止日與繳款推估`() {
        val base = snapshot(paying = null)
        val s = base.copy(
            accounts = listOf(
                base.accounts.first(),
                Account(3, "台新", AccountKind.CREDIT_CARD, balance = 50_000, statementDay = 20, paymentDueDay = 5, card = terms(null)),
                Account(4, "樂天", AccountKind.CREDIT_CARD, balance = 100_000, statementDay = 10, paymentDueDay = 28, card = terms(12.0)),
            ),
            ledger = listOf(paidBefore(4, 5_000, statementMonth = 6, dueDay = 28, dueMonth = 6)),
        )
        val dues = DueItems.list(s)
        assertEquals("台新 9/5 全額繳 8/20 帳單", 50_000L, dues.single { it.key == "cardpay:3:2026-08" }.amount)
        dues.single { it.key == "cardpay:4:2026-09" }.run {
            assertEquals("樂天 9/28 照上一期繳 5,000", 5_000L, amount)
            assertEquals("全額是 9/10 帳單 100,000", 100_000L, payOptions!!.full)
        }
        assertTrue("台新 9/20：上一期繳清，不計息；樂天 9/10：上一期 8/28 就截止了，視為繳清", dues.none { it.kind == DueKind.CARD_INTEREST })
    }

    // ---------- 年度計畫表與帳戶頁 ----------

    @Test fun `計畫表：只繳一部分的卡以推估金額估算利息、繳款與卡債變化`() {
        val summary = PlanSummaryCalculator.summarize(snapshot(), 2026)
        assertEquals("(400,000 − 18,000) × 1.25% = 4,775 × 12", 57_300L, summary.totalCardInterest)
        assertEquals(240_000L, summary.totalCardSpending)
        assertEquals(216_000L, summary.totalCardPayments)
        assertEquals("刷卡 240,000 ＋ 利息 57,300 − 繳款 216,000", 81_300L, summary.cardDebtIncrease)
        assertEquals("960,000 − 240,000 − 57,300", 662_700L, summary.structuralGap)
        // 全額的卡：不計息
        assertEquals(0L, PlanSummaryCalculator.summarize(snapshot(cardTerms = terms(null), paying = null), 2026).totalCardInterest)
    }

    @Test fun `計畫檢查：依帳單繳款的卡，計畫轉帳不計；繳得不夠會提醒，全額不提醒`() {
        val both = snapshot(payCardPlan = 18_000)
        val messages = PlanValidator.validate(both, 2026).map { it.message }
        assertTrue(messages.any { it == "「繳信用卡」不計入：「信用卡」已依合約自動繳款。若這是額外還款，請在項目勾選「額外還款」" })
        assertTrue(
            messages.any { it == "「信用卡」每月刷 $20,000、利息 $4,775，繳 $18,000 不夠；每月至少要多繳 $6,775 卡債才不會再增加" },
        )
        assertTrue(PlanValidator.validate(snapshot(cardTerms = terms(null), paying = null), 2026).none { it.message.contains("才不會再增加") })
    }

    @Test fun `帳戶頁：目前這一期的帳單、預計繳款、帳單上的最低應繳`() {
        val card = AccountSummaryCalculator.overview(snapshot(), today).cards.single()
        assertEquals(LocalDate.of(2026, 9, 5), card.cycle!!.due)
        assertEquals(400_000L, card.currentBill)
        assertEquals("照上一期 18,000", 18_000L, card.fixedPayment)
        assertEquals(CardRules.PaymentAssumption.Source.LAST_AMOUNT, card.assumption!!.source)
        assertEquals(4_775L, card.interest)
        assertNull(card.minimumPayment)

        val entered = snapshot(paying = null).copy(cardStatements = listOf(CardStatement(CARD, 2026, 8, 400_000, 12_000)))
        AccountSummaryCalculator.overview(entered, today).cards.single().run {
            assertEquals(12_000L, minimumPayment)
            assertEquals("還沒有繳款紀錄：帳單上的最低 12,000", 12_000L, fixedPayment)
            assertTrue(billEntered)
        }
        val plain = AccountSummaryCalculator.overview(snapshot(cardTerms = null), today).cards.single()
        assertEquals(0L, plain.interest)
        assertNull(plain.currentBill)
    }

    // ---------- 帳單校正（R-CARD-23） ----------

    /** 9/22：9/20 結帳過了；9/21 自己記了一筆 1,000 刷卡（已經在欠款 30,000 裡）；起算日 9/10（8/20 那期 9/5 就截止）。 */
    private fun afterStatement(): FinanceSnapshot {
        val base = snapshot(cardTerms = terms(null), cardDebt = 30_000, paying = null)
        return base.copy(
            today = LocalDate.of(2026, 9, 22),
            ledger = listOf(
                LedgerEntry(id = 1, date = LocalDate.of(2026, 9, 21), type = FlowType.EXPENSE, amount = 1_000, itemId = LIVING,
                    method = PaymentMethod.CREDIT_CARD, accountId = CARD),
            ),
            settings = base.settings.copy(autoPostFrom = LocalDate.of(2026, 9, 10).toEpochDay()),
        )
    }

    @Test fun `帳單校正：App 估計是結帳日的欠款；差額另記一筆，之後以帳單為準`() {
        val s = afterStatement()
        val card = s.account(CARD)!!
        val preview = BillCorrection.preview(s, card)!!
        assertEquals(LocalDate.of(2026, 9, 20), preview.cycle.statement)
        assertEquals("30,000 − 結帳後刷的 1,000", 29_000L, preview.estimate)

        assertEquals("最低應繳要在 0 到帳單金額之間", BillCorrection.validate(s, card, 29_500, 30_000))
        val result = BillCorrection.correct(s, card, preview.cycle, 29_500, 3_000)
        result.entry!!.run {
            assertEquals(500L, amount)
            assertEquals(LocalDate.of(2026, 9, 20), date)
            assertEquals(EntrySource.STATEMENT, source)
            assertEquals("stmt:3:2026-09", postingKey)
        }
        assertEquals(CardStatement(CARD, 2026, 9, 29_500, 3_000, coversInterest = true), result.statement)
        assertEquals("cardint:3:2026-09", result.coveredInterestKey)

        val after = s.copy(
            ledger = s.ledger + result.entry!!.copy(id = 2),
            accounts = s.accounts.map { if (it.id == CARD) it.copy(balance = 30_500) else it },
            cardStatements = listOf(result.statement),
            postedKeys = setOf(result.coveredInterestKey!!),
        )
        val pay = DueItems.list(after, through = LocalDate.of(2026, 10, 31)).single { it.key == "cardpay:3:2026-09" }
        assertEquals("10/7 全額 = 帳單 29,500", CardRules.PaymentOptions(full = 29_500, minimum = 3_000), pay.payOptions)
        assertEquals(29_500L, BillCorrection.preview(after, after.account(CARD)!!)!!.existing!!.amount)

        // 再校正一次：取代前一次（29,200 − 29,000 = 200）
        assertEquals(200L, BillCorrection.correct(after, after.account(CARD)!!, preview.cycle, 29_200, null).entry!!.amount)
        // 和估計一樣：不另記差額
        assertNull(BillCorrection.correct(s, card, preview.cycle, 29_000, null).entry)

        // 刪掉差額：整筆校正一起刪，利息回到本月到期
        val plan = Deletion.plan(after, after.ledger.single { it.postingKey == "stmt:3:2026-09" })
        assertEquals(CARD to YearMonth.of(2026, 9), plan.removeStatement)
        assertEquals(listOf("cardint:3:2026-09"), plan.postedKeys)
        assertEquals(listOf("stmt:3:2026-09"), plan.ledgerKeys)
        assertEquals("紀錄頁標示", "帳單差額", RecordRules.sourceLabel(result.entry!!))
    }

    // ---------- 要留給卡費的現金（R-CARD-25） ----------

    @Test fun `要留給卡費的現金：推估全額與沒條件的卡算整筆欠款，只繳一部分的卡只算本期要繳的`() {
        val base = snapshot(paying = null)
        val s = base.copy(
            today = LocalDate.of(2026, 9, 15),
            accounts = listOf(
                base.accounts.first(),
                Account(3, "台新", AccountKind.CREDIT_CARD, balance = 50_000, statementDay = 20, paymentDueDay = 5, card = terms(null)),
                Account(4, "樂天", AccountKind.CREDIT_CARD, balance = 100_000, statementDay = 10, paymentDueDay = 28, card = terms(12.0)),
                Account(5, "沒條件的卡", AccountKind.CREDIT_CARD, balance = 3_000),
            ),
            ledger = listOf(paidBefore(4, 5_000, statementMonth = 6, dueDay = 28, dueMonth = 6)),
        )
        assertEquals("50,000 ＋ 5,000 ＋ 3,000", 58_000L, CardRules.reserve(s))
        AccountSummaryCalculator.overview(s).run {
            assertEquals(58_000L, cardReserve)
            assertEquals("100,000 − 58,000", 42_000L, freeCash)
        }
        assertEquals(58_000L, PeriodOverviewCalculator.build(s).cardReserve)
    }
}
