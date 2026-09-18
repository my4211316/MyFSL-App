package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.PAY_CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.sample.SampleHousehold.SUBSIDY
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BaselineBuilderTest {

    private val sep1 = Period(2026, 9, Half.FIRST)
    private val sep2 = Period(2026, 9, Half.SECOND)
    private val snapshot = SampleHousehold.snapshot()
    private val pool = CashFlowEngine.CARD_POOL_ID

    private fun FlowEvent.key() = Triple(itemId, method, period)

    @Test fun `信用卡合併成一個合計，支付方式對應扣款帳戶`() {
        val input = BaselineBuilder.build(SampleHousehold.snapshot())
        val card = input.accounts.single { it.id == pool }
        assertEquals(105_000L, card.balance)
        assertTrue(input.accounts.none { it.id == SampleHousehold.CARD_A || it.id == SampleHousehold.CARD_B })
        assertEquals(CASH, input.methodAccounts[PaymentMethod.CASH])
        assertEquals(BANK, input.methodAccounts[PaymentMethod.TRANSFER])
        assertEquals(pool, input.methodAccounts[PaymentMethod.CREDIT_CARD])
        assertEquals(sep1, input.start)
        assertEquals(48, input.periodCount)
    }

    @Test fun `當月回報型項目扣掉已花費，剩下的依時點拆到上下半月`() {
        val events = BaselineBuilder.build(SampleHousehold.snapshot()).events
        val byKey = events.groupBy { it.key() }.mapValues { (_, list) -> list.sumOf { it.amount } }
        assertEquals(2_300L, byKey[Triple(LIVING, PaymentMethod.CASH, sep1)])
        assertEquals(2_300L, byKey[Triple(LIVING, PaymentMethod.CASH, sep2)])
        assertEquals(3_100L, byKey[Triple(LIVING, PaymentMethod.CREDIT_CARD, sep1)])
        assertEquals(3_100L, byKey[Triple(LIVING, PaymentMethod.CREDIT_CARD, sep2)])
        // 下個月回到完整計畫
        assertEquals(4_500L, byKey[Triple(LIVING, PaymentMethod.CASH, Period(2026, 10, Half.FIRST))])
    }

    @Test fun `支出事件使用支付方式的帳戶，轉帳到信用卡改成信用卡合計`() {
        val events = BaselineBuilder.build(SampleHousehold.snapshot()).events
        val phone = events.first { it.itemId == PHONE && it.period == sep1 }
        assertEquals(pool, phone.fromAccountId)
        val salary = events.first { it.itemId == SALARY && it.period == sep1 }
        assertEquals(BANK, salary.toAccountId)
        val payA = events.first { it.itemId == PAY_CARD_A && it.period == sep1 }
        assertEquals(BANK, payA.fromAccountId)
        assertEquals(pool, payA.toAccountId)
        val service = events.first { it.itemId == CAR_SERVICE && it.period == sep2 }
        assertEquals(12_000L, service.amount)
    }

    @Test fun `貸款依攤還條件產生本金與利息，第一期在繳款日所在半月`() {
        val events = BaselineBuilder.build(SampleHousehold.snapshot()).events
        val loan = events.filter { it.relatedAccountId == SampleHousehold.LOAN && it.period == sep2 }
        assertEquals(10_757L, loan.single { it.kind == EventKind.TRANSFER }.amount)
        assertEquals(3_750L, loan.single { it.kind == EventKind.EXPENSE }.amount)
        assertTrue(events.none { it.relatedAccountId == SampleHousehold.LOAN && it.period == sep1 })
    }

    @Test fun `今天在下半月：上半月的自動項目視為已發生，回報型剩餘全部放在下半月`() {
        val snapshot = SampleHousehold.snapshot().copy(today = LocalDate.of(2026, 9, 20))
        val events = BaselineBuilder.build(snapshot).events
        assertEquals(sep2, BaselineBuilder.build(snapshot).start)
        assertTrue(events.none { it.itemId == SALARY && it.period.yearMonth == sep1.yearMonth })
        assertEquals(4_600L, events.filter { it.itemId == LIVING && it.method == PaymentMethod.CASH && it.period == sep2 }.sumOf { it.amount })
    }

    @Test fun `到期確認：已完成移除當月、延到下月則移到下個月`() {
        val done = SampleHousehold.snapshot().let {
            it.copy(actuals = it.actuals + ItemActual(CAR_SERVICE, PaymentMethod.CREDIT_CARD, 2026, 9, ActualStatus.DONE, it.today))
        }
        val doneEvents = BaselineBuilder.build(done).events
        assertTrue(doneEvents.none { it.itemId == CAR_SERVICE && it.period.yearMonth == sep1.yearMonth })
        assertTrue("明年同月不受影響", doneEvents.any { it.itemId == CAR_SERVICE && it.period == Period(2027, 9, Half.SECOND) })

        val postponed = SampleHousehold.snapshot().let {
            it.copy(actuals = it.actuals + ItemActual(SUBSIDY, null, 2026, 9, ActualStatus.POSTPONED, it.today))
        }
        val events = BaselineBuilder.build(postponed).events
        assertTrue(events.none { it.itemId == SUBSIDY && it.period.month == 9 && it.period.year == 2026 })
        assertEquals(5_000L, events.filter { it.itemId == SUBSIDY && it.period == Period(2026, 10, Half.FIRST) }.sumOf { it.amount })
    }

    @Test fun `實際金額就是當月該列記帳的合計`() {
        val line = PlanLine(LIVING, PaymentMethod.CASH)
        val view = ActualCalculator.actualFor(line, 2026, 9, snapshot.actuals, snapshot.ledger)
        assertEquals("3,775 + 120（漏記差額）+ 420 + 85", 4_400L, view.amount)
        assertTrue(view.reported)
        assertEquals(null, view.status)

        // 別的月份不算進來
        val other = snapshot.copy(
            ledger = snapshot.ledger + LedgerEntry(
                date = LocalDate.of(2026, 8, 30), type = FlowType.EXPENSE, amount = 999,
                itemId = LIVING, method = PaymentMethod.CASH, accountId = CASH,
            ),
        )
        assertEquals(4_400L, ActualCalculator.actualFor(line, 2026, 9, other.actuals, other.ledger).amount)

        // 沒有記帳也沒有確認過的列
        val untouched = ActualCalculator
            .actualFor(PlanLine(SampleHousehold.PHONE, PaymentMethod.CREDIT_CARD), 2026, 9, snapshot.actuals, snapshot.ledger)
        assertEquals(0L, untouched.amount)
        assertFalse(untouched.reported)
    }

    @Test fun `記完帳後基準線只留下本月剩下的額度`() {
        val extra = snapshot.copy(
            ledger = snapshot.ledger + LedgerEntry(
                date = snapshot.today, type = FlowType.EXPENSE, amount = 600,
                itemId = LIVING, method = PaymentMethod.CASH, accountId = CASH,
            ),
        )
        // 9,000 − 5,000 = 4,000，平分到上下半月
        val events = BaselineBuilder.build(extra).events.filter { it.itemId == LIVING && it.method == PaymentMethod.CASH }
        assertEquals(2_000L, events.single { it.period == sep1 }.amount)
        assertEquals(2_000L, events.single { it.period == sep2 }.amount)
    }

    @Test fun `沒有計畫的年度沿用前一年`() {
        val snapshot = SampleHousehold.snapshot()
        assertEquals(65_000L, snapshot.planAmount(PlanLine(SALARY), 2031, 3))
        assertEquals(65_000L, snapshot.planAmount(PlanLine(SALARY), 2020, 3))
    }

    @Test fun `未指定卡片的刷卡加進信用卡合計`() {
        val snapshot = SampleHousehold.snapshot().copy(unassignedCardSpending = 800)
        assertEquals(105_800L, BaselineBuilder.build(snapshot).accounts.single { it.id == pool }.balance)
    }
}
