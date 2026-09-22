package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPaymentPlan
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import java.time.LocalDate

/**
 * 「要留給卡費的現金」也要用**截止日那個月**編的金額（R-CARD-25、R-CARD-27；fdee72d 外部複審）。
 *
 * 卡片 20 日結帳、**次月** 5 日截止，所以結帳月和截止月不同月——
 * 錢是在截止日那天出去的，預留就要照那個月編的金額。
 * 欠款 60,000、可動用現金 100,000；選「照計畫編的金額」。
 */
class CardReserveMonthTest {

    private val BANK = 1L
    private val CARD = 2L
    private val PAY_CARD = 801L

    private fun snapshot(
        today: LocalDate,
        trackingFrom: LocalDate,
        pay: Map<Int, Money>,
        pay2027: Map<Int, Money> = emptyMap(),
        paid: Boolean = false,
    ): FinanceSnapshot {
        fun amounts(m: Map<Int, Money>) = mapOf(PlanLine(PAY_CARD) to List(12) { i -> m[i + 1] ?: 0L })
        return FinanceSnapshot(
            today = today,
            accounts = listOf(
                Account(BANK, "銀行", AccountKind.BANK, balance = 100_000, balanceAsOf = trackingFrom),
                Account(
                    CARD, "卡", AccountKind.CREDIT_CARD, balance = 60_000, balanceAsOf = trackingFrom, creditLimit = 300_000,
                    statementDay = 20, paymentDueDay = 5,
                    card = CardTerms(revolvingRatePercent = 15.0, payAccountId = BANK, paymentPlan = CardPaymentPlan.PLANNED),
                ),
            ),
            groups = listOf(PlanGroup(8, "繳款", 8)),
            items = listOf(PlanItem(PAY_CARD, "繳卡", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, dueDay = 5)),
            amountsByYear = mapOf(2026 to amounts(pay), 2027 to amounts(pay2027)),
            actuals = emptyList(),
            ledger = if (paid) {
                listOf(
                    LedgerEntry(
                        id = 1, date = LocalDate.of(2026, 10, 5), type = FlowType.TRANSFER, amount = 30_000,
                        accountId = BANK, toAccountId = CARD, source = EntrySource.DUE, postingKey = "cardpay:2:2026-09",
                    ),
                )
            } else {
                emptyList()
            },
            settings = AppSettings(safetyLevel = 0, horizonMonths = 24, autoPostFrom = trackingFrom.toEpochDay(), transferAccountId = BANK),
        )
    }

    /** 到期項目、卡費預留、扣掉後可用三者必須一致。 */
    private fun assertConsistent(s: FinanceSnapshot, expected: Money) {
        val due = DueItems.list(s, through = LocalDate.of(s.today.year + 1, 12, 31))
            .first { it.kind == DueKind.CARD_PAYMENT }
        assertEquals("本月到期", expected, due.amount)
        assertEquals("要留給卡費", expected, CardRules.reserve(s))
        val overview = AccountSummaryCalculator.overview(s)
        assertEquals("扣掉後可用 = 可動用現金 − 預留", 100_000L - expected, overview.freeCash)
    }

    @Test fun `結帳與截止跨月：預留用截止月編的金額`() {
        // 9/20 結帳 → 10/5 截止。9 月編 10,000、10 月編 30,000 → 要留 30,000
        val s = snapshot(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 22), pay = mapOf(9 to 10_000, 10 to 30_000))
        assertConsistent(s, 30_000)
    }

    @Test fun `前一個月編 0、截止月有金額：預留不是 0`() {
        val s = snapshot(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 22), pay = mapOf(9 to 0, 10 to 30_000))
        assertConsistent(s, 30_000)
    }

    @Test fun `跨年：12 月結帳、次年 1 月截止，用 1 月編的金額`() {
        // 編的金額要小於帳單（60,000），否則會被「不超過這一期帳單」的上限壓下來（R-CARD-20）
        val s = snapshot(
            LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 22),
            pay = mapOf(12 to 20_000), pay2027 = mapOf(1 to 50_000),
        )
        assertConsistent(s, 50_000)
    }

    @Test fun `編得比帳單多：繳款與預留都只到帳單金額（R-CARD-20）`() {
        val s = snapshot(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 22), pay = mapOf(9 to 10_000, 10 to 200_000))
        assertConsistent(s, 60_000)
    }

    @Test fun `這一期已經記下繳款：預留改用截止月的推估，不是結帳月的`() {
        val s = snapshot(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 22), pay = mapOf(9 to 10_000, 10 to 30_000), paid = true)
        assertEquals("要留給卡費", 30_000L, CardRules.reserve(s))
    }
}
