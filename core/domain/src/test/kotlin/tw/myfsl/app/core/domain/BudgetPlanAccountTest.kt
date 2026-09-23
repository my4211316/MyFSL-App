package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetPlanAccountTest {

    private val snapshot = SampleHousehold.snapshot()

    // ---------- 本月可調支出進度 ----------

    @Test fun `9月14日支出進度：每個有額度的項目一列、狀態、百分比與每日可用`() {
        val lines = BudgetProgressCalculator.forMonth(snapshot)
        // 任何有本月額度的支出項目都有進度條（R-BUD-10），不只可調項目。
        assertEquals(8, lines.size)
        fun line(item: Long) = lines.single { it.item.id == item }

        line(FUEL).run {
            assertEquals(PaceStatus.AHEAD, status); assertEquals(66, spentPercent); assertEquals(47, timePercent)
            assertEquals(19, paceGapPercent); assertEquals(70L, dailyAllowance)
        }
        // 生活費：進度條是項目層級（R-MIX-01），現金列 9,000 ＋ 信用卡列 16,000 = 25,000，
        // 花了現金 4,400 ＋ 刷卡 9,800 = 14,200（57%），時間 47%
        line(LIVING).run {
            assertEquals(PaceStatus.ON_TRACK, status); assertEquals(57, spentPercent); assertEquals(10, paceGapPercent)
            assertEquals(25_000L, planned)
            assertEquals("剩 10,800 ÷ 17 天", 635L, dailyAllowance)
            assertEquals("信用卡 \$9,800 · 現金 \$4,400", methodBreakdown)
        }
        line(HOUSEHOLD).run {
            assertEquals(PaceStatus.ON_TRACK, status); assertEquals(30, spentPercent); assertEquals(41L, dailyAllowance)
        }
        // 才藝課（可調、6,000）這個月還沒花 → 尚未開始，但仍算得出每日可用 6,000 ÷ 17 = 352
        line(LESSONS).run { assertEquals(PaceStatus.NOT_STARTED, status); assertEquals(352L, dailyAllowance) }
        // 固定項目：有額度就有進度條，但不算「剩下每天可用」——到期一次付掉就結束
        line(SampleHousehold.PHONE).run {
            assertEquals(PaceStatus.NOT_STARTED, status); assertEquals(2_400L, planned); assertNull(dailyAllowance)
        }
        line(SampleHousehold.CAR_SERVICE).run { assertEquals(12_000L, planned); assertNull(dailyAllowance) }
        // 排序：花太快在前，超前多的排前面；還沒開始的排最後
        assertEquals(FUEL, lines[0].item.id)
        assertEquals(LIVING, lines[1].item.id)
        assertEquals(PaceStatus.NOT_STARTED, lines.last().status)
        // 生活類每日可用加總（可調的四項）
        assertEquals(746L, lines.filter { it.groupName == "生活" }.sumOf { it.dailyAllowance ?: 0 })
    }

    @Test fun `其他支付方式的花費也算在同一個項目的預算裡（R-MIX-01）`() {
        val withTransfer = snapshot.copy(
            ledger = snapshot.ledger +
                LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 500, itemId = LIVING, method = PaymentMethod.TRANSFER, accountId = BANK),
        )
        val living = BudgetProgressCalculator.forMonth(withTransfer).single { it.item.id == LIVING }
        assertEquals("現金列 9,000 ＋ 信用卡列 16,000", 25_000L, living.planned)
        assertEquals("轉帳付的 500 也算進來，不再是「未規劃」", 14_700L, living.actual)
        assertEquals(PaceStatus.AHEAD, living.status)
        assertEquals("這個月的支付結構仍看得到", "信用卡 \$9,800 · 現金 \$4,400 · 轉帳 \$500", living.methodBreakdown)
    }

    @Test fun `超支、已完成、尚未開始`() {
        val over = snapshot.copy(
            ledger = snapshot.ledger +
                LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 900, itemId = HOUSEHOLD, method = PaymentMethod.CASH, accountId = CASH),
        )
        assertEquals(PaceStatus.OVER, BudgetProgressCalculator.forMonth(over).single { it.item.id == HOUSEHOLD }.status)

        val done = snapshot.copy(
            actuals = listOf(ItemActual(HOUSEHOLD, 2026, 9, ActualStatus.DONE, snapshot.today)),
        )
        val doneLine = BudgetProgressCalculator.forMonth(done).single { it.item.id == HOUSEHOLD }
        assertEquals(PaceStatus.DONE, doneLine.status)
        assertNull(doneLine.dailyAllowance)

        val secondHalfItem = PlanItem(
            900, "月底聚餐", 5, FlowType.EXPENSE,
            flexibility = tw.myfsl.app.core.model.Flexibility.FLEXIBLE,
        )
        val plan = snapshot.amountsByYear.mapValues { (_, amounts) -> amounts + (PlanLine(900) to List(12) { 3_000L }) }
        val notStarted = snapshot.copy(items = snapshot.items + secondHalfItem, amountsByYear = plan, today = LocalDate.of(2026, 9, 10))
        assertEquals(PaceStatus.NOT_STARTED, BudgetProgressCalculator.forMonth(notStarted).single { it.item.id == 900L }.status)
    }

    @Test fun `時間進度與剩餘天數`() {
        // 一期就是一個月（R-PER-01）：進度 = 今天是第幾天 ÷ 當月天數。
        assertEquals(0.5, BudgetProgressCalculator.timeRatio(15, 30), 0.0)
        assertEquals(1.0, BudgetProgressCalculator.timeRatio(30, 30), 0.0)
        assertEquals(17, BudgetProgressCalculator.daysLeft(14, 30))
        assertEquals(1, BudgetProgressCalculator.daysLeft(30, 30))
        assertEquals(14, displayPercent(0.1449))
        assertEquals(15, displayPercent(0.145))
    }

    // ---------- 年度計畫表 ----------

    @Test fun `2026 年度計畫彙總`() {
        val summary = PlanSummaryCalculator.summarize(snapshot, 2026)
        assertEquals(940_000L, summary.totalIncome)
        assertEquals("計畫裡的支出", 840_000L, summary.totalPlannedExpense)
        // 今天是 2026/9/14：卡費與貸款只預測 9–12 月，過去的月份已經發生過（R-PLS-05）。
        assertEquals(listOf(9, 10, 11, 12), summary.autoMonths)
        // A 卡逐月滾動（R-PLS-06）。刷卡列每月 21,900（生活費 16,000 ＋ 油資 3,500 ＋ 手機 2,400），
        // 9 月多一筆汽車保養 12,000 → 33,900。繳款推估每期 18,000，利息只算沒繳掉的部分：
        //  9 月 (60,000−18,000)×1.25% = 525 → 餘額 60,000+525+33,900−18,000 = 76,425
        // 10 月 (76,425−18,000)×1.25% = 730 → 76,425+730+21,900−18,000 = 81,055
        // 11 月 (81,055−18,000)×1.25% = 788 → 81,055+788+21,900−18,000 = 85,743
        // 12 月 (85,743−18,000)×1.25% = 847 → 85,743+847+21,900−18,000 = 90,490
        assertEquals(2_890L, summary.totalCardInterest)
        assertEquals("信貸 9–12 月共 4 期", 14_595L, summary.totalLoanInterest)
        // 支出＝一定要付出去的錢（R-PLS-04）：非刷卡支出 ＋ 繳卡費 ＋ 貸款月繳，利息已經包在繳出去的錢裡。
        assertEquals(501_020L + 180_000L + 58_028L, summary.totalExpense)
        // 刷卡列：21,900×12 ＋ 汽車保養 12,000×2 ＋ 旅遊 52,180（R-MIX-01）
        assertEquals(338_980L, summary.totalCardSpending)
        assertEquals("840,000 − 338,980", 501_020L, summary.totalNonCardSpending)
        assertEquals("A 卡 18,000×4 ＋ 計畫繳 B 卡 9,000×12", 180_000L, summary.totalCardPayments)
        assertEquals("每月 14,507 × 4 期", 58_028L, summary.totalLoanPayments)
        assertEquals(43_433L, summary.totalLoanPrincipal)
        assertEquals("940,000 − 739,048", 200_952L, summary.structuralGap)
        // 其中在還本金的部分：貸款本金 43,433 ＋（繳卡費 180,000 − 循環利息 2,890 − 刷卡 338,980）＝ 負的：卡債在長大
        assertEquals(-118_437L, summary.debtPrincipal)
        assertEquals("不算還本金：200,952 − 118,437", 82_515L, summary.gapWithoutPrincipal)
        // 卡債變化看年底與年初的餘額差（R-PLS-06）：A 卡 60,000 → 90,490，B 卡 45,000 → 9,000。
        // 卡債＝帳單該繳沒繳掉的部分；12 月刷的 21,900 還沒出帳，是未到期卡款（R-CARD-28）
        assertEquals(105_000L, summary.cardDebtStart)
        assertEquals("A 卡 68,590 ＋ B 卡 9,000", 77_590L, summary.cardDebtEnd)
        assertEquals(-27_410L, summary.cardDebtChange)
        assertEquals("未繳卡款＝卡債 ＋ 未到期", 99_490L, summary.cardUnpaidEnd)
        assertEquals("12 月刷的還沒出帳", 21_900L, summary.cardNotDueEnd)
        assertEquals("卡債變少，標題要跟著方向走（R-PLS-07）", "卡債全年減少", summary.cardDebtLabel)
        assertEquals("9 月：525 ＋ 33,900 − 27,000", 7_425L, summary.cardDebtChange(9))
        assertEquals(listOf(33_900L, 21_900L, 21_900L, 21_900L), summary.cardSpending.subList(8, 12))
        assertEquals("當月從帳戶扣的部分", listOf(19_500L, 24_700L, 23_900L, 48_700L), summary.nonCardSpending.subList(8, 12))
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

    @Test fun `只編了幾個月時，自動產生的貸款與卡費也只算那幾個月（R-PLS-05）`() {
        // 只留 10–12 月的計畫金額
        val partial = snapshot.copy(
            amountsByYear = snapshot.amountsByYear.mapValues { (_, amounts) ->
                amounts.mapValues { (_, months) -> months.mapIndexed { m, v -> if (m >= 9) v else 0L } }
            },
        )
        val summary = PlanSummaryCalculator.summarize(partial, 2026)
        assertEquals(listOf(10, 11, 12), summary.plannedMonths)
        assertEquals(listOf(10, 11, 12), summary.autoMonths)
        // 逐月滾動：10 月 (60,000−18,000)×1.25% = 525 → 餘額 64,425；11 月 580 → 68,905；12 月 636
        assertEquals(1_741L, summary.totalCardInterest)
        assertEquals("A 卡 18,000 ＋ 計畫繳 B 卡 9,000，各 3 個月", 81_000L, summary.totalCardPayments)
        assertEquals("信貸第一期在 9 月，10–12 月是第 2–4 期：每月 14,507 × 3", 43_521L, summary.totalLoanPayments)
    }

    @Test fun `計畫檢查：計畫有刷卡時提醒卡債走向`() {
        val issues = PlanValidator.validate(snapshot, 2026)
        val messages = issues.map { it.message }
        assertTrue(issues.none { it.severity == Severity.ERROR })
        assertTrue("示意資料沒有重複繳款", issues.none { it.message.contains("不計入") })
        assertTrue(messages.contains("「才藝課」是可調項目，建議改成依記帳，才能控管進度"))
        // 刷卡列都算在預設卡片 A（R-CARD-05）；9–12 月逐月滾動（R-PLS-06），繳 18,000 追不上刷的 99,600。
        assertTrue(
            messages.toString(),
            messages.any {
                it == "「信用卡 A」這一年刷 $99,600、利息 $2,890，繳 $72,000 不夠：年底欠款會從 $60,000 變成 $90,490"
            },
        )
        assertTrue("B 卡照計畫每月繳 9,000，欠款只會變少", messages.none { it.startsWith("「信用卡 B」這一年") })
        // 兩張卡合起來卡債仍然變少（A 卡 +30,490、B 卡 −36,000），所以沒有全年的卡債提醒。
        assertTrue(messages.none { it.startsWith("全年刷卡加利息") })
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
        // 沒有現金帳戶時退回第一個流動帳戶（銀行），所以不算錯誤
        assertTrue(messages.none { it.startsWith("計畫有") && it.contains("沒有可以扣款的帳戶") })

        // 帳戶是逐個支付方式檢查的（R-MIX-01）：現金列和轉帳列各自要有可以扣款的帳戶
        val noLiquid = broken.copy(accounts = broken.accounts.filter { !it.kind.isLiquid })
        val noLiquidMessages = PlanValidator.validate(noLiquid, 2026).map { it.message }
        assertTrue(noLiquidMessages.contains("計畫有現金支出，但沒有可以扣款的帳戶，請先新增現金或銀行帳戶"))
        assertTrue(noLiquidMessages.contains("計畫有轉帳支出，但沒有可以扣款的帳戶，請先新增銀行帳戶"))

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
