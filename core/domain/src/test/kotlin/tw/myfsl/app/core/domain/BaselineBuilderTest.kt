package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.Deferral
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.FOOD_CASH
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.LOAN
import tw.myfsl.app.core.sample.SampleHousehold.PAY_CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.sample.SampleHousehold.SUBSIDY
import java.time.LocalDate

class BaselineBuilderTest {

    private val sep1 = Period(2026, 9, Half.FIRST)
    private val sep2 = Period(2026, 9, Half.SECOND)
    private val oct1 = Period(2026, 10, Half.FIRST)
    private val snapshot = SampleHousehold.snapshot()

    private fun FlowEvent.key() = Triple(itemId, method, period)

    @Test fun `每張卡各自一個帳戶，沒指定卡片的刷卡歸預設卡片`() {
        val input = BaselineBuilder.build(snapshot)
        assertEquals(60_000L, input.accounts.single { it.id == CARD_A }.balance)
        assertEquals(45_000L, input.accounts.single { it.id == CARD_B }.balance)
        assertEquals(CASH, input.methodAccounts[PaymentMethod.CASH])
        assertEquals(BANK, input.methodAccounts[PaymentMethod.TRANSFER])
        assertEquals("預設卡片 = 排序第一張", CARD_A, input.methodAccounts[PaymentMethod.CREDIT_CARD])
        assertEquals(sep1, input.start)
        assertEquals(48, input.periodCount)

        val unassigned = BaselineBuilder.build(snapshot.copy(unassignedCardSpending = 800))
        assertEquals(60_800L, unassigned.accounts.single { it.id == CARD_A }.balance)
        val otherDefault = BaselineBuilder.build(snapshot.copy(unassignedCardSpending = 800, settings = snapshot.settings.copy(defaultCardId = CARD_B)))
        assertEquals(45_800L, otherDefault.accounts.single { it.id == CARD_B }.balance)
    }

    @Test fun `依記帳的項目本月扣掉已花費，已過的發生日移到今天所在的半月`() {
        val byKey = BaselineBuilder.build(snapshot).events.groupBy { it.key() }.mapValues { (_, list) -> list.sumOf { it.amount } }
        // 現金伙食 9,000 − 4,400 = 4,600，上下各半
        assertEquals(2_300L, byKey[Triple(FOOD_CASH, PaymentMethod.CASH, sep1)])
        assertEquals(2_300L, byKey[Triple(FOOD_CASH, PaymentMethod.CASH, sep2)])
        // 生活費 16,000 − 9,800 = 6,200，上下各半
        assertEquals(3_100L, byKey[Triple(LIVING, PaymentMethod.CREDIT_CARD, sep1)])
        assertEquals(3_100L, byKey[Triple(LIVING, PaymentMethod.CREDIT_CARD, sep2)])
        assertEquals("10 月沒有記帳，整月照計畫：現金伙食 9,000 上下各半", 4_500L, byKey[Triple(FOOD_CASH, PaymentMethod.CASH, oct1)])
        assertEquals("生活費 16,000 上下各半", 8_000L, byKey[Triple(LIVING, PaymentMethod.CREDIT_CARD, oct1)])
    }

    @Test fun `每月固定：起算日（今天）以前的視為已在餘額裡，只預測今天以後的發生日（手算：9／14）`() {
        val events = BaselineBuilder.build(snapshot).events
        // 手機網路：上半月、沒填日期 → 每月 1 號。9/1 已過（已入帳或包含在校正餘額），不再預測。
        assertTrue(events.none { it.itemId == PHONE && it.period.yearMonth == sep1.yearMonth })
        assertEquals(CARD_A, events.first { it.itemId == PHONE && it.period == oct1 }.fromAccountId)
        // 薪資：15 號 → 9/15 還沒到，放在上半月。
        val salary = events.single { it.itemId == SALARY && it.period.yearMonth == sep1.yearMonth }
        assertEquals(sep1, salary.period)
        assertEquals(BANK, salary.toAccountId)
        // 繳 B 卡（B 卡沒設循環條件）：下半月 → 16 號，照計畫轉帳。
        val payB = events.single { it.itemId == PAY_CARD_B && it.period.yearMonth == sep1.yearMonth }
        assertEquals(sep2, payB.period)
        assertEquals(CARD_B, payB.toAccountId)
        assertEquals(12_000L, events.single { it.itemId == CAR_SERVICE && it.period == sep2 }.amount)
    }

    @Test fun `已過的繳款日不再扣一次：貸款 5 號、今天 14 號，第一期從下個月開始`() {
        val paidOn5th = snapshot.copy(
            accounts = snapshot.accounts.map { if (it.id == LOAN) it.copy(loan = it.loan!!.copy(payDay = 5)) else it },
        )
        val loanEvents = BaselineBuilder.build(paidOn5th).events.filter { it.relatedAccountId == LOAN }
        assertTrue("9/5 已扣過，不能再出現在 9 月", loanEvents.none { it.period.yearMonth == sep1.yearMonth })
        assertEquals(oct1, loanEvents.minOf { it.period })

        // 原本 20 號：9/20 還沒到，第一期在 9 月下半月
        val loan = BaselineBuilder.build(snapshot).events.filter { it.relatedAccountId == LOAN && it.period == sep2 }
        assertEquals(10_757L, loan.single { it.kind == EventKind.TRANSFER }.amount)
        assertEquals(3_750L, loan.single { it.kind == EventKind.EXPENSE }.amount)
    }

    @Test fun `逐卡排程：依帳單繳款的 A 卡依自己的結帳日與截止日，B 卡沒有`() {
        val input = BaselineBuilder.build(snapshot)
        val cardA = input.events.filter { it.relatedAccountId == CARD_A && it.source == EventSource.CARD_SCHEDULE }
        // 9/15 截止（上半月）：自由繳 18,000；9/1 那期帳單 = 60,000 − 9/1 之後刷的 9,800 = 50,200
        assertEquals(18_000L, cardA.filter { it.period == sep1 }.single { it.kind == EventKind.TRANSFER }.amount)
        assertEquals(50_200L, input.openStatements[CARD_A])
        // 10/1 結帳：和 10/15 截止同一個半月，計息與結帳提前
        val oct1 = cardA.filter { it.period == Period(2026, 10, Half.FIRST) }
        oct1.single { it.interestRatePercent != null }.run { assertEquals(15.0, interestRatePercent!!, 0.0); assertTrue(statementEarly) }
        assertTrue(oct1.single { it.statementOf == CARD_A }.statementEarly)
        assertTrue(input.events.none { it.relatedAccountId == CARD_B && it.source == EventSource.CARD_SCHEDULE })
    }

    @Test fun `繳給依合約自動繳款的卡片：計畫轉帳不計，除非標成額外還款`() {
        val withPlan = snapshot.copy(
            items = snapshot.items + tw.myfsl.app.core.model.PlanItem(
                900, "繳信用卡 A", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD_A,
            ),
            amountsByYear = snapshot.amountsByYear.mapValues { (_, plan) -> plan + (PlanLine(900) to List(12) { 18_000L }) },
        )
        assertTrue(BaselineBuilder.build(withPlan).events.none { it.itemId == 900L })
        val extra = withPlan.copy(items = withPlan.items.map { if (it.id == 900L) it.copy(extraRepayment = true) else it })
        assertTrue(BaselineBuilder.build(extra).events.any { it.itemId == 900L })
    }

    @Test fun `今天在下半月：已過的都不再預測，依記帳的剩餘全部在下半月`() {
        val late = snapshot.copy(today = LocalDate.of(2026, 9, 20))
        val events = BaselineBuilder.build(late).events
        assertEquals(sep2, BaselineBuilder.build(late).start)
        assertTrue(events.none { it.itemId == SALARY && it.period.yearMonth == sep1.yearMonth })
        assertEquals(4_600L, events.filter { it.itemId == FOOD_CASH && it.method == PaymentMethod.CASH && it.period == sep2 }.sumOf { it.amount })
    }

    @Test fun `到期確認：已完成移除當月；延期改由延期款放到到期月份`() {
        val done = snapshot.copy(actuals = listOf(ItemActual(CAR_SERVICE, 2026, 9, ActualStatus.DONE, snapshot.today)))
        val doneEvents = BaselineBuilder.build(done).events
        assertTrue(doneEvents.none { it.itemId == CAR_SERVICE && it.period.yearMonth == sep1.yearMonth })
        assertTrue("明年同月不受影響", doneEvents.any { it.itemId == CAR_SERVICE && it.period == Period(2027, 9, Half.SECOND) })

        val postponed = snapshot.copy(
            actuals = listOf(ItemActual(SUBSIDY, 2026, 9, ActualStatus.POSTPONED, snapshot.today)),
            deferrals = listOf(Deferral(1, SUBSIDY, null, 2026, 9, 2026, 10, 5_000)),
        )
        val events = BaselineBuilder.build(postponed).events
        assertTrue(events.none { it.itemId == SUBSIDY && it.period.yearMonth == sep1.yearMonth })
        val deferred = events.single { it.itemId == SUBSIDY && it.source == EventSource.DEFERRAL }
        assertEquals(oct1, deferred.period)
        assertEquals(5_000L, deferred.amount)

        // 已經過期還沒付的延期款放在本期；付清的不再出現
        val overdue = postponed.copy(deferrals = listOf(Deferral(1, SUBSIDY, null, 2026, 7, 2026, 8, 5_000)))
        assertEquals(sep1, BaselineBuilder.build(overdue).events.single { it.source == EventSource.DEFERRAL }.period)
        val settled = postponed.copy(deferrals = postponed.deferrals.map { it.copy(settled = true) })
        assertTrue(BaselineBuilder.build(settled).events.none { it.source == EventSource.DEFERRAL })
    }

    @Test fun `實際金額就是當月這個項目記帳的合計，不分支付方式`() {
        val view = ActualCalculator.actualFor(FOOD_CASH, 2026, 9, snapshot.actuals, snapshot.ledger)
        assertEquals("3,775 + 120（漏記差額）+ 420 + 85", 4_400L, view.amount)
        assertTrue(view.reported)
        assertEquals(null, view.status)

        val other = snapshot.copy(
            ledger = snapshot.ledger + LedgerEntry(
                date = LocalDate.of(2026, 8, 30), type = FlowType.EXPENSE, amount = 999,
                itemId = FOOD_CASH, method = PaymentMethod.CASH, accountId = CASH,
            ),
        )
        assertEquals("8/30 那筆算在 8 月", 4_400L, ActualCalculator.actualFor(FOOD_CASH, 2026, 9, other.actuals, other.ledger).amount)
        assertEquals("生活費只剩刷卡的 9,650 + 150", 9_800L, ActualCalculator.actualFor(LIVING, 2026, 9, snapshot.actuals, snapshot.ledger).amount)

        val untouched = ActualCalculator.actualFor(PHONE, 2026, 9, snapshot.actuals, snapshot.ledger)
        assertEquals(0L, untouched.amount)
        assertFalse(untouched.reported)
    }

    @Test fun `記完帳後基準線只留下本月剩下的額度`() {
        val extra = snapshot.copy(
            ledger = snapshot.ledger + LedgerEntry(
                date = snapshot.today, type = FlowType.EXPENSE, amount = 600,
                itemId = FOOD_CASH, method = PaymentMethod.CASH, accountId = CASH,
            ),
        )
        // 9,000 − 4,400 − 600 = 4,000，上下各半
        val events = BaselineBuilder.build(extra).events.filter { it.itemId == FOOD_CASH && it.method == PaymentMethod.CASH }
        assertEquals(2_000L, events.single { it.period == sep1 }.amount)
        assertEquals(2_000L, events.single { it.period == sep2 }.amount)
    }

    @Test fun `沒有計畫的年度沿用前一年`() {
        assertEquals(65_000L, snapshot.planAmount(PlanLine(SALARY), 2031, 3))
        assertEquals(65_000L, snapshot.planAmount(PlanLine(SALARY), 2020, 3))
    }

    @Test fun `封存只影響封存月份之後`() {
        // 10 月起封存手機網路：9 月的計畫仍在歷史裡，10 月起不再預測
        val archived = snapshot.copy(
            items = snapshot.items.map { if (it.id == PHONE) it.copy(archived = true, archivedFrom = 2026 * 12 + 9) else it },
        )
        assertTrue(archived.isItemActiveIn(archived.item(PHONE)!!, 2026, 9))
        assertFalse(archived.isItemActiveIn(archived.item(PHONE)!!, 2026, 10))
        assertTrue(BaselineBuilder.build(archived).events.none { it.itemId == PHONE })
        val before = PlanSummaryCalculator.summarize(snapshot, 2026).cardSpending.sum()
        val after = PlanSummaryCalculator.summarize(archived, 2026).cardSpending.sum()
        assertEquals("10–12 月的 3 個月不算", 2_400L * 3, before - after)
    }
}
