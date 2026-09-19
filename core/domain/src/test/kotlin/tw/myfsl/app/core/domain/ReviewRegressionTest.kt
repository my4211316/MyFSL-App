package tw.myfsl.app.core.domain

import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.*
import java.time.LocalDate

/**
 * Independent review cases (v2.2 審閱). Assertions describe expected accounting behavior.
 *
 * v2.9 起信用卡改成依帳單繳款（R-CARD-20–22）：卡片固定用「10 日結帳、次月 5 日截止、年利率 12%」。
 * v3.2 起卡片不設預設繳款方式，建議金額由繳款紀錄推估（R-CARD-26）：這裡放一筆 8/5 只繳 2,000 的紀錄（7/10 那期沒繳清），
 * 所以之後每期推估繳 2,000，和原本「自由繳 2,000」的預期值相同。
 * 起算 9/1、今天 9/18：8/10 那期的截止日 9/5 在起算之後 → 9/5 繳 2,000；9/10 結帳時 8/10 那期帳單 10,000 還剩 8,000 沒繳，
 * 計利息 8,000 × 1% = 80。原本「以整筆欠款計息」的預期值依新規則手算改寫，測試的用意不變。
 */
class ReviewRegressionTest {
    private val today = LocalDate.of(2026, 9, 18)
    /** 8/5 繳 7/10 那期的 2,000（沒繳清）：推估之後每期繳 2,000。 */
    private val history = LedgerEntry(id = 99, date = LocalDate.of(2026, 8, 5), type = FlowType.TRANSFER, amount = 2000,
        accountId = 1, toAccountId = 2, source = EntrySource.DUE, postingKey = "cardpay:2:2026-07")
    private fun base(): FinanceSnapshot = FinanceSnapshot.empty(today).copy(
        accounts = listOf(
            Account(1, "Bank", AccountKind.BANK, balance = 100000),
            Account(2, "Card", AccountKind.CREDIT_CARD, balance = 10000, statementDay = 10, paymentDueDay = 5,
                card = CardTerms(revolvingRatePercent = 12.0, payAccountId = 1))
        ),
        ledger = listOf(history),
        settings = AppSettings(autoPostFrom = LocalDate.of(2026, 9, 1).toEpochDay(), transferAccountId = 1)
    )

    @Test fun checkInIncludesCardInterestAndPayment() {
        val s = base()
        val due = DueItems.list(s)
        val pending = due.flatMap { DueItems.record(s, it, DueItems.defaultChoice(s, it)).entries }
        val row = CheckInRules.reconciles(s, pending).single { it.isCard }
        assertEquals("10000 - 2000 payment (9/5) + 80 interest (9/10)", 8080L, row.computed)
    }

    @Test fun unassignedSpendingCountsForDueInterest() {
        val s = base().copy(unassignedCardSpending = 5000)
        val interest = DueItems.list(s).single { it.kind == DueKind.CARD_INTEREST }
        assertEquals("Default card carries 10000 + 5000 debt: (15000 - 2000) × 1%", 130L, interest.amount)
    }

    @Test fun overduePaymentTodayMustAffectBalanceAfterYesterdaySnapshot() {
        val s = base()
        val due = DueItems.list(s).single { it.kind == DueKind.CARD_PAYMENT }
        val entry = DueItems.record(s, due, DueItems.defaultChoice(s, due)).entries.single().copy(createdAt = 200)
        assertTrue("Actual payment on Sep 18 must be after Sep 17 reconciliation", entry.isAfter(RecordMark(today.minusDays(1), 100)))
    }

    @Test fun oneUnplannedPaymentMustNotCoverTwoBudgetLines() {
        val item = PlanItem(10, "Utilities", 1, FlowType.EXPENSE, dueDay = 15)
        val s = base().copy(items = listOf(item), amountsByYear = mapOf(2026 to mapOf(
            PlanLine(10, PaymentMethod.CASH) to List(12) { 1000L },
            PlanLine(10, PaymentMethod.CREDIT_CARD) to List(12) { 1000L }
        )), ledger = listOf(LedgerEntry(date = today.minusDays(4), type = FlowType.EXPENSE,
            amount = 500, itemId = 10, method = PaymentMethod.TRANSFER, accountId = 1)))
        assertEquals("2000 planned minus one 500 payment", 1500L,
            DueItems.list(s).filter { it.kind == DueKind.PLAN }.sumOf { it.amount })
    }

    @Test fun existingInterestRecordCanSaveNoteOnly() {
        val s = base()
        val due = DueItems.list(s).single { it.kind == DueKind.CARD_INTEREST }
        val entry = DueItems.record(s, due, DueItems.defaultChoice(s, due)).entries.single()
        val result = RecordEditForm.validate(RecordDraft(entry, note = "bank statement verified"), s)
        assertTrue("Note-only edit rejected: ${result.errors}", result.ok)
    }

    @Test fun skipInterestRecomputesFullPaymentInCheckIn() {
        // 10/6 本週檢查：9/5 已經記下繳 2,000（欠款 8,000）；9/10 利息 80 與 10/5 的繳款都還沒記
        val original = base()
        val paid = LedgerEntry(id = 1, date = LocalDate.of(2026, 9, 5), type = FlowType.TRANSFER, amount = 2000,
            accountId = 1, toAccountId = 2, source = EntrySource.DUE, postingKey = "cardpay:2:2026-08")
        val s = original.copy(
            today = LocalDate.of(2026, 10, 6),
            ledger = listOf(paid),
            accounts = original.accounts.map { if (it.id == 2L) it.copy(balance = 8000) else it },
        )
        val dues = DueItems.list(s, through = s.today)
        val interest = dues.single { it.kind == DueKind.CARD_INTEREST }
        val payment = dues.single { it.kind == DueKind.CARD_PAYMENT }
        assertEquals(80L, interest.amount)
        assertEquals("全額 = 9/10 帳單 8,000 + 80", 8080L, payment.payOptions!!.full)
        // 本週檢查略過利息：後面的全額跟著少那筆利息
        val skipped = CheckInRules.dueLines(s, input = CheckInInput(dues = mapOf(interest.key to DueDecision(DueCheck.SKIP))))
        assertEquals("Skipping interest leaves only 8000 payable", 8000L,
            skipped.single { it.kind == DueKind.CARD_PAYMENT }.payOptions!!.full)
    }

    @Test fun installmentIsPostedBeforeInterestInSamePeriod() {
        // 還沒有繳款紀錄 → 推估全額（R-CARD-26）：9/5 繳清 8/10 的帳單 10,000；分期第一期在 10 月上半月入帳，
        // 和 10/10 結帳同一個半月 → 算進 10/10 帳單，11/5 繳
        val original = base()
        val s = original.copy(
            ledger = emptyList(),
            installments = listOf(CardInstallment(
                id = 7, cardAccountId = 2, purchaseDate = LocalDate.of(2026, 9, 20),
                amount = 12000, months = 12, firstPeriodIndex = Period(2026, 10, Half.FIRST).index
            )),
        )
        val result = CashFlowEngine.run(BaselineBuilder.build(s, 4))
        assertEquals("先入帳分期、再結帳：分期本金算進帳單", 1000L, result.periods.first { it.period == Period(2026, 11, Half.FIRST) }.cardPayments)
        assertEquals("全額繳清不計息", 0L, result.totalCardInterest)
    }

    @Test fun interestOnlyLoanPaymentDoesNotReappear() {
        val s = base().copy(accounts = listOf(base().accounts.first(),
            Account(3, "Loan", AccountKind.LOAN, balance = 120000,
                loan = LoanTerms(12.0, 12, RepaymentMethod.INTEREST_ONLY, 1, 15))))
        val due = DueItems.list(s).single { it.kind == DueKind.LOAN }
        val record = DueItems.record(s, due, DueItems.defaultChoice(s, due))
        val after = s.copy(ledger = record.entries, accounts = s.accounts.map {
            if(it.id == 3L) it.copy(loan = it.loan!!.copy(remainingMonths = 11)) else it
        })
        assertFalse("Paid interest-only loan period should disappear", DueItems.list(after).any { it.key == due.key })
    }

    // ---------- F09、F10、F12：從記下到刪除（或清償後再刷卡）完整走一次 ----------

    /** 把刪除計畫套到快照上（資料層在同一個交易內做的事）。 */
    private fun FinanceSnapshot.apply(plan: DeletionPlan): FinanceSnapshot {
        val cancel = plan.cancelInstallmentId
        return copy(
            ledger = ledger.filterNot { e ->
                e.id in plan.ledgerIds || e.postingKey in plan.ledgerKeys || (cancel != null && e.installmentId == cancel)
            },
            postedKeys = postedKeys - plan.postedKeys.toSet(),
            installments = installments.filterNot { it.id == cancel },
            accounts = accounts.map { a ->
                if (plan.addLoanMonthTo == a.id) a.copy(loan = a.loan!!.copy(remainingMonths = a.loan!!.remainingMonths + 1)) else a
            },
        )
    }

    /** 記下到期項目後的快照（資料層寫入的事：記帳、期數；帳戶餘額跟著記帳變）。已經到期的照到期日付款。 */
    private fun FinanceSnapshot.record(due: DueItem): FinanceSnapshot {
        val r = DueItems.record(this, due, DueItems.defaultChoice(this, due).copy(date = due.date.takeIf { !it.isAfter(today) }))
        var nextId = (ledger.maxOfOrNull { it.id } ?: 0L) + 1
        return copy(
            ledger = ledger + r.entries.map { it.copy(id = nextId++) },
            accounts = accounts.map { a ->
                val loan = r.loanRemaining?.takeIf { it.first == a.id }?.second
                val moved = a.copy(balance = a.balance + r.entries.sumOf { BalanceRules.effect(it, a.id, a.kind) })
                if (loan != null) moved.copy(loan = moved.loan!!.copy(remainingMonths = loan)) else moved
            },
        )
    }

    @Test fun f09DeletingOnePeriodKeepsPurchaseAndInstallment() {
        val purchase = LedgerEntry(id = 1, date = LocalDate.of(2026, 8, 20), type = FlowType.EXPENSE, amount = 12000,
            method = PaymentMethod.CREDIT_CARD, accountId = 2, installmentId = 7)
        val s0 = base().copy(ledger = listOf(purchase), installments = listOf(CardInstallment(
            id = 7, cardAccountId = 2, purchaseDate = LocalDate.of(2026, 8, 20), amount = 12000, months = 12,
            fee = InstallmentFee.PER_PERIOD, feeValue = 50.0, firstPeriodIndex = Period(2026, 9, Half.SECOND).index,
        )))
        val period = DueItems.list(s0).single { it.kind == DueKind.INSTALLMENT }
        val s1 = s0.record(period)
        assertTrue("記下後不再列出", DueItems.list(s1).none { it.kind == DueKind.INSTALLMENT })
        val fee = s1.ledger.single { it.postingKey == "instfee:7:1" }
        assertEquals("手續費也帶分期 id", 7L, fee.installmentId)

        // 刪掉那一期的本金：只刪那一期（本金＋手續費），原始消費與分期都還在，這一期回到清單
        val principal = s1.ledger.single { it.postingKey == "inst:7:1" }
        val plan = Deletion.plan(s1, principal)
        assertNull("不是整筆取消", plan.cancelInstallmentId)
        val s2 = s1.apply(plan)
        assertEquals(listOf(purchase), s2.ledger)
        assertEquals(1, s2.installments.size)
        assertEquals("回到本月到期", listOf("inst:7:1"), DueItems.list(s2).filter { it.kind == DueKind.INSTALLMENT }.map { it.key })

        // 刪分期消費本身：整筆取消（消費、已入帳的那一期與手續費、分期）
        val s3 = s1.apply(Deletion.plan(s1, purchase))
        assertTrue(s3.ledger.isEmpty())
        assertTrue(s3.installments.isEmpty())
    }

    @Test fun f10DeletingInterestReturnsSameAmount() {
        // v2.9：「既有卡循」由帳單期別取代（R-CARD-22）。利息只看上一期帳單沒繳清的部分，刪掉重記仍是同一個金額。
        val s0 = base()
        val payment = DueItems.list(s0).single { it.kind == DueKind.CARD_PAYMENT }
        val s1 = s0.record(payment)
        val interest = DueItems.list(s1).single { it.kind == DueKind.CARD_INTEREST }
        assertEquals("(10,000 − 2,000) × 12% ÷ 12", 80L, interest.amount)
        val s2 = s1.record(interest)
        assertTrue(DueItems.list(s2).none { it.kind == DueKind.CARD_INTEREST })

        val deleted = s2.ledger.single { it.postingKey == interest.key }
        val s3 = s2.apply(Deletion.plan(s2, deleted)).let { s ->
            s.copy(accounts = s.accounts.map { it.copy(balance = it.balance - BalanceRules.effect(deleted, it.id, it.kind)) })
        }
        assertEquals("刪掉後回到本月到期，金額不變", 80L, DueItems.list(s3).single { it.kind == DueKind.CARD_INTEREST }.amount)
    }

    @Test fun f12NewSpendingAfterPayoffAccruesInterestByOriginalTerms() {
        // 今天 9/18（9 月下半月）清償；10 月上半月刷 30,000；卡片 10 日結帳、5 日截止、自由繳 2,000、年利率 12%
        val start = Period.of(today)
        val oct1 = Period(2026, 10, Half.FIRST)
        val nov1 = Period(2026, 11, Half.FIRST)
        val applied = ScenarioApplier.apply(BaselineBuilder.build(base(), 6), listOf(
            ScenarioChange.PayOffDebts(listOf(2L), 1, start.index),
            ScenarioChange.OneOff("之後又刷", oct1.index, FlowType.EXPENSE, 30000, method = PaymentMethod.CREDIT_CARD),
        ))
        val result = CashFlowEngine.run(applied)
        // 9/10 的利息 80 到期還沒記下，放在今天這一期、先計息再清償（R-DUE-06、R-ORD-01）；
        // 9/5 那筆 2,000 也還沒記，排在清償之後，欠款已經是 0，繳 0（R-PAY-02）
        assertEquals("10,000 ＋ 利息 80", 10080L, result.periods.first { it.period == start }.debtPayoff)
        result.periods.first { it.period == oct1 }.run {
            assertEquals("10/10 結帳：9/10 那期已經清償，沒有利息", 0L, cardInterest)
            assertEquals("10/5 欠款 0，最多繳到 0", 0L, cardPayments)
            assertEquals(30000L, balances[2])
        }
        result.periods.first { it.period == nov1 }.run {
            assertEquals("依原條件 11/5 繳 2,000", 2000L, cardPayments)
            assertEquals("11/10 結帳：10/10 帳單 30,000 − 已繳 2,000 = 28,000 × 1%", 280L, cardInterest)
            assertEquals("30,000 − 2,000 + 280", 28280L, balances[2])
        }
    }

    @Test fun deferredPaymentCountsInOriginalBudgetMonth() {
        val e = LedgerEntry(date = LocalDate.of(2026, 10, 3), type = FlowType.EXPENSE, amount = 100, itemId = 10,
            method = PaymentMethod.CASH, source = EntrySource.DUE, postingKey = "plan:10:CASH:2026-09:15")
        assertEquals(java.time.YearMonth.of(2026, 9), e.budgetMonth)
    }
}
