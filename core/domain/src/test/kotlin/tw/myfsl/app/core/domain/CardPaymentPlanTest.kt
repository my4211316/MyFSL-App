package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPaymentPlan
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.TrackingMode
import java.time.LocalDate

/**
 * 卡片的繳款計畫（R-CARD-27）：整年打算怎麼繳，是**預算編列的決定**。
 * 全部手算得出來：卡 1 日結帳、15 日截止，年利率 15%（月 1.25%）；今天 9/1、起算日 9/1；
 * 欠款 60,000；計畫每月刷 20,000（生活費・信用卡）；薪資 80,000。
 * 今天是 9/1，所以自動產生的繳款只算 9–12 月（R-PLS-05）。
 */
class CardPaymentPlanTest {

    private val today = LocalDate.of(2026, 9, 1)
    private val BANK = 1L
    private val CARD = 3L
    private val SALARY = 101L
    private val LIVING = 501L
    private val PAY_CARD = 801L

    private fun snapshot(
        plan: CardPaymentPlan,
        payCardPlan: Money = 0,
        minimum: Money? = null,
    ): FinanceSnapshot {
        val amounts: MonthlyAmounts = buildMap {
            put(PlanLine(SALARY), List(12) { 80_000L })
            put(PlanLine(LIVING, PaymentMethod.CREDIT_CARD), List(12) { 20_000L })
            if (payCardPlan > 0) put(PlanLine(PAY_CARD), List(12) { payCardPlan })
        }
        return FinanceSnapshot(
            today = today,
            accounts = listOf(
                Account(BANK, "銀行", AccountKind.BANK, balance = 100_000, balanceAsOf = today),
                Account(
                    CARD, "信用卡", AccountKind.CREDIT_CARD, balance = 60_000, balanceAsOf = today, creditLimit = 300_000,
                    statementDay = 1, paymentDueDay = 15,
                    card = CardTerms(revolvingRatePercent = 15.0, payAccountId = BANK, paymentPlan = plan),
                ),
            ),
            groups = listOf(PlanGroup(1, "收入", 1), PlanGroup(5, "生活", 5), PlanGroup(8, "繳款", 8)),
            items = listOfNotNull(
                PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, dueDay = 5),
                PlanItem(LIVING, "生活費", 5, FlowType.EXPENSE, flexibility = Flexibility.FLEXIBLE, tracking = TrackingMode.LEDGER),
                PlanItem(PAY_CARD, "繳信用卡", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, dueDay = 15)
                    .takeIf { payCardPlan > 0 },
            ),
            amountsByYear = mapOf(2026 to amounts, 2027 to amounts),
            actuals = emptyList(),
            ledger = emptyList(),
            cardStatements = minimum?.let { listOf(CardStatement(CARD, 2026, 8, 60_000, it)) }.orEmpty(),
            settings = AppSettings(safetyLevel = 0, horizonMonths = 24, autoPostFrom = today.toEpochDay(), transferAccountId = BANK),
        )
    }

    private fun summary(s: FinanceSnapshot) = PlanSummaryCalculator.summarize(s, 2026)

    @Test fun `全額繳清：每期繳掉上一期帳單，當期新刷的下一期才繳`() {
        val s = summary(snapshot(CardPaymentPlan.FULL))
        assertEquals(listOf(9, 10, 11, 12), s.autoMonths)
        //  9 月：繳結轉的 60,000，本月刷 20,000 留到下一期 → 月底欠 20,000
        // 10–12 月：每期繳上一期的 20,000，再刷 20,000 → 月底一直是 20,000
        assertEquals("60,000 ＋ 20,000×3", 120_000L, s.totalCardPayments)
        assertEquals("全額繳清不會有循環利息", 0L, s.totalCardInterest)
        assertEquals(60_000L, s.cardDebtStart)
        // 每期都把帳單繳掉 → 卡債歸 0；月底留著的 20,000 是當月新刷、還沒出帳的未到期卡款（R-CARD-28）
        assertEquals("全額繳清就沒有卡債", 0L, s.cardDebtEnd)
        assertEquals("永遠有一個月的刷卡還沒到期", 20_000L, s.cardUnpaidEnd)
        assertEquals(20_000L, s.cardNotDueEnd)
        assertEquals(listOf(60_000L, 20_000L, 20_000L, 20_000L), s.cardPayments.subList(8, 12))
    }

    @Test fun `照計畫編的金額：繳款看計畫那一列，沒繳完的部分計息`() {
        val s = summary(snapshot(CardPaymentPlan.PLANNED, payCardPlan = 30_000))
        //  9 月 (60,000−30,000)×1.25% = 375 → 60,000+375+20,000−30,000 = 50,375
        // 10 月 (50,375−30,000)×1.25% = 255 → 40,630
        // 11 月 (40,630−30,000)×1.25% = 133 → 30,763
        // 12 月 (30,763−30,000)×1.25% =  10 → 20,773
        assertEquals(773L, s.totalCardInterest)
        assertEquals("沒繳完的部分才是卡債", 773L, s.cardDebtEnd)
        assertEquals("未繳卡款＝773 ＋ 12 月刷的 20,000", 20_773L, s.cardUnpaidEnd)
        // 繳款金額要套還款上限（R-PAY-02），上限只有逐月滾動算得出來，所以和其他依帳單繳款的卡一樣
        // 只算「有編計畫、而且今天以後」的月份（R-PLS-05）：今天 9/1，所以是 9–12 月。
        assertEquals("30,000 × 4 期", 120_000L, s.totalCardPayments)
        assertEquals(listOf(30_000L, 30_000L, 30_000L, 30_000L), s.cardPayments.subList(8, 12))
        assertEquals("過去的月份不再推估", listOf(0L, 0L), s.cardPayments.subList(0, 2))
        assertEquals("逐卡列＝合計列（R-PLS-10）", s.cards.single().payments, s.totalCardPayments)
    }

    @Test fun `照計畫編的金額：試算在截止日那個月扣，金額看那個月編多少`() {
        val s = snapshot(CardPaymentPlan.PLANNED, payCardPlan = 30_000)
        val result = ScenarioApplier.run(BaselineBuilder.build(s), emptyList())
        // 9/15 截止：繳 9/1 帳單，帳單就是結轉的 60,000，計畫編 30,000 → 繳 30,000
        assertEquals(30_000L, result.periods.first { it.period == Period(2026, 9) }.cardPayments)
        assertEquals(30_000L, result.periods.first { it.period == Period(2026, 10) }.cardPayments)
        // 繳款不會超過這一期帳單還沒繳的部分（不會提早繳還沒出帳的刷卡）
        val small = ScenarioApplier.run(BaselineBuilder.build(snapshot(CardPaymentPlan.PLANNED, payCardPlan = 500_000)), emptyList())
        assertEquals(60_000L, small.periods.first { it.period == Period(2026, 9) }.cardPayments)
    }

    @Test fun `帳單最低：有輸入帳單就照最低繳；沒輸入就先當全額並提醒`() {
        val withBill = summary(snapshot(CardPaymentPlan.MINIMUM, minimum = 5_000))
        assertEquals("5,000 × 4 個月", 20_000L, withBill.totalCardPayments)
        assertTrue("繳最低會滾利息", withBill.totalCardInterest > 0)

        val noBill = snapshot(CardPaymentPlan.MINIMUM)
        assertEquals("先當全額繳清", 0L, summary(noBill).totalCardInterest)
        assertTrue(
            PlanValidator.validate(noBill, 2026).map { it.message }.toString(),
            PlanValidator.validate(noBill, 2026).any {
                it.message == "「信用卡」選了「帳單最低」，但還沒輸入帳單的最低應繳：目前先當成全額繳清試算"
            },
        )
    }

    @Test fun `照計畫編的金額但沒編：提醒去編，而且不會被當成「不計入」`() {
        val messages = PlanValidator.validate(snapshot(CardPaymentPlan.PLANNED), 2026).map { it.message }
        assertTrue(
            messages.toString(),
            messages.contains(
                "「信用卡」選了「照計畫編的金額」，但計畫裡沒有繳這張卡的項目：" +
                    "請新增一個轉帳項目（轉入「信用卡」）並編上每個月要繳多少",
            ),
        )
        // 編了之後：那一列就是繳款，不再出現「不計入」的提醒
        val planned = PlanValidator.validate(snapshot(CardPaymentPlan.PLANNED, payCardPlan = 30_000), 2026).map { it.message }
        assertTrue(planned.toString(), planned.none { it.contains("不計入") })
        assertTrue(planned.contains("「繳信用卡」就是「信用卡」每期要繳的金額；沒繳完的部分照利率計息"))
    }

    @Test fun `照紀錄推估仍然是預設：沒設定過就不用先決定怎麼繳（R-CARD-26）`() {
        val s = snapshot(CardPaymentPlan.AUTO)
        assertEquals(CardPaymentPlan.AUTO, s.account(CARD)!!.card!!.paymentPlan)
        // 沒有繳款紀錄也沒有帳單 → 先假設全額繳清，並提醒輸入帳單
        val assumption = CardRules.assumption(s, s.account(CARD)!!)
        assertEquals(CardRules.PaymentAssumption.Source.NO_RECORD, assumption.source)
        assertTrue(assumption.needsBill)
    }
}
