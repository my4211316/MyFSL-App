package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPaymentPlan
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import java.time.LocalDate

/**
 * 「照計畫編的金額」（R-CARD-27）的三個回歸案例（外部審閱 V37-01–03）。
 * 卡片 1 日結帳、15 日截止（同月），年利率 15%（月 1.25%）；今天 2026-09-14、起算日同一天。
 */
class CardPlannedPaymentTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val BANK = 1L
    private val CARD = 2L
    private val SALARY = 101L
    private val PAY_CARD = 801L

    /** [pay] 是「繳卡」項目 12 個月的金額。 */
    private fun snapshot(debt: Money, pay: List<Money>, pay2027: List<Money> = pay): FinanceSnapshot {
        fun amounts(p: List<Money>): MonthlyAmounts = mapOf(
            PlanLine(SALARY) to List(12) { 100_000L },
            PlanLine(PAY_CARD) to p,
        )
        return FinanceSnapshot(
            today = today,
            accounts = listOf(
                Account(BANK, "銀行", AccountKind.BANK, balance = 1_000_000, balanceAsOf = today),
                Account(
                    CARD, "卡", AccountKind.CREDIT_CARD, balance = debt, balanceAsOf = today, creditLimit = 1_000_000,
                    statementDay = 1, paymentDueDay = 15,
                    card = CardTerms(revolvingRatePercent = 15.0, payAccountId = BANK, paymentPlan = CardPaymentPlan.PLANNED),
                ),
            ),
            groups = listOf(PlanGroup(1, "收入", 1), PlanGroup(8, "繳款", 8)),
            items = listOf(
                PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, dueDay = 5),
                PlanItem(PAY_CARD, "繳卡", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, dueDay = 15),
            ),
            amountsByYear = mapOf(2026 to amounts(pay), 2027 to amounts(pay2027)),
            actuals = emptyList(),
            ledger = emptyList(),
            settings = AppSettings(safetyLevel = 0, horizonMonths = 24, autoPostFrom = today.toEpochDay(), transferAccountId = BANK),
        )
    }

    private fun months(vararg pairs: Pair<Int, Money>): List<Money> =
        List(12) { i -> pairs.firstOrNull { it.first == i + 1 }?.second ?: 0L }

    @Test fun `V37-01 本月到期用截止日那個月編的金額，不是上一期的`() {
        // 9 月編 10,000、10 月編 30,000；欠款夠多，所以建議金額不會被帳單上限壓下來
        val s = snapshot(debt = 200_000, pay = months(9 to 10_000, 10 to 30_000))
        val dues = DueItems.list(s, through = LocalDate.of(2026, 10, 31)).filter { it.kind == DueKind.CARD_PAYMENT }
        assertEquals(listOf("cardpay:2:2026-09", "cardpay:2:2026-10"), dues.map { it.key })
        assertEquals("9/15 截止 → 9 月編的", 10_000L, dues[0].amount)
        assertEquals("10/15 截止 → 10 月編的", 30_000L, dues[1].amount)
    }

    @Test fun `V37-02 明年的年初欠款＝今年的年底欠款`() {
        // 逐月金額不同才看得出來：只用「目前這一期」的金額往前滾會滾錯
        val s = snapshot(debt = 200_000, pay = months(9 to 10_000, 10 to 20_000, 11 to 30_000, 12 to 40_000))
        val closing2026 = PlanSummaryCalculator.summarize(s, 2026).cards.single().end
        val opening2027 = PlanSummaryCalculator.summarize(s, 2027).cards.single().start
        assertEquals("跨年要接續（R-PLS-06）", closing2026, opening2027)
    }

    @Test fun `V37-03 繳卡費合計用還款上限之後的金額（R-PAY-02）`() {
        // 欠款只有 60,000，卻編每月繳 500,000：實際只會繳到欠款為 0
        val s = snapshot(debt = 60_000, pay = List(12) { 500_000L })
        val summary = PlanSummaryCalculator.summarize(s, 2026)
        val card = summary.cards.single()
        assertEquals("逐卡推估與彙總要是同一組數字（R-PLS-06）", card.payments, summary.totalCardPayments)
        assertEquals("9 月最多繳到欠款為 0", 60_000L, summary.cardPayments[8])
        assertEquals(0L, summary.cardPayments[9])
    }
}
