package tw.myfsl.app.core.domain

import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.*
import java.time.LocalDate

/** Independent review cases (v2.2 審閱). Assertions describe expected accounting behavior. */
class ReviewRegressionTest {
    private val today = LocalDate.of(2026, 9, 18)
    private fun base(): FinanceSnapshot = FinanceSnapshot.empty(today).copy(
        accounts = listOf(
            Account(1, "Bank", AccountKind.BANK, balance = 100000),
            Account(2, "Card", AccountKind.CREDIT_CARD, balance = 10000,
                card = CardTerms(12.0, payMode = CardPayMode.FIXED, fixedPayment = 2000,
                    payAccountId = 1, payDay = 15))
        ),
        settings = AppSettings(autoPostFrom = LocalDate.of(2026, 9, 1).toEpochDay(), transferAccountId = 1)
    )

    @Test fun checkInIncludesCardInterestAndPayment() {
        val s = base()
        val due = DueItems.list(s)
        val pending = due.flatMap { DueItems.record(s, it, DueItems.defaultChoice(s, it)).entries }
        val row = CheckInRules.reconciles(s, pending).single { it.isCard }
        assertEquals("10000 + 100 interest - 2000 payment", 8100L, row.computed)
    }

    @Test fun unassignedSpendingCountsForDueInterest() {
        val s = base().copy(unassignedCardSpending = 5000)
        val interest = DueItems.list(s).single { it.kind == DueKind.CARD_INTEREST }
        assertEquals("Default card carries 10000 + 5000 debt", 150L, interest.amount)
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
        val original = base()
        val s = original.copy(accounts = original.accounts.map { if(it.id == 2L) it.copy(card = it.card!!.copy(payMode = CardPayMode.FULL)) else it })
        val dues = DueItems.list(s)
        val interest = dues.single { it.kind == DueKind.CARD_INTEREST }
        val payment = dues.single { it.kind == DueKind.CARD_PAYMENT }
        val result = CheckInRules.build(s, CheckInInput(dues = mapOf(
            interest.key to DueDecision(DueCheck.SKIP), payment.key to DueDecision(DueCheck.PAID)
        )))
        assertEquals("Skipping interest leaves only 10000 payable", 10000L,
            result.entries.single { it.type == FlowType.TRANSFER }.amount)
    }

    @Test fun installmentIsPostedBeforeInterestInSamePeriod() {
        val s = base().copy(installments = listOf(CardInstallment(
            id = 7, cardAccountId = 2, purchaseDate = LocalDate.of(2026, 8, 20),
            amount = 12000, months = 12, firstPeriodIndex = Period(2026, 9, Half.SECOND).index
        )))
        val result = CashFlowEngine.run(BaselineBuilder.build(s, 2))
        assertEquals("11000 debt after installment must incur 110 interest", 110L, result.periods.first().cardInterest)
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
                val revolving = plan.restoreRevolving?.takeIf { it.first == a.id }?.second
                when {
                    revolving != null -> a.copy(card = a.card!!.copy(revolvingBalance = revolving))
                    plan.addLoanMonthTo == a.id -> a.copy(loan = a.loan!!.copy(remainingMonths = a.loan!!.remainingMonths + 1))
                    else -> a
                }
            },
        )
    }

    /** 記下到期項目後的快照（資料層寫入的事：記帳、期數、卡循、原值標記）。 */
    private fun FinanceSnapshot.record(due: DueItem): FinanceSnapshot {
        val r = DueItems.record(this, due, DueItems.defaultChoice(this, due))
        var nextId = (ledger.maxOfOrNull { it.id } ?: 0L) + 1
        return copy(
            ledger = ledger + r.entries.map { it.copy(id = nextId++) },
            postedKeys = postedKeys + listOfNotNull(r.marker),
            accounts = accounts.map { a ->
                val terms = r.cardTerms?.takeIf { it.first == a.id }?.second
                val loan = r.loanRemaining?.takeIf { it.first == a.id }?.second
                when {
                    terms != null -> a.copy(card = terms)
                    loan != null -> a.copy(loan = a.loan!!.copy(remainingMonths = loan))
                    else -> a
                }
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

    @Test fun f10DeletingFirstInterestRestoresRevolvingBalance() {
        val original = base()
        val s0 = original.copy(accounts = original.accounts.map {
            if (it.id == 2L) it.copy(balance = 60000, card = it.card!!.copy(revolvingBalance = 20000)) else it
        })
        val interest = DueItems.list(s0).single { it.kind == DueKind.CARD_INTEREST }
        assertEquals("20,000 × 12% ÷ 12", 200L, interest.amount)
        val s1 = s0.record(interest)
        assertNull("記下後清掉既有卡循", s1.account(2)!!.card!!.revolvingBalance)

        val s2 = s1.apply(Deletion.plan(s1, s1.ledger.single { it.postingKey == interest.key }))
        assertEquals("恢復既有卡循", 20000L, s2.account(2)!!.card!!.revolvingBalance)
        assertTrue("原值標記清掉", s2.postedKeys.none { it.startsWith(PostingKeys.REVOLVING) })
        assertEquals("重記時仍是 200，不是 60,000 × 1% = 600", 200L, DueItems.list(s2).single { it.kind == DueKind.CARD_INTEREST }.amount)
    }

    @Test fun f12NewSpendingAfterPayoffAccruesInterestByOriginalTerms() {
        // 今天 9/18（9 月下半月）清償；10 月上半月刷 30,000；卡片 15 日計息、固定繳 2,000、年利率 12%
        val start = Period.of(today)
        val oct1 = Period(2026, 10, Half.FIRST)
        val nov1 = Period(2026, 11, Half.FIRST)
        val applied = ScenarioApplier.apply(BaselineBuilder.build(base(), 6), listOf(
            ScenarioChange.PayOffDebts(listOf(2L), 1, start.index),
            ScenarioChange.OneOff("之後又刷", oct1.index, FlowType.EXPENSE, 30000, method = PaymentMethod.CREDIT_CARD),
        ))
        val result = CashFlowEngine.run(applied)
        // 9/15 的利息 100 到期還沒記下，放在今天這一期、先計息再清償（R-DUE-06、R-ORD-01）
        assertEquals("10,000 ＋ 利息 100", 10100L, result.periods.first { it.period == start }.debtPayoff)
        result.periods.first { it.period == oct1 }.run {
            assertEquals("10/15 計息時欠款是 0（同一期先計息再刷卡）", 0L, cardInterest)
            assertEquals("欠款 0，固定繳款最多繳到 0", 0L, cardPayments)
            assertEquals(30000L, balances[2])
        }
        result.periods.first { it.period == nov1 }.run {
            assertEquals("30,000 × 12% ÷ 12", 300L, cardInterest)
            assertEquals("依原條件固定繳 2,000", 2000L, cardPayments)
            assertEquals("30,000 + 300 − 2,000", 28300L, balances[2])
        }
    }

    @Test fun deferredPaymentCountsInOriginalBudgetMonth() {
        val e = LedgerEntry(date = LocalDate.of(2026, 10, 3), type = FlowType.EXPENSE, amount = 100, itemId = 10,
            method = PaymentMethod.CASH, source = EntrySource.DUE, postingKey = "plan:10:CASH:2026-09:15")
        assertEquals(java.time.YearMonth.of(2026, 9), e.budgetMonth)
    }
}
