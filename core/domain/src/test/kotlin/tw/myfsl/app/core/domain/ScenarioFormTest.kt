package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.TRIP
import tw.myfsl.app.core.model.CheckIn
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScenarioFormTest {

    private val snapshot = SampleHousehold.snapshot()
    private val oct = Period(2026, 10, Half.FIRST)

    @Test fun `月份偏移：本月下半月時 0 個月後就是今天這一期`() {
        assertEquals(oct.index, ScenarioForm.indexFor(LocalDate.of(2026, 9, 14), 1))
        assertEquals(Period(2026, 9, Half.FIRST).index, ScenarioForm.indexFor(LocalDate.of(2026, 9, 14), 0))
        assertEquals(Period(2026, 9, Half.SECOND).index, ScenarioForm.indexFor(LocalDate.of(2026, 9, 20), 0))
        assertEquals(1, ScenarioForm.offsetFor(LocalDate.of(2026, 9, 14), oct.index))
    }

    @Test fun `貸款整合卡債：存成新增貸款加清償兩個變動，算出來和驗收數字一樣`() {
        val consolidate = ScenarioForm.newChange(ChangeKind.CONSOLIDATE, snapshot)
            .copy(amount = "200,000", rate = "6.5", months = "60", payHalf = Half.SECOND)
        assertEquals("預設清償所有信用卡", setOf(CARD_A, CARD_B), consolidate.debtAccountIds)
        assertEquals(BANK, consolidate.depositAccountId)
        val method = ScenarioForm.newChange(ChangeKind.METHOD, snapshot)
            .copy(itemIds = setOf(LIVING, FUEL, PHONE, CAR_SERVICE, TRIP))
        val result = ScenarioForm.validate(ScenarioDraft(name = "整合卡債", changes = listOf(consolidate, method)), snapshot)
        assertTrue(result.errors.toString(), result.ok)
        val changes = result.scenario!!.changes
        assertEquals(
            listOf(
                ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK, Half.SECOND),
                ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index),
                ScenarioChange.ChangeMethod(listOf(LIVING, FUEL, PHONE, CAR_SERVICE, TRIP).sorted(), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, oct.index),
            ),
            changes,
        )
        val comparison = ForecastComparisonCalculator.compare(snapshot, listOf(result.scenario), 24)
        assertEquals(2_880L, comparison.outcomes[1].lowest)
        assertEquals(0L, comparison.outcomes[1].endCardDebt)
        assertEquals(-48_639L, comparison.outcomes[0].lowest)
        assertEquals(2_880L, comparison.bestLowest)
    }

    @Test fun `存回草稿：新增貸款與同期清償合併回貸款整合`() {
        val consolidate = ScenarioForm.newChange(ChangeKind.CONSOLIDATE, snapshot).copy(amount = "200000", rate = "6.5")
        val cut = ScenarioForm.newChange(ChangeKind.CUT, snapshot).copy(percent = "-20", monthOffset = 3)
        val scenario = ScenarioForm.validate(ScenarioDraft(name = "x", changes = listOf(consolidate, cut)), snapshot).scenario!!
        val draft = ScenarioForm.fromScenario(scenario, snapshot.today)
        assertEquals(listOf(ChangeKind.CONSOLIDATE, ChangeKind.CUT), draft.changes.map { it.kind })
        assertEquals(scenario, ScenarioForm.validate(draft, snapshot).scenario)
    }

    @Test fun `必填錯誤`() {
        val empty = ScenarioForm.validate(ScenarioDraft(), snapshot)
        assertEquals("請輸入情境名稱", empty.errors[ScenarioForm.Field.NAME])
        assertEquals("至少加一個變動", empty.errors[ScenarioForm.Field.CHANGES])

        val loan = ScenarioForm.newChange(ChangeKind.CONSOLIDATE, snapshot).copy(amount = "", rate = "abc", months = "0", debtAccountIds = emptySet())
        val result = ScenarioForm.validate(ScenarioDraft(name = "x", changes = listOf(loan)), snapshot)
        assertFalse(result.ok)
        assertEquals("請輸入貸款金額", result.errors[ScenarioForm.Field.of(0, "amount")])
        assertEquals("年利率要在 0 到 100 之間", result.errors[ScenarioForm.Field.of(0, "rate")])
        assertEquals("期數要在 1 到 480 之間", result.errors[ScenarioForm.Field.of(0, "months")])
        assertEquals("至少選一個要清償的負債", result.errors[ScenarioForm.Field.of(0, "debts")])

        val method = ScenarioForm.newChange(ChangeKind.METHOD, snapshot).copy(toMethod = PaymentMethod.CREDIT_CARD)
        assertEquals(
            "原本與改成的支付方式不能一樣",
            ScenarioForm.validate(ScenarioDraft(name = "x", changes = listOf(method)), snapshot).errors[ScenarioForm.Field.of(0, "method")],
        )
    }

    @Test fun `一次性收入要入帳帳戶；支出用支付方式`() {
        val income = ChangeDraft(ChangeKind.ONE_OFF, monthOffset = 2, name = "賣車", amount = "300000", oneOffType = FlowType.INCOME, depositAccountId = BANK)
        val expense = ChangeDraft(ChangeKind.ONE_OFF, monthOffset = 2, name = "修車", amount = "20000", oneOffMethod = PaymentMethod.CREDIT_CARD)
        val result = ScenarioForm.validate(ScenarioDraft(name = "x", changes = listOf(income, expense)), snapshot)
        assertTrue(result.ok)
        val (a, b) = result.scenario!!.changes.map { it as ScenarioChange.OneOff }
        assertEquals(BANK, a.accountId)
        assertNull(a.method)
        assertEquals(PaymentMethod.CREDIT_CARD, b.method)
    }

    @Test fun `反推：可調支出要減多少才不低於安全線`() {
        val flexible = snapshot.activeItems.filter { it.flexibility == tw.myfsl.app.core.model.Flexibility.FLEXIBLE }.map { it.id }.toSet()
        val seek = ForecastComparisonCalculator.seek(snapshot, 24, flexible, GoalTarget.MinLiquid(30_000), fromMonthOffset = 0)
        assertTrue(seek.achievable)
        assertTrue(seek.cutPercent > 0 && seek.cutPercent <= 100)
        assertTrue(seek.result.lowestLiquid >= 30_000)
    }

    @Test fun `本期：檢查提醒、水位、到期清單`() {
        val overview = PeriodOverviewCalculator.build(snapshot.copy(lastCheckIn = CheckIn(date = LocalDate.of(2026, 9, 7))))
        assertEquals(7L, overview.checkIn.daysSinceLast)
        assertTrue(overview.checkIn.due)
        assertEquals("本月漏記 1 筆 · $120", overview.checkIn.missedLabel)
        assertEquals(-48_639L, overview.lowest)
        assertEquals(24, overview.monthlyLows.size)
        assertEquals("9/14 ÷ 30 天", 47, overview.timePercent)
        assertTrue("到期清單不含刷卡消費", overview.upcoming.none { it.label.contains("生活費") })
        assertTrue(overview.upcoming.isNotEmpty())
        assertTrue(overview.upcoming.zipWithNext().all { (a, b) -> a.period.index <= b.period.index })

        val never = PeriodOverviewCalculator.build(snapshot.copy(lastCheckIn = null))
        assertNull(never.checkIn.daysSinceLast)
        assertTrue(never.checkIn.text.startsWith("還沒檢查過"))
    }
}
