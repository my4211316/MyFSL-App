package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RecordMark
import tw.myfsl.app.core.model.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetPlanAccountTest {

    private val snapshot = SampleHousehold.snapshot()

    // ---------- 本月可調支出進度 ----------

    @Test fun `9月14日可調支出：5 列、狀態、百分比與每日可用`() {
        val lines = BudgetProgressCalculator.forMonth(snapshot)
        assertEquals(5, lines.size)
        fun line(item: Long, method: PaymentMethod) = lines.single { it.item.id == item && it.method == method }

        line(FUEL, PaymentMethod.CREDIT_CARD).run {
            assertEquals(PaceStatus.AHEAD, status); assertEquals(66, spentPercent); assertEquals(47, timePercent)
            assertEquals(19, paceGapPercent); assertEquals(70L, dailyAllowance)
        }
        line(LIVING, PaymentMethod.CREDIT_CARD).run {
            assertEquals(PaceStatus.AHEAD, status); assertEquals(61, spentPercent); assertEquals(14, paceGapPercent)
            assertEquals(364L, dailyAllowance)
        }
        line(LIVING, PaymentMethod.CASH).run {
            assertEquals(PaceStatus.ON_TRACK, status); assertEquals(49, spentPercent); assertEquals(270L, dailyAllowance)
        }
        line(HOUSEHOLD, PaymentMethod.CASH).run {
            assertEquals(PaceStatus.ON_TRACK, status); assertEquals(30, spentPercent); assertEquals(41L, dailyAllowance)
        }
        line(LESSONS, PaymentMethod.TRANSFER).run { assertEquals(PaceStatus.ON_TRACK, status) }
        // 排序：花太快在前，超前多的排前面
        assertEquals(FUEL, lines[0].item.id)
        assertEquals(LIVING, lines[1].item.id)
        // 生活類每日可用加總
        assertEquals(745L, lines.filter { it.groupName == "生活" }.sumOf { it.dailyAllowance ?: 0 })
    }

    @Test fun `沒規劃的支付方式：列為未規劃、不提醒，但算進項目總額`() {
        val withTransfer = snapshot.copy(
            ledger = snapshot.ledger +
                LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 500, itemId = LIVING, method = PaymentMethod.TRANSFER, accountId = BANK),
        )
        val lines = BudgetProgressCalculator.forMonth(withTransfer)
        val unplanned = lines.single { it.item.id == LIVING && it.method == PaymentMethod.TRANSFER }
        assertEquals(PaceStatus.UNPLANNED, unplanned.status)
        assertNull(unplanned.dailyAllowance)
        val living = BudgetProgressCalculator.byItem(lines).single { it.item.id == LIVING }
        assertEquals(25_000L, living.planned)
        assertEquals(14_700L, living.actual)
        assertEquals(10_300L, living.remaining)
    }

    @Test fun `超支、已完成、尚未開始`() {
        val over = snapshot.copy(
            ledger = snapshot.ledger +
                LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 900, itemId = HOUSEHOLD, method = PaymentMethod.CASH, accountId = CASH),
        )
        assertEquals(PaceStatus.OVER, BudgetProgressCalculator.forMonth(over).single { it.item.id == HOUSEHOLD }.status)

        val done = snapshot.copy(
            actuals = listOf(ItemActual(HOUSEHOLD, PaymentMethod.CASH, 2026, 9, ActualStatus.DONE, snapshot.today)),
        )
        val doneLine = BudgetProgressCalculator.forMonth(done).single { it.item.id == HOUSEHOLD }
        assertEquals(PaceStatus.DONE, doneLine.status)
        assertNull(doneLine.dailyAllowance)

        val secondHalfItem = PlanItem(900, "月底聚餐", 5, FlowType.EXPENSE, timing = Timing.SECOND_HALF, flexibility = tw.myfsl.app.core.model.Flexibility.FLEXIBLE)
        val plan = snapshot.amountsByYear.mapValues { (_, amounts) -> amounts + (PlanLine(900, PaymentMethod.CASH) to List(12) { 3_000L }) }
        val notStarted = snapshot.copy(items = snapshot.items + secondHalfItem, amountsByYear = plan, today = LocalDate.of(2026, 9, 10))
        assertEquals(PaceStatus.NOT_STARTED, BudgetProgressCalculator.forMonth(notStarted).single { it.item.id == 900L }.status)
    }

    @Test fun `時間進度與剩餘天數`() {
        assertEquals(1.0, BudgetProgressCalculator.timeRatio(Timing.FIRST_HALF, 20, 30), 0.0)
        assertEquals(0.0, BudgetProgressCalculator.timeRatio(Timing.SECOND_HALF, 10, 30), 0.0)
        assertEquals(1.0, BudgetProgressCalculator.timeRatio(Timing.SECOND_HALF, 30, 30), 0.0)
        assertEquals(17, BudgetProgressCalculator.daysLeft(Timing.SPLIT, 14, 30))
        assertEquals(0, BudgetProgressCalculator.daysLeft(Timing.FIRST_HALF, 20, 30))
        assertEquals(15, BudgetProgressCalculator.daysLeft(Timing.SECOND_HALF, 10, 30))
        assertEquals(14, displayPercent(0.1449))
        assertEquals(15, displayPercent(0.145))
    }

    // ---------- 年度計畫表 ----------

    @Test fun `2026 年度計畫彙總`() {
        val summary = PlanSummaryCalculator.summarize(snapshot, 2026)
        assertEquals(940_000L, summary.totalIncome)
        assertEquals(840_000L, summary.totalExpense)
        assertEquals(338_980L, summary.totalCardSpending)
        assertEquals("A 卡自由繳預估 18,000 ＋ 計畫繳 B 卡 9,000，各 12 個月", 324_000L, summary.totalCardPayments)
        assertEquals(174_084L, summary.totalLoanPayments)
        assertEquals("940,000 − 840,000 − 174,084 − 6,300（循環利息）", -80_384L, summary.structuralGap)
        assertEquals("只有 A 卡依帳單繳款：沒繳清的 (60,000 − 18,000) × 15% ÷ 12 = 525，× 12", 6_300L, summary.totalCardInterest)
        assertEquals("刷卡 338,980 ＋ 利息 6,300 − 繳卡費 324,000", 21_280L, summary.cardDebtIncrease)
        assertEquals("33,900 ＋ 525 − 27,000", 7_425L, summary.cardDebtChange(9))
        assertEquals(listOf(33_900L, 21_900L, 21_900L, 21_900L), summary.cardSpending.subList(8, 12))
        assertEquals(listOf(19_500L, 24_700L, 23_900L, 48_700L), summary.nonCardSpending.subList(8, 12))
        assertEquals(listOf(41_507L, 41_507L, 41_507L, 41_507L), summary.debtPayments.subList(8, 12))
        assertEquals(listOf(8_993L, -1_207L, -407L, -25_207L), summary.monthlyCashFlow.subList(8, 12))
        val groups = summary.groups.associate { it.group.name to it.total }
        assertEquals(146_920L, groups["稅費與保險"])
        assertEquals(11_100L, groups["生活繳費"])
        assertEquals(84_000L, groups["固定支出"])
        assertEquals(382_800L, groups["生活"])
        assertEquals(84_000L, groups["小孩活動"])
        assertEquals(131_180L, groups["年度"])
    }

    @Test fun `計畫檢查：示意資料只有卡債提醒與建議`() {
        val issues = PlanValidator.validate(snapshot, 2026)
        assertTrue(issues.none { it.severity == Severity.ERROR })
        assertTrue(issues.any { it.message == "全年刷卡加利息比繳卡費多 $21,280，差額會累積成卡債" })
        // 刷卡都算在預設卡片 A：每月 338,980 ÷ 12 ≈ 28,248；28,248 ＋ 525 − 18,000 = 10,773
        assertTrue(
            issues.any { it.message == "「信用卡 A」每月刷 $28,248、利息 $525，繳 $18,000 不夠；每月至少要多繳 $10,773 卡債才不會再增加" },
        )
        assertTrue("B 卡沒有循環條件，不做卡債走向提醒", issues.none { it.message.startsWith("「信用卡 B」") })
        assertTrue("示意資料沒有重複繳款", issues.none { it.message.contains("不計入") })
        assertTrue(issues.any { it.message == "「才藝課」是可調項目，建議改成依記帳，才能控管進度" })
    }

    @Test fun `計畫檢查：各種錯誤`() {
        val broken = snapshot.copy(
            accounts = snapshot.accounts.filter { !it.kind.isLiquid || it.kind == AccountKind.BANK },
            items = snapshot.items + listOf(
                PlanItem(950, "兼職", 1, FlowType.INCOME),
                PlanItem(951, "自己轉自己", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = BANK),
                PlanItem(952, "沒有轉入", 8, FlowType.TRANSFER, accountId = BANK),
                PlanItem(953, "重複繳信貸", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = SampleHousehold.LOAN),
                PlanItem(954, "空項目", 5, FlowType.EXPENSE),
            ),
            settings = AppSettings(cashAccountId = null, transferAccountId = BANK),
            amountsByYear = snapshot.amountsByYear.mapValues { (_, a) -> a + (PlanLine(953) to List(12) { 5_000L }) },
        )
        val messages = PlanValidator.validate(broken, 2026).map { it.message }
        assertTrue(messages.contains("「兼職」沒有設定入帳帳戶"))
        assertTrue(messages.contains("「自己轉自己」轉出與轉入是同一個帳戶"))
        assertTrue(messages.contains("「沒有轉入」沒有設定轉入帳戶"))
        assertTrue(messages.contains("「重複繳信貸」不計入：「信貸」已依合約自動繳款。若這是額外還款，請在項目勾選「額外還款」"))
        assertTrue(messages.contains("「空項目」今年沒有任何計畫金額"))
        // 沒有現金帳戶時，現金支出退回第一個流動帳戶（銀行），所以不算錯誤
        assertTrue(messages.none { it.startsWith("支付方式「現金」") })

        val noLiquid = broken.copy(accounts = broken.accounts.filter { !it.kind.isLiquid })
        val noLiquidMessages = PlanValidator.validate(noLiquid, 2026).map { it.message }
        assertTrue(noLiquidMessages.contains("支付方式「現金」沒有對應的帳戶，請先新增現金帳戶或在設定指定"))
        assertTrue(noLiquidMessages.contains("支付方式「轉帳」沒有對應的帳戶，請先新增銀行帳戶或在設定指定"))

        val noIncome = snapshot.copy(amountsByYear = snapshot.amountsByYear.mapValues { (_, a) -> a.filterKeys { it.itemId !in setOf(101L, 102L, 103L) } })
        assertTrue(PlanValidator.validate(noIncome, 2026).any { it.message == "計畫中沒有任何收入" })
        // 錯誤排在最前面
        assertEquals(Severity.ERROR, PlanValidator.validate(broken, 2026).first().severity)
    }

    // ---------- 帳戶 ----------

    @Test fun `記帳對帳戶餘額的影響（資產與負債方向相反）`() {
        val expense = LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 100, accountId = 1)
        assertEquals(-100L, BalanceRules.effect(expense, 1, AccountKind.CASH))
        assertEquals(100L, BalanceRules.effect(expense, 1, AccountKind.CREDIT_CARD))
        assertEquals(0L, BalanceRules.effect(expense, 2, AccountKind.CASH))
        val income = LedgerEntry(date = snapshot.today, type = FlowType.INCOME, amount = 100, accountId = 1)
        assertEquals(100L, BalanceRules.effect(income, 1, AccountKind.BANK))
        assertEquals(-100L, BalanceRules.effect(income, 1, AccountKind.CREDIT_CARD))
        val payCard = LedgerEntry(date = snapshot.today, type = FlowType.TRANSFER, amount = 100, accountId = 1, toAccountId = 3)
        assertEquals(-100L, BalanceRules.effect(payCard, 1, AccountKind.BANK))
        assertEquals(-100L, BalanceRules.effect(payCard, 3, AccountKind.CREDIT_CARD))
    }

    @Test fun `未指定卡片的刷卡：只算最近一次信用卡校正之後的`() {
        val ledger = listOf(
            LedgerEntry(date = LocalDate.of(2026, 9, 10), type = FlowType.EXPENSE, amount = 300, method = PaymentMethod.CREDIT_CARD),
            LedgerEntry(date = LocalDate.of(2026, 9, 13), type = FlowType.EXPENSE, amount = 800, method = PaymentMethod.CREDIT_CARD),
            LedgerEntry(date = LocalDate.of(2026, 9, 13), type = FlowType.EXPENSE, amount = 150, method = PaymentMethod.CREDIT_CARD, accountId = CARD_A),
            LedgerEntry(date = LocalDate.of(2026, 9, 13), type = FlowType.EXPENSE, amount = 90, method = PaymentMethod.CASH, accountId = CASH),
        )
        assertEquals(800L, BalanceRules.unassignedCardSpending(ledger, RecordMark(LocalDate.of(2026, 9, 12))))
        assertEquals(1_100L, BalanceRules.unassignedCardSpending(ledger, null))
        // 校正當天的刷卡：寫入時間晚於校正才算
        val sameDay = listOf(
            LedgerEntry(date = LocalDate.of(2026, 9, 12), type = FlowType.EXPENSE, amount = 70, method = PaymentMethod.CREDIT_CARD, createdAt = 1_000),
            LedgerEntry(date = LocalDate.of(2026, 9, 12), type = FlowType.EXPENSE, amount = 30, method = PaymentMethod.CREDIT_CARD, createdAt = 3_000),
        )
        assertEquals(30L, BalanceRules.unassignedCardSpending(sameDay, RecordMark(LocalDate.of(2026, 9, 12), recordedAt = 2_000)))
    }

    @Test fun `帳戶總覽`() {
        val overview = AccountSummaryCalculator.overview(snapshot)
        assertEquals(135_000L, overview.liquid)
        assertEquals(105_000L, overview.cardDebt)
        assertEquals(600_000L, overview.loanDebt)
        assertEquals(800_000L, overview.policyLoanDebt)
        assertEquals(1_505_000L, overview.totalDebt)
        val cardA = overview.cards.single { it.account.id == CARD_A }
        assertEquals(40, cardA.utilizationPercent)
        assertEquals(90_000L, cardA.available)
        assertEquals("A 卡依合約固定繳", 18_000L, cardA.fixedPayment)
        assertEquals("B 卡沒有循環條件，看計畫的繳信用卡 B", 9_000L, overview.cards.single { it.account.id == SampleHousehold.CARD_B }.fixedPayment)
        assertEquals("9,650 + 150", 9_800L, cardA.monthSpending)
        assertEquals(
            "未指定卡片的 800 不算在個別卡片",
            1_500L,
            overview.cards.single { it.account.id == SampleHousehold.CARD_B }.monthSpending,
        )
        assertEquals(56, overview.cards.single { it.account.id == SampleHousehold.CARD_B }.utilizationPercent)
        val loan = overview.loans.single { it.account.id == SampleHousehold.LOAN }
        assertEquals(14_507L, loan.monthlyPayment)
        assertEquals(25, loan.repaidPercent)
        assertNull(overview.loans.single { it.account.id == SampleHousehold.POLICY_LOAN }.repaidPercent)
    }

    @Test fun `未指定卡片的刷卡算進卡債合計`() {
        val overview = AccountSummaryCalculator.overview(snapshot.copy(unassignedCardSpending = 800))
        assertEquals(105_800L, overview.cardDebt)
        assertEquals(800L, overview.unassignedCardSpending)
    }
}
