package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.Deferral
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RecordMark
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.TrackingMode
import tw.myfsl.app.core.sample.SampleHousehold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 本月到期（R-DUE：列出、點下去選支付方式記下、試算跟著清單）、還款上限（R-PAY-02）、延期款（R-DEF）、漏記取代與全部卡片對帳。
 * 用一份很小的獨立資料，每個數字都可以手算。
 */
class DueItemsTest {

    private val today = LocalDate.of(2026, 9, 14)

    private val BANK = 1L
    private val CARD = 3L
    private val CARD_B = 4L
    private val LOAN = 5L

    private val SALARY = 101L
    private val RENT = 201L
    private val LATER = 202L
    private val PAY_B = 301L
    private val PAY_CARD = 302L

    /** 5 日結帳、次月 3 日截止、年利率 12%。 */
    private val cardTerms = CardTerms(revolvingRatePercent = 12.0, payAccountId = BANK)

    /** 6/3 繳 5/5 那期 4,000（沒繳清）：之後每期推估繳 4,000（R-CARD-26）。在所有測試期間之前，不影響其他計算。 */
    private val history = LedgerEntry(
        id = 900, date = LocalDate.of(2026, 6, 3), type = FlowType.TRANSFER, amount = 4_000, accountId = BANK, toAccountId = CARD,
        source = EntrySource.DUE, postingKey = "cardpay:3:2026-05",
    )

    private val installment = CardInstallment(
        id = 7, cardAccountId = CARD_B, purchaseDate = LocalDate.of(2026, 8, 20), amount = 12_000, months = 6,
        fee = InstallmentFee.PER_PERIOD, feeValue = 100.0, firstPeriodIndex = Period(2026, 9, Half.FIRST).index,
    )

    private fun snapshot(
        from: LocalDate? = LocalDate.of(2026, 8, 31),
        terms: CardTerms = cardTerms,
        extraRepayment: Boolean = false,
        ledger: List<LedgerEntry> = emptyList(),
        actuals: List<ItemActual> = emptyList(),
        postedKeys: Set<String> = emptySet(),
    ): FinanceSnapshot {
        val amounts: MonthlyAmounts = mapOf(
            PlanLine(SALARY) to List(12) { 50_000L },
            PlanLine(RENT) to List(12) { 20_000L },
            PlanLine(LATER) to List(12) { 1_000L },
            PlanLine(PAY_B) to List(12) { 9_000L },
            PlanLine(PAY_CARD) to List(12) { 5_000L },
        )
        return FinanceSnapshot(
            today = today,
            accounts = listOf(
                Account(BANK, "銀行", AccountKind.BANK, balance = 100_000, balanceAsOf = today, sortOrder = 1),
                Account(
                    CARD, "依帳單繳款的卡", AccountKind.CREDIT_CARD, balance = 40_000, balanceAsOf = today,
                    statementDay = 5, paymentDueDay = 3, card = terms, sortOrder = 2,
                ),
                Account(CARD_B, "沒有循環條件的卡", AccountKind.CREDIT_CARD, balance = 3_000, balanceAsOf = today, sortOrder = 3),
                Account(
                    LOAN, "貸款", AccountKind.LOAN, balance = 120_000, balanceAsOf = today,
                    loan = LoanTerms(12.0, 12, RepaymentMethod.EQUAL_PAYMENT, BANK, 5), sortOrder = 4,
                ),
            ),
            groups = listOf(PlanGroup(1, "全部", 1)),
            items = listOf(
                PlanItem(SALARY, "薪資", 1, FlowType.INCOME, accountId = BANK, dueDay = 5),
                PlanItem(RENT, "房租", 1, FlowType.EXPENSE, method = PaymentMethod.TRANSFER, dueDay = 10),
                PlanItem(LATER, "月底才扣", 1, FlowType.EXPENSE, method = PaymentMethod.CASH, dueDay = 20),
                PlanItem(PAY_B, "繳 B 卡", 1, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD_B, dueDay = 12),
                PlanItem(PAY_CARD, "多繳有條件的卡", 1, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD, dueDay = 12, extraRepayment = extraRepayment),
            ),
            amountsByYear = mapOf(2026 to amounts),
            actuals = actuals,
            ledger = listOf(history) + ledger,
            installments = listOf(installment),
            settings = AppSettings(transferAccountId = BANK, autoPostFrom = from?.toEpochDay()),
            postedKeys = postedKeys,
        )
    }


    // ---------- 入帳內容 ----------

    private fun reached(s: FinanceSnapshot) = DueItems.list(s, through = today)
    private fun List<DueItem>.entry(key: String) = flatMap { it.entries }.single { it.postingKey == key }
    private fun List<DueItem>.item(key: String) = single { it.key == key }

    /** 把清單上的項目都照建議記下。 */
    private fun recordAll(s: FinanceSnapshot, items: List<DueItem>): FinanceSnapshot =
        s.copy(ledger = s.ledger + items.flatMap { DueItems.record(s, it, DueItems.defaultChoice(s, it)).entries })

    // ---------- 清單與建議金額 ----------

    @Test fun `8／31 起算，到 9／14 已到期的項目與建議金額：每筆都可以手算`() {
        val dues = reached(snapshot())
        assertEquals("不會自動寫入任何記帳", listOf(history), snapshot().ledger)

        // 9/1 分期第 1 期：本金 12,000 ÷ 6 = 2,000 入 B 卡（不重算預算）、手續費 100（算支出），同一個項目
        dues.item("inst:7:1").run {
            assertEquals(DueKind.INSTALLMENT, kind)
            assertEquals(2_100L, amount)
            assertFalse("分期金額不能改", amountEditable)
        }
        dues.entry("inst:7:1").run { assertEquals(2_000L, amount); assertEquals(CARD_B, accountId); assertFalse(countsForBudget) }
        dues.entry("instfee:7:1").run { assertEquals(100L, amount); assertTrue(countsForBudget) }

        // 9/5 貸款：120,000 月利率 1%，12 期本息平均攤還，月付 10,662 = 利息 1,200 ＋ 本金 9,462
        dues.item("loan:5:2026-09").run { assertEquals(10_662L, amount); assertEquals("貸款 月繳", title); assertTrue(choosesAccount) }
        assertEquals(1_200L, dues.entry("loan:5:2026-09:interest").amount)
        assertEquals(9_462L, dues.entry("loan:5:2026-09").amount)
        assertEquals(LoanAmortization.firstPayment(120_000, 12.0, 12, RepaymentMethod.EQUAL_PAYMENT), 10_662L)

        // 9/5 薪資
        dues.item("plan:101:2026-09:5").run {
            assertEquals(50_000L, amount); assertTrue(isIncome); assertEquals(BANK, defaultAccountId)
            assertEquals(EntrySource.DUE, entries.single().source)
        }

        // 9/3 繳 8/5 那期帳單：建議金額照上一期的繳法 4,000（R-CARD-26）；全額 = 帳單 40,000，還沒輸入帳單所以沒有最低
        dues.entry("cardpay:3:2026-08").run { assertEquals(4_000L, amount); assertEquals(BANK, accountId); assertEquals(CARD, toAccountId) }
        dues.item("cardpay:3:2026-08").run {
            assertEquals(CardRules.PaymentAssumption(CardRules.PaymentAssumption.Source.LAST_AMOUNT, 4_000), assumption)
            assertEquals(CardRules.PaymentOptions(full = 40_000, minimum = null), payOptions)
            assertEquals(LocalDate.of(2026, 8, 5), statementDate)
        }
        // 9/5 結帳：8/5 那期帳單 40,000 繳了 4,000，沒繳清的 36,000 × 12% ÷ 12 = 360
        assertEquals(360L, dues.item("cardint:3:2026-09").amount)
        val order = dues.map { it.key }
        assertTrue("先繳上一期、再結帳計息", order.indexOf("cardpay:3:2026-08") < order.indexOf("cardint:3:2026-09"))

        // 9/10 房租：支出，可以選支付方式，預設計畫的「轉帳」
        dues.item("plan:201:2026-09:10").run {
            assertEquals(20_000L, amount); assertTrue(choosesMethod); assertEquals(PaymentMethod.TRANSFER, method)
        }

        // 9/12 繳 B 卡：計畫 9,000，但 B 卡只欠 3,000 ＋ 分期 2,000 ＋ 手續費 100 = 5,100（R-PAY-02）
        assertEquals(5_100L, dues.item("plan:301:2026-09:12").amount)

        // 繳給依合約繳款的卡片，沒標額外還款不列（R-PAY-01）
        assertTrue(dues.none { it.item?.id == PAY_CARD })
        assertEquals("分期、貸款、薪資、利息、繳卡費、房租、繳 B 卡", 7, dues.size)

        // 本月還沒到期的也列出（9/20），可以提早記下
        val month = DueItems.list(snapshot())
        month.item("plan:202:2026-09:20").run { assertFalse(isDue(today)); assertEquals(1_000L, amount) }
        assertEquals(8, month.size)
    }

    @Test fun `快到期：3 天內到期或已過期沒記下的才提示（R-DUE-07）`() {
        fun due(date: LocalDate) = DueItem(key = "k", kind = DueKind.PLAN, date = date, title = "t", entries = emptyList())
        assertTrue("上個月逾期", due(LocalDate.of(2026, 8, 20)).isSoon(today))
        assertTrue("今天之前", due(LocalDate.of(2026, 9, 10)).isSoon(today))
        assertTrue("今天", due(today).isSoon(today))
        assertTrue("第 3 天", due(LocalDate.of(2026, 9, 17)).isSoon(today))
        assertFalse("第 4 天", due(LocalDate.of(2026, 9, 18)).isSoon(today))
    }

    @Test fun `起算日（含）以前到期的視為已在餘額裡；沒有起算日時從今天算`() {
        assertTrue(reached(snapshot(from = null)).isEmpty())
        assertTrue(reached(snapshot(from = today)).isEmpty())
        assertEquals("只剩今天以後的 9/20", listOf("plan:202:2026-09:20"), DueItems.list(snapshot(from = null)).map { it.key })
    }

    @Test fun `記下的不再列出；選「這個月沒有」的也不再列出；刪掉記帳就回來`() {
        val s = snapshot()
        val first = reached(s)
        val recorded = recordAll(s, first)
        assertTrue(reached(recorded).isEmpty())

        val skipped = s.copy(postedKeys = setOf("plan:201:2026-09:10"))
        assertTrue(reached(skipped).none { it.key == "plan:201:2026-09:10" })

        // 刪掉房租那筆：回到清單（R-REC-EDIT-07）
        val deleted = recorded.copy(ledger = recorded.ledger.filter { it.itemId != RENT })
        assertEquals(listOf("plan:201:2026-09:10"), reached(deleted).map { it.key })
        assertEquals(listOf("loan:5:2026-09", "loan:5:2026-09:interest"), DueItems.groupKeys("loan:5:2026-09:interest"))
        assertEquals(listOf("inst:7:1", "instfee:7:1"), DueItems.groupKeys("instfee:7:1"))
        assertEquals(5L, DueItems.loanIdOf("loan:5:2026-09"))
    }

    @Test fun `自己已經記了一部分：只列剩下的；用沒規劃的支付方式記的也算；已確認完成的不列`() {
        val manual = LedgerEntry(date = LocalDate.of(2026, 9, 3), type = FlowType.EXPENSE, amount = 8_000, itemId = RENT, method = PaymentMethod.TRANSFER, accountId = BANK)
        assertEquals("20,000 − 8,000", 12_000L, reached(snapshot(ledger = listOf(manual))).item("plan:201:2026-09:10").amount)

        val cash = manual.copy(method = PaymentMethod.CASH, amount = 20_000)
        assertTrue("計畫轉帳、實際付現金，一樣算付過", reached(snapshot(ledger = listOf(cash))).none { it.item?.id == RENT })

        val done = ItemActual(RENT, 2026, 9, ActualStatus.DONE, today)
        assertTrue(reached(snapshot(actuals = listOf(done))).none { it.item?.id == RENT })
    }

    @Test fun `標成額外還款的轉帳才列出`() {
        // 9/12 時欠 40,000 − 4,000 ＋ 360 = 36,360，額外還 5,000 不受限
        assertEquals(5_000L, reached(snapshot(extraRepayment = true)).item("plan:302:2026-09:12").amount)
    }

    @Test fun `逐期計息：每期結帳時算上一期沒繳清的；起算前就截止的那期視為繳清`() {
        // 7/31 起算：7/5 那期的截止日 8/3 在起算之後 → 8/3 繳 4,000；8/5 結帳 (40,000 − 4,000) × 1% = 360
        val s = snapshot(from = LocalDate.of(2026, 7, 31))
        val dues = reached(s)
        assertEquals(4_000L, dues.item("cardpay:3:2026-07").amount)
        assertEquals(360L, dues.item("cardint:3:2026-08").amount)
        dues.item("cardint:3:2026-08").run { assertTrue("8 月的是上個月到期", isOverdue(today)) }
        // 8/5 帳單 = 40,000 − 4,000 ＋ 360 = 36,360；9/3 繳 4,000；9/5 結帳 32,360 × 1% = 323.6 → 324
        assertEquals(36_360L, dues.item("cardpay:3:2026-08").payOptions!!.full)
        assertEquals(324L, dues.item("cardint:3:2026-09").amount)
        // 8/31 起算：7/5 那期 8/3 就截止了，當天輸入的餘額已經反映 → 8/5 不計息
        assertTrue(reached(snapshot()).none { it.key == "cardint:3:2026-08" })
    }

    @Test fun `帳單上的最低應繳：輸入後「最低」選項用帳單上的金額；每期自己選方式，記下後推估跟著變`() {
        val statement = CardStatement(CARD, 2026, 8, amount = 40_000, minimumPayment = 2_500)
        val s = snapshot().copy(cardStatements = listOf(statement))
        val pay = reached(s).item("cardpay:3:2026-08")
        assertEquals(CardRules.PaymentOptions(full = 40_000, minimum = 2_500), pay.payOptions)
        assertEquals("建議金額照上一期的 4,000", 4_000L, pay.amount)
        assertEquals("請選這一期的繳款方式", DueItems.validate(s, pay, DueChoice(4_000, accountId = BANK)))
        assertEquals(
            "少於帳單上的最低應繳 $2,500，可能被收違約金並影響信用紀錄",
            DueItems.warning(s, pay, DueChoice(2_000, accountId = BANK, payMode = CardPayMode.FREE)),
        )
        // 這一期臨時改全額：記下的金額與備註照選的方式
        val full = DueChoice(40_000, accountId = BANK, date = LocalDate.of(2026, 9, 3), payMode = CardPayMode.FULL)
        DueItems.record(s, pay, full).entries.single().run {
            assertEquals(40_000L, amount)
            assertEquals("繳 依帳單繳款的卡（全額）", note)
        }
        // 9/3 全額繳清 → 9/5 結帳不計息；之後推估改成全額繳清
        val paidFull = s.copy(
            ledger = DueItems.record(s, pay, full).entries,
            accounts = s.accounts.map { if (it.id == CARD) it.copy(balance = 0) else it },
        )
        assertTrue(reached(paidFull).none { it.key == "cardint:3:2026-09" })
        assertEquals(CardRules.PaymentAssumption.Source.LAST_FULL, CardRules.assumption(paidFull, paidFull.account(CARD)!!).source)
    }

    // ---------- 點下去：選支付方式、改金額 ----------

    @Test fun `記下時可以改支付方式、卡片、金額與扣款帳戶`() {
        val s = snapshot()
        val rent = reached(s).item("plan:201:2026-09:10")
        DueItems.record(s, rent, DueChoice(20_000, PaymentMethod.CREDIT_CARD, cardId = CARD_B)).entries.single().run {
            assertEquals(PaymentMethod.CREDIT_CARD, method)
            assertEquals(CARD_B, accountId)
            assertEquals("識別碼不變，記下後不再列出", "plan:201:2026-09:10", postingKey)
            assertEquals("付款日預設今天（F02）", today, date)
            assertEquals("預算仍算在 9 月", java.time.YearMonth.of(2026, 9), budgetMonth)
        }
        assertEquals(
            "到期日當天就付了：選到期日",
            LocalDate.of(2026, 9, 10),
            DueItems.record(s, rent, DueChoice(20_000, PaymentMethod.TRANSFER, date = LocalDate.of(2026, 9, 10))).entries.single().date,
        )
        assertEquals("付款日不能晚於今天", DueItems.validate(s, rent, DueChoice(20_000, PaymentMethod.TRANSFER, date = today.plusDays(1))))
        DueItems.record(s, rent, DueChoice(20_000, PaymentMethod.CREDIT_CARD, cardId = null)).entries.single().run {
            assertNull("不指定卡片", accountId)
        }

        // 貸款改金額：利息不變，差額算本金
        val loan = reached(s).item("loan:5:2026-09")
        val paid = DueItems.record(s, loan, DueChoice(10_000, accountId = BANK))
        assertEquals(listOf(8_800L, 1_200L), paid.entries.map { it.amount })
        assertEquals("剩下 11 期", LOAN to 11, paid.loanRemaining)

        // 還沒到期就記下：日期用今天
        val later = DueItems.list(s).item("plan:202:2026-09:20")
        assertEquals(today, DueItems.record(s, later, DueItems.defaultChoice(s, later)).entries.single().date)

        // 檢查
        assertEquals("金額要大於 0", DueItems.validate(s, rent, DueChoice(0, PaymentMethod.CASH)))
        assertEquals("請選扣款帳戶", DueItems.validate(s, loan, DueChoice(10_662, accountId = CARD)))
        assertNull(DueItems.validate(s, loan, DueChoice(10_662, accountId = BANK)))
    }

    // ---------- 試算與分期未入帳本金跟著清單走 ----------

    @Test fun `試算：到期還沒記下的放今天所在的半月；記下後不再預測`() {
        val s = snapshot()
        val start = Period.of(today)
        fun rentEvents(snap: FinanceSnapshot) = BaselineBuilder.build(snap).events.filter { it.itemId == RENT && it.period.year == 2026 && it.period.month == 9 }
        fun loanEvents(snap: FinanceSnapshot) = BaselineBuilder.build(snap).events.filter { it.relatedAccountId == LOAN }

        assertEquals(listOf(start to 20_000L), rentEvents(s).map { it.period to it.amount })
        assertEquals("9/5 還沒記下，放在今天這一期", start, loanEvents(s).minOf { it.period })

        val recorded = recordAll(s, reached(s)).let { snap ->
            // 貸款記下後剩餘期數減一（repository 會寫回帳戶）
            snap.copy(accounts = snap.accounts.map { if (it.id == LOAN) it.copy(balance = 120_000 - 9_462, loan = it.loan!!.copy(remainingMonths = 11)) else it })
        }
        assertTrue(rentEvents(recorded).isEmpty())
        assertEquals("下一期是 10/5", Period(2026, 10, Half.FIRST), loanEvents(recorded).minOf { it.period })

        // 提早記下 9/20 那筆：9 月不再預測
        val later = DueItems.list(s).item("plan:202:2026-09:20")
        val early = s.copy(ledger = DueItems.record(s, later, DueItems.defaultChoice(s, later)).entries)
        assertTrue(BaselineBuilder.build(early).events.none { it.itemId == LATER && it.period.year == 2026 && it.period.month == 9 })
        assertTrue(BaselineBuilder.build(s).events.any { it.itemId == LATER && it.period == Period(2026, 9, Half.SECOND) })
    }

    @Test fun `分期：到期還沒記下的那一期仍算未入帳本金`() {
        val s = snapshot()
        val card = AccountSummaryCalculator.overview(s).cards.single { it.account.id == CARD_B }
        assertEquals("6 期都還沒入帳", 12_000L, card.pendingInstallmentPrincipal)
        val recorded = recordAll(s, reached(s).filter { it.kind == DueKind.INSTALLMENT })
        assertEquals("第 1 期記下後剩 10,000", 10_000L, AccountSummaryCalculator.overview(recorded).cards.single { it.account.id == CARD_B }.pendingInstallmentPrincipal)
    }

    // ---------- 本週檢查（R-DUE-05） ----------

    @Test fun `本週檢查：先處理到期還沒記下的，對帳推算已包含`() {
        val s = snapshot()
        assertEquals(reached(s).map { it.key }, CheckInRules.dueLines(s).map { it.key })
        val result = CheckInRules.build(
            s,
            CheckInInput(
                dues = mapOf(
                    "plan:101:2026-09:5" to DueDecision(DueCheck.PAID),
                    "loan:5:2026-09" to DueDecision(DueCheck.DIFFERENT_AMOUNT, 10_000),
                    "plan:201:2026-09:10" to DueDecision(DueCheck.SKIP),
                ),
            ),
        )
        assertEquals("同一天先貸款、再收入（R-ORD-01）", listOf(8_800L, 1_200L, 50_000L), result.dueEntries.map { it.amount })
        assertEquals(listOf("plan:201:2026-09:10"), result.skippedKeys)
        assertEquals(mapOf(LOAN to 11), result.loanRemaining)
        assertFalse(result.isEmpty)
        // 銀行推算：100,000 ＋ 50,000 − 10,000
        assertEquals(140_000L, CheckInRules.reconciles(s, result.entries).first { it.id == BANK }.computed)
    }

    // ---------- 試算的還款上限（R-PAY-02） ----------

    @Test fun `試算：還款最多還到欠款為 0，多的錢留在原帳戶`() {
        val start = Period(2026, 9, Half.SECOND)
        val input = ForecastInput(
            start, 2,
            listOf(AccountSeed(BANK, "銀行", AccountKind.BANK, 10_000), AccountSeed(CARD_B, "卡", AccountKind.CREDIT_CARD, 5_000)),
            listOf(
                FlowEvent(start, EventKind.TRANSFER, 9_000, "繳卡", fromAccountId = BANK, toAccountId = CARD_B),
                FlowEvent(start.next(), EventKind.TRANSFER, 9_000, "繳卡", fromAccountId = BANK, toAccountId = CARD_B),
            ),
            0,
        )
        val result = CashFlowEngine.run(input)
        result.periods[0].run {
            assertEquals(5_000L, cardPayments)
            assertEquals(0L, balances[CARD_B])
            assertEquals(5_000L, balances[BANK])
        }
        result.periods[1].run {
            assertEquals("已經還清，不再扣款", 0L, cardPayments)
            assertEquals(5_000L, balances[BANK])
        }
    }

    // ---------- 延期款（R-DEF） ----------

    private val sample = SampleHousehold.snapshot()
    private val deferral = Deferral(1, SampleHousehold.SUBSIDY, null, 2026, 9, 2026, 10, 5_000)
    private val october = sample.copy(today = LocalDate.of(2026, 10, 5), deferrals = listOf(deferral))

    @Test fun `延期款：下個月出現在到期確認，金額固定`() {
        val line = CheckInRules.confirmLines(october).single { it.deferral != null }
        assertEquals("deferral:1", line.key)
        assertEquals(5_000L, line.planned)
        assertTrue("9 月還沒到期", CheckInRules.confirmLines(sample.copy(deferrals = listOf(deferral))).none { it.deferral != null })
        assertTrue("過期沒付的也會出現", CheckInRules.confirmLines(october.copy(today = LocalDate.of(2026, 12, 1))).any { it.deferral != null })
        assertTrue("付清的不再出現", CheckInRules.confirmLines(october.copy(deferrals = listOf(deferral.copy(settled = true)))).none { it.deferral != null })
    }

    @Test fun `延期款：再延一次只改到期月份；付了就結清並記一筆`() {
        val again = CheckInRules.build(october, CheckInInput(confirms = mapOf("deferral:1" to ConfirmDecision(ConfirmChoice.POSTPONE))))
        assertTrue(again.entries.isEmpty())
        assertTrue("延期款不改月狀態", again.actuals.isEmpty())
        assertEquals(deferral.copy(dueYear = 2026, dueMonth = 11), again.deferrals.single())

        val paid = CheckInRules.build(october, CheckInInput(confirms = mapOf("deferral:1" to ConfirmDecision(ConfirmChoice.PAID))))
        paid.entries.single().run {
            assertEquals(FlowType.INCOME, type)
            assertEquals(5_000L, amount)
            assertEquals(SampleHousehold.BANK, accountId)
            assertEquals("deferral:1", postingKey)
        }
        assertTrue(paid.deferrals.single().settled)

        val less = CheckInRules.build(october, CheckInInput(confirms = mapOf("deferral:1" to ConfirmDecision(ConfirmChoice.DIFFERENT_AMOUNT, 4_000))))
        assertEquals(4_000L, less.entries.single().amount)
        assertTrue(less.deferrals.single().settled)
    }

    // ---------- 漏記取代（R-REC-03） ----------

    @Test fun `補登明細取代漏記差額：沿用差額的時間，超過的部分照今天記`() {
        val missed = RecordRules.missedMatch(sample, SampleHousehold.FOOD_CASH, PaymentMethod.CASH, today)!!
        assertEquals("示意資料 9/7 的漏記 120", 120L, missed.amount)
        assertNull("其他月份不配對", RecordRules.missedMatch(sample, SampleHousehold.FOOD_CASH, PaymentMethod.CASH, LocalDate.of(2026, 10, 1)))
        assertNull("其他付款方式不配對", RecordRules.missedMatch(sample, SampleHousehold.FOOD_CASH, PaymentMethod.CREDIT_CARD, today))

        val detail = LedgerEntry(date = today, type = FlowType.EXPENSE, amount = 80, itemId = SampleHousehold.LIVING, method = PaymentMethod.CASH, createdAt = 999)
        RecordRules.replaceMissed(missed, detail).run {
            assertEquals(40L, remainingMissed!!.amount)
            assertEquals(80L, this.detail.amount)
            assertEquals(missed.date, this.detail.date)
            assertEquals(missed.createdAt, this.detail.createdAt)
            assertNull(extra)
        }
        RecordRules.replaceMissed(missed, detail.copy(amount = 200)).run {
            assertNull("差額整筆沖掉", remainingMissed)
            assertEquals(120L, this.detail.amount)
            assertEquals(80L, extra!!.amount)
            assertEquals("超過的部分是新的花費", today, extra!!.date)
        }
    }

    // ---------- 未指定卡片的刷卡（R-CARD-05） ----------

    @Test fun `全部卡片一起對帳才吸收未指定卡片的刷卡`() {
        val d1 = LocalDate.of(2026, 9, 1)
        val d2 = LocalDate.of(2026, 9, 10)
        val together = BalanceRules.fullCardReconcile(
            mapOf(CARD to listOf(RecordMark(d1, 100), RecordMark(d2, 200)), CARD_B to listOf(RecordMark(d2, 200))),
        )
        assertEquals(RecordMark(d2, 200), together)
        assertNull(
            "只校正其中一張卡，不知道那些刷卡是不是這張卡",
            BalanceRules.fullCardReconcile(mapOf(CARD to listOf(RecordMark(d2, 200)), CARD_B to listOf(RecordMark(d1, 150)))),
        )
        assertNull(BalanceRules.fullCardReconcile(emptyMap()))

        val ledger = listOf(
            LedgerEntry(date = d1, type = FlowType.EXPENSE, amount = 300, method = PaymentMethod.CREDIT_CARD),
            LedgerEntry(date = LocalDate.of(2026, 9, 12), type = FlowType.EXPENSE, amount = 500, method = PaymentMethod.CREDIT_CARD),
            LedgerEntry(date = d2, type = FlowType.EXPENSE, amount = 12_000, method = PaymentMethod.CREDIT_CARD, installmentId = 7),
        )
        assertEquals("對帳之後的 500；分期消費不算", 500L, BalanceRules.unassignedCardSpending(ledger, together))
        assertEquals(800L, BalanceRules.unassignedCardSpending(ledger, null))
    }

    // ---------- 到期記下與到期確認的修改限制（R-REC-EDIT-06） ----------

    @Test fun `到期記下的款項只能改金額、同月日期與備註`() {
        val due = LedgerEntry(
            id = 50, date = LocalDate.of(2026, 9, 5), type = FlowType.EXPENSE, amount = 2_000, itemId = SampleHousehold.PARKING,
            method = PaymentMethod.TRANSFER, accountId = SampleHousehold.BANK, source = EntrySource.DUE, postingKey = "plan:401:2026-09:1",
        )
        val draft = RecordDraft(due)
        val ok = RecordEditForm.validate(draft.copy(amount = "2100", date = LocalDate.of(2026, 9, 3), note = "漲價"), sample)
        assertTrue(ok.errors.toString(), ok.ok)
        assertEquals("識別碼保留，才不會再列出一次", due.postingKey, ok.entry!!.postingKey)
        assertEquals("每月固定", RecordRules.sourceLabel(due))

        assertEquals(
            "到期記下的款項綁著原本的項目，不能換；要換請刪掉再自己記一筆",
            RecordEditForm.validate(draft.copy(itemId = SampleHousehold.LESSONS), sample).errors[RecordEditForm.Field.ITEM],
        )
        assertEquals(
            "到期記下的款項不能改付款方式；要改請刪掉再自己記一筆",
            RecordEditForm.validate(draft.copy(method = PaymentMethod.CASH), sample).errors[RecordEditForm.Field.METHOD],
        )
        assertEquals(
            "到期記下的款項只能在同一個月內改日期",
            RecordEditForm.validate(draft.copy(date = LocalDate.of(2026, 8, 31)), sample).errors[RecordEditForm.Field.DATE],
        )
        val confirmed = draft.copy(original = due.copy(source = EntrySource.CONFIRMED, postingKey = null))
        assertEquals(
            "到期確認只能在同一個月內改日期",
            RecordEditForm.validate(confirmed.copy(date = LocalDate.of(2026, 8, 31)), sample).errors[RecordEditForm.Field.DATE],
        )
    }
}
