package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ScenarioChange
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 信用卡循環利息與最低應繳。
 * 用獨立的示意資料（不是設計稿那份），所以設計稿的黃金數字不受影響。
 */
class CardInterestTest {

    private val today = LocalDate.of(2026, 9, 1)
    private val sep1 = Period(2026, 9, Half.FIRST)
    private val sep2 = Period(2026, 9, Half.SECOND)
    private val oct1 = Period(2026, 10, Half.FIRST)

    private val BANK = 1L
    private val CARD = 3L
    private val SALARY = 101L
    private val LIVING = 501L
    private val PAY_CARD = 801L

    private fun terms(
        rate: Double = 15.0,
        percent: Double = 10.0,
        floor: Money = 1_000,
        mode: CardPayMode = CardPayMode.MINIMUM,
        fixed: Money? = null,
    ) = CardTerms(rate, percent, floor, mode, fixed, payAccountId = BANK, payDay = 15)

    private fun snapshot(
        cardTerms: CardTerms? = terms(),
        cardDebt: Money = 400_000,
        payCardPlan: Money = 0,
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
                Account(CARD, "信用卡", AccountKind.CREDIT_CARD, balance = cardDebt, balanceAsOf = today, creditLimit = 500_000, card = cardTerms),
            ),
            groups = listOf(PlanGroup(1, "收入", 1), PlanGroup(5, "生活", 5), PlanGroup(8, "繳款", 8)),
            items = listOfNotNull(
                PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, timing = Timing.FIRST_HALF, dueDay = 5),
                PlanItem(LIVING, "生活費", 5, FlowType.EXPENSE, timing = Timing.SPLIT, flexibility = Flexibility.FLEXIBLE, tracking = TrackingMode.LEDGER),
                PlanItem(PAY_CARD, "繳信用卡", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, timing = Timing.FIRST_HALF, dueDay = 5)
                    .takeIf { payCardPlan > 0 },
            ),
            amountsByYear = mapOf(2026 to amounts, 2027 to amounts, 2028 to amounts),
            actuals = emptyList(),
            ledger = emptyList(),
            settings = tw.myfsl.app.core.model.AppSettings(safetyLevel = 0, horizonMonths = 24),
        )
    }

    // ---------- 規則 ----------

    @Test fun `循環利息：餘額 × 年利率 ÷ 12`() {
        assertEquals(5_000L, CardRules.monthlyInterest(400_000, 15.0))
        assertEquals(1_063L, CardRules.monthlyInterest(85_000, 15.0))
        assertEquals(0L, CardRules.monthlyInterest(0, 15.0))
        assertEquals(0L, CardRules.monthlyInterest(-5_000, 15.0))
        assertEquals(0L, CardRules.monthlyInterest(400_000, 0.0))
    }

    @Test fun `最低應繳：比例、下限與當期利息取大，最多為全部欠款`() {
        val t = terms()
        assertEquals(40_000L, CardRules.minimumPayment(400_000, t))
        assertEquals("比例算出來太小時用下限", 1_000L, CardRules.minimumPayment(5_000, t))
        assertEquals("欠款比下限還少時只繳欠款", 500L, CardRules.minimumPayment(500, t))
        assertEquals(0L, CardRules.minimumPayment(0, t))
        // 比例只有 1% 時，最低應繳至少要付掉當期利息
        assertEquals(5_000L, CardRules.minimumPayment(400_000, terms(percent = 1.0)))
    }

    @Test fun `逐卡計算最低應繳：不能合併成一張卡再算（審閱第 7 點的算例）`() {
        // A 欠 500、下限 1,000 → 最多繳 500；B 欠 100,000、10% → 10,000。逐卡合計 10,500。
        val a = terms(rate = 0.0, floor = 1_000)
        val b = terms(rate = 0.0, floor = 1_000)
        assertEquals(500L, CardRules.minimumPayment(500, a))
        assertEquals(10_000L, CardRules.minimumPayment(100_000, b))
        // 合併成 100,500 × 10% 會變成 10,050，是錯的
        assertEquals(10_050L, CardRules.minimumPayment(100_500, b))

        val snapshot = snapshot(cardTerms = null).let { base ->
            base.copy(
                accounts = base.accounts.filter { it.kind != AccountKind.CREDIT_CARD } + listOf(
                    Account(3, "A", AccountKind.CREDIT_CARD, balance = 500, card = a, sortOrder = 1),
                    Account(4, "B", AccountKind.CREDIT_CARD, balance = 100_000, card = b, sortOrder = 2),
                ),
                amountsByYear = emptyMap(),
            )
        }
        val p1 = ScenarioApplier.run(BaselineBuilder.build(snapshot), emptyList()).periods.first { it.period == sep1 }
        assertEquals("逐卡繳款合計", 10_500L, p1.cardPayments)
        assertEquals(0L, p1.balances[3])
        assertEquals(90_000L, p1.balances[4])
    }

    @Test fun `只清償其中一張卡：另一張的欠款與條件照舊`() {
        val snapshot = snapshot(cardTerms = null).let { base ->
            base.copy(
                accounts = base.accounts.filter { it.kind != AccountKind.CREDIT_CARD } + listOf(
                    Account(3, "A", AccountKind.CREDIT_CARD, balance = 60_000, card = terms(rate = 15.0), sortOrder = 1),
                    Account(4, "B", AccountKind.CREDIT_CARD, balance = 40_000, card = terms(rate = 12.0), sortOrder = 2),
                ),
                amountsByYear = emptyMap(),
            )
        }
        val result = ScenarioApplier.run(BaselineBuilder.build(snapshot), listOf(ScenarioChange.PayOffDebts(listOf(3L), BANK, sep1.index)))
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals("A 清掉", 0L, p1.balances[3])
        // B：利息 400（40,000 × 12% ÷ 12），最低應繳 max(1,000, 40,400 × 10% = 4,040, 400) = 4,040
        assertEquals(40_000L + 400 - 4_040, p1.balances[4])
        // A 清償時停掉它的排程（含計息），以當時欠款 60,000 清償
        assertEquals(60_000L, p1.debtPayoff)
    }

    @Test fun `卡債會往哪裡走：繳款先付利息，剩下才還本金`() {
        val t = terms()
        CardRules.outlook(400_000, t, monthlySpending = 20_000, payment = 40_000).run {
            assertEquals(5_000L, interest)
            assertEquals(35_000L, principalRepaid)
            assertEquals(-15_000L, change)
            assertFalse(growing)
            assertNull(CardRules.warning(this))
        }
        CardRules.outlook(400_000, t, monthlySpending = 20_000, payment = 20_000).run {
            assertEquals(5_000L, change)
            assertTrue(growing)
            assertEquals(5_000L, extraToStop)
            assertEquals(
                "每月刷 $20,000、利息 $5,000，繳 $20,000 不夠；每月至少要多繳 $5,000 卡債才不會再增加",
                CardRules.warning(this),
            )
        }
        // 多繳 extraToStop 剛好持平（不是下降）
        CardRules.outlook(400_000, t, monthlySpending = 20_000, payment = 25_000).run {
            assertEquals(0L, change)
            assertFalse(growing)
        }
        CardRules.outlook(400_000, t, monthlySpending = 20_000, payment = 3_000).run {
            assertEquals("繳款不夠付利息時，還到本金是 0（不是負數）", 0L, principalRepaid)
            assertEquals(2_000L, unpaidInterest)
            assertEquals(22_000L, change)
            assertEquals(
                "繳的錢還不夠付循環利息 $5,000，卡債只會變多；每月至少要多繳 $22,000 卡債才不會再增加",
                CardRules.warning(this),
            )
        }
        // 沒有指定繳款金額時，依繳款方式推算
        assertEquals(40_000L, CardRules.outlook(400_000, t, 20_000).payment)
        assertEquals(405_000L, CardRules.outlook(400_000, terms(mode = CardPayMode.FULL), 20_000).payment)
        assertEquals(40_200L, CardRules.outlook(400_000, terms(mode = CardPayMode.FIXED, fixed = 40_200), 20_000).payment)
    }

    @Test fun `只繳最低應繳、不再刷卡時要多久才還完`() {
        assertEquals(10, CardRules.monthsToClear(10_000, terms(rate = 0.0)))
        assertNull("一邊還一邊刷就還不完", CardRules.monthsToClear(400_000, terms(), monthlySpending = 20_000))
        assertNull("固定繳款少於利息就永遠還不完", CardRules.monthsToClear(400_000, terms(mode = CardPayMode.FIXED, fixed = 3_000)))
        assertEquals(1, CardRules.monthsToClear(400_000, terms(mode = CardPayMode.FULL)))
    }

    // ---------- 引擎 ----------

    @Test fun `最低應繳：先計息、再繳款，卡債逐月下降`() {
        val result = ScenarioApplier.run(BaselineBuilder.build(snapshot()), emptyList())
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals(5_000L, p1.cardInterest)
        assertEquals("利息不算刷卡消費", 10_000L, p1.cardSpending)
        assertEquals(40_500L, p1.cardPayments)
        assertEquals("400,000 + 5,000 − 40,500 + 10,000", 374_500L, p1.cardDebtEnd)
        assertEquals(100_000L, p1.liquidStart)
        assertEquals(40_500L, p1.liquidOut)
        assertEquals(59_500L, p1.liquidLow)
        assertEquals(139_500L, p1.liquidEnd)

        val p2 = result.periods.first { it.period == sep2 }
        assertEquals("利息與繳款只在繳款日所在的半月", 0L, p2.cardInterest)
        assertEquals(0L, p2.cardPayments)
        assertEquals(384_500L, p2.cardDebtEnd)

        val p3 = result.periods.first { it.period == oct1 }
        assertEquals(4_806L, p3.cardInterest)
        assertEquals(38_931L, p3.cardPayments)
        assertEquals(360_375L, p3.cardDebtEnd)

        assertTrue("兩年後卡債比現在少", result.endCardDebt < 400_000)
        assertTrue("利息有被算成支出", result.totalCardInterest > 0)
        assertEquals(result.totalCardInterest, result.periods.sumOf { it.cardInterest })
    }

    @Test fun `固定繳款低於利息：卡債會一直長大`() {
        val result = ScenarioApplier.run(
            BaselineBuilder.build(snapshot(cardTerms = terms(mode = CardPayMode.FIXED, fixed = 3_000))),
            emptyList(),
        )
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals(3_000L, p1.cardPayments)
        assertEquals(412_000L, p1.cardDebtEnd)
        assertTrue(result.endCardDebt > 400_000)
    }

    @Test fun `當期全額：連利息一起繳掉，只剩當期新刷的`() {
        val result = ScenarioApplier.run(BaselineBuilder.build(snapshot(cardTerms = terms(mode = CardPayMode.FULL))), emptyList())
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals(405_000L, p1.debtPayoff)
        assertEquals(0L, p1.cardPayments)
        assertEquals("清掉之後同一半月刷的 10,000 成為新卡債", 10_000L, p1.cardDebtEnd)
        assertEquals(-305_000L, p1.liquidLow)
    }

    @Test fun `沒有設循環條件的卡：不計息，繳款完全看計畫`() {
        val result = ScenarioApplier.run(BaselineBuilder.build(snapshot(cardTerms = null, payCardPlan = 18_000)), emptyList())
        val p1 = result.periods.first { it.period == sep1 }
        assertEquals(0L, p1.cardInterest)
        assertEquals(18_000L, p1.cardPayments)
        assertEquals(392_000L, p1.cardDebtEnd)
    }

    @Test fun `情境：借貸款清掉卡債之後就不再產生循環利息`() {
        val base = BaselineBuilder.build(snapshot())
        val payoff = ScenarioApplier.run(
            base,
            listOf(
                ScenarioChange.AddLoan("整合貸款", 420_000, 6.5, 60, tw.myfsl.app.core.model.RepaymentMethod.EQUAL_PAYMENT, sep1.index, BANK, BANK, Half.SECOND),
                ScenarioChange.PayOffDebts(listOf(CARD), BANK, sep1.index),
                ScenarioChange.ChangeMethod(listOf(LIVING), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, sep1.index),
            ),
        )
        val current = ScenarioApplier.run(base, emptyList())
        assertEquals(0L, payoff.endCardDebt)
        assertEquals("清掉後不再有循環利息", 0L, payoff.periods.drop(1).sumOf { it.cardInterest })
        assertTrue("整合後付的利息比繼續循環少", payoff.totalCardInterest < current.totalCardInterest)
    }

    // ---------- 年度計畫表 ----------

    @Test fun `計畫表：預估循環利息、卡債變化與結構缺口`() {
        val summary = PlanSummaryCalculator.summarize(snapshot(), 2026)
        assertEquals(60_000L, summary.totalCardInterest)
        assertEquals(5_000L, summary.cardInterest[0])
        assertEquals(240_000L, summary.totalCardSpending)
        assertEquals("有循環條件的卡依合約（最低應繳）估算", 480_000L, summary.totalCardPayments)
        assertEquals("刷卡 240,000 ＋ 利息 60,000 − 繳款 480,000", -180_000L, summary.cardDebtIncrease)
        assertEquals(-15_000L, summary.cardDebtChange(9))
        assertEquals("960,000 − 240,000 − 0 − 60,000", 660_000L, summary.structuralGap)
    }

    @Test fun `計畫檢查：依合約繳款的卡，計畫轉帳不計；固定繳款不夠付利息會提醒`() {
        val both = snapshot(payCardPlan = 18_000)
        val messages = PlanValidator.validate(both, 2026).map { it.message }
        assertTrue(messages.any { it == "「繳信用卡」不計入：「信用卡」已依合約自動繳款。若這是額外還款，請在項目勾選「額外還款」" })
        val summary = PlanSummaryCalculator.summarize(both, 2026)
        assertEquals("計畫的 18,000 不重複算，只算合約的最低應繳", 480_000L, summary.totalCardPayments)

        val fixed = snapshot(cardTerms = terms(mode = CardPayMode.FIXED, fixed = 18_000))
        assertTrue(
            PlanValidator.validate(fixed, 2026).map { it.message }
                .any { it == "「信用卡」每月刷 $20,000、利息 $5,000，繳 $18,000 不夠；每月至少要多繳 $7,000 卡債才不會再增加" },
        )
        // 最低應繳 40,000 夠多就不再提醒
        assertTrue(PlanValidator.validate(snapshot(), 2026).none { it.message.contains("才不會再增加") })
    }

    // ---------- 帳戶總覽 ----------

    @Test fun `帳戶總覽：每張卡顯示當期利息與最低應繳`() {
        val overview = AccountSummaryCalculator.overview(snapshot(), today)
        val card = overview.cards.single()
        assertEquals(5_000L, card.interest)
        assertEquals(40_000L, card.minimumPayment)
        assertEquals(5_000L, overview.cardInterest)
        // 沒設循環條件時不顯示
        val plain = AccountSummaryCalculator.overview(snapshot(cardTerms = null), today).cards.single()
        assertEquals(0L, plain.interest)
        assertNull(plain.minimumPayment)
    }
}
