package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.SUBSIDY
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ReportInput
import tw.myfsl.app.core.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInTest {

    private val snapshot = SampleHousehold.snapshot()
    private val pool = CheckInRules.CARD_ROW_ID
    private val postingDays = snapshot.settings.cardPostingDays

    private fun cardLine() = "line:$CAR_SERVICE:CREDIT_CARD"
    private fun subsidyLine() = "line:$SUBSIDY:-"

    // ---------- 要檢查什麼 ----------

    @Test fun `示意資料沒有信封回報，只有 2 項到期確認`() {
        assertTrue(CheckInRules.reportLines(snapshot).isEmpty())
        val confirms = CheckInRules.confirmLines(snapshot)
        assertEquals(listOf(SUBSIDY, CAR_SERVICE), confirms.map { it.item.id })
        confirms.single { it.item.id == CAR_SERVICE }.run {
            assertEquals(12_000L, planned)
            assertEquals(0L, recorded)
            assertEquals(PaymentMethod.CREDIT_CARD, method)
        }
        confirms.single { it.item.id == SUBSIDY }.run {
            assertEquals(5_000L, planned)
            assertNull(method)
        }
    }

    @Test fun `帳戶對帳：流動帳戶各一列，信用卡合併成一列`() {
        val rows = CheckInRules.reconciles(snapshot)
        assertEquals(listOf(BANK, CASH, pool), rows.map { it.id })
        assertEquals(listOf("薪轉帳戶", "零用現金", "信用卡合計"), rows.map { it.name })

        rows[0].run {
            assertEquals(120_000L, computed)
            assertEquals(PaymentMethod.TRANSFER, method)
            assertEquals(LESSONS, defaultItem?.id)
        }
        rows[1].run {
            assertEquals(15_000L, computed)
            assertEquals(PaymentMethod.CASH, method)
            assertEquals(LIVING, defaultItem?.id)
        }
        rows[2].run {
            assertEquals(listOf(CARD_A, CARD_B), accounts.map { it.id })
            assertEquals(105_000L, computed)
            assertEquals(12_100L, pendingRecent)
            assertEquals(LIVING, defaultItem?.id)
            assertTrue(isCard)
        }
        // 未指定卡片的刷卡算進信用卡合計
        assertEquals(105_800L, CheckInRules.reconciles(snapshot.copy(unassignedCardSpending = 800)).last().computed)
    }

    // ---------- 差額分類 ----------

    @Test fun `錢包：少了是漏記，一樣是已對帳，多了可能記重複`() {
        val cash = CheckInRules.reconciles(snapshot).single { it.id == CASH }
        fun check(actual: Long, kind: DiffKind, text: String, resolution: Resolution) {
            assertEquals(kind, cash.kind(actual))
            assertEquals(text, CheckInRules.message(kind, cash.missed(actual), isCard = false, postingDays = postingDays))
            assertEquals(resolution, CheckInRules.defaultResolution(kind))
        }
        check(14_500, DiffKind.MISSED, "少了 $500，可能是漏記", Resolution.ASSIGN_ITEM)
        check(15_000, DiffKind.MATCHED, "和推算一樣", Resolution.BALANCE_ONLY)
        check(15_300, DiffKind.OVER_RECORDED, "多了 $300，可能是記重複或有收入沒記", Resolution.BALANCE_ONLY)
    }

    @Test fun `信用卡：欠款比推算少且在最近幾天刷卡範圍內，視為還沒入帳`() {
        val card = CheckInRules.reconciles(snapshot).single { it.id == pool }
        fun check(actual: Long, kind: DiffKind, text: String, resolution: Resolution) {
            assertEquals(kind, card.kind(actual))
            assertEquals(text, CheckInRules.message(kind, card.missed(actual), isCard = true, postingDays = postingDays))
            assertEquals(resolution, CheckInRules.defaultResolution(kind))
        }
        check(105_650, DiffKind.MISSED, "銀行多 $650，可能有刷卡沒記", Resolution.ASSIGN_ITEM)
        check(105_000, DiffKind.MATCHED, "和推算一樣", Resolution.BALANCE_ONLY)
        check(104_000, DiffKind.PENDING, "銀行少 $1,000，可能是最近 5 天的刷卡還沒入帳", Resolution.SKIP)
        check(92_900, DiffKind.PENDING, "銀行少 $12,100，可能是最近 5 天的刷卡還沒入帳", Resolution.SKIP)
        check(92_899, DiffKind.OVER_RECORDED, "銀行少 $12,101，可能是記重複或有退款", Resolution.BALANCE_ONLY)
    }

    @Test fun `差額預設歸到的項目：本月該支付方式計畫最大的可調項目`() {
        assertEquals(LIVING, CheckInRules.defaultItemFor(snapshot, PaymentMethod.CASH)?.id)
        assertEquals(LIVING, CheckInRules.defaultItemFor(snapshot, PaymentMethod.CREDIT_CARD)?.id)
        assertEquals(LESSONS, CheckInRules.defaultItemFor(snapshot, PaymentMethod.TRANSFER)?.id)
    }

    // ---------- 寫入 ----------

    @Test fun `錢包少了 500：存成漏記差額並校正餘額，之後進度也跟著變`() {
        val result = CheckInRules.build(
            snapshot,
            CheckInInput(reconciles = mapOf(CASH to ReconcileDecision(balances = mapOf(CASH to 14_500)))),
        )
        val entry = result.entries.single()
        assertEquals(
            LedgerEntry(
                date = snapshot.today, type = FlowType.EXPENSE, amount = 500, itemId = LIVING,
                method = PaymentMethod.CASH, accountId = CASH, source = EntrySource.MISSED,
            ),
            entry,
        )
        assertEquals(mapOf(CASH to 14_500L), result.balances)
        assertEquals(1, result.missedEntries.size)
        assertEquals(500L, result.missedTotal)
        assertTrue(result.actuals.isEmpty())

        // 加上這筆之後，生活費・現金本月剩 9,000 − 4,900
        val after = snapshot.copy(ledger = snapshot.ledger + result.entries)
        val hint = EntryRules.budgetHint(after, after.item(LIVING)!!, PaymentMethod.CASH, 0)
        assertEquals("生活費・現金 本月剩 $4,100", EntryRules.hintText(hint))
        assertEquals("本月漏記 2 筆 · $620", RecordRules.missedLabel(RecordRules.missedSummary(after)))
    }

    @Test fun `差額可以改歸到別的項目、只校正餘額，或先不處理`() {
        fun build(decision: ReconcileDecision) = CheckInRules.build(snapshot, CheckInInput(reconciles = mapOf(CASH to decision)))

        val toHousehold = build(ReconcileDecision(balances = mapOf(CASH to 14_500), itemId = HOUSEHOLD))
        assertEquals(HOUSEHOLD, toHousehold.entries.single().itemId)

        val balanceOnly = build(ReconcileDecision(balances = mapOf(CASH to 14_500), resolution = Resolution.BALANCE_ONLY))
        assertTrue(balanceOnly.entries.isEmpty())
        assertEquals(mapOf(CASH to 14_500L), balanceOnly.balances)

        val skipped = build(ReconcileDecision(balances = mapOf(CASH to 14_500), resolution = Resolution.SKIP))
        assertTrue(skipped.isEmpty)

        // 沒有填餘額就不處理這個帳戶
        assertTrue(CheckInRules.build(snapshot, CheckInInput(reconciles = mapOf(CASH to ReconcileDecision()))).isEmpty)
    }

    @Test fun `信用卡合計：每張卡都要填；漏記的刷卡不歸到特定卡片`() {
        fun build(balances: Map<Long, Long>) =
            CheckInRules.build(snapshot, CheckInInput(reconciles = mapOf(pool to ReconcileDecision(balances = balances))))

        assertTrue("少填一張卡就跳過", build(mapOf(CARD_A to 60_650L)).isEmpty)

        val result = build(mapOf(CARD_A to 60_650L, CARD_B to 45_000L))
        val entry = result.entries.single()
        assertEquals(650L, entry.amount)
        assertEquals(LIVING, entry.itemId)
        assertEquals(PaymentMethod.CREDIT_CARD, entry.method)
        assertNull("未指定卡片", entry.accountId)
        assertEquals(mapOf(CARD_A to 60_650L, CARD_B to 45_000L), result.balances)

        // 還沒入帳：預設先不處理
        assertTrue(build(mapOf(CARD_A to 59_000L, CARD_B to 45_000L)).isEmpty)
    }

    @Test fun `到期確認：已付會補記一筆，並算進當期卡片推算`() {
        val input = CheckInInput(confirms = mapOf(cardLine() to ConfirmDecision(ConfirmChoice.PAID)))
        val result = CheckInRules.build(snapshot, input)
        val entry = result.entries.single()
        assertEquals(12_000L, entry.amount)
        assertEquals(CAR_SERVICE, entry.itemId)
        assertEquals(EntrySource.CONFIRMED, entry.source)
        assertNull(entry.accountId)
        assertEquals(ActualStatus.DONE, result.actuals.single().status)
        assertTrue("到期確認不算漏記", result.missedEntries.isEmpty())

        // 對帳時卡片推算已包含補記的 12,000
        assertEquals(117_000L, CheckInRules.reconciles(snapshot, result.entries).last().computed)
        val both = CheckInRules.build(
            snapshot,
            input.copy(reconciles = mapOf(pool to ReconcileDecision(balances = mapOf(CARD_A to 72_000L, CARD_B to 45_000L)))),
        )
        assertEquals(1, both.entries.size)
        assertEquals(mapOf(CARD_A to 72_000L, CARD_B to 45_000L), both.balances)
    }

    @Test fun `到期確認：已經記過帳只補差額`() {
        val logged = snapshot.copy(
            ledger = snapshot.ledger + LedgerEntry(
                date = snapshot.today, type = FlowType.EXPENSE, amount = 11_000, itemId = CAR_SERVICE,
                method = PaymentMethod.CREDIT_CARD, accountId = CARD_A, note = "定期保養",
            ),
        )
        assertEquals(11_000L, CheckInRules.confirmLines(logged).single { it.item.id == CAR_SERVICE }.recorded)
        val result = CheckInRules.build(logged, CheckInInput(confirms = mapOf(cardLine() to ConfirmDecision(ConfirmChoice.PAID))))
        assertEquals(1_000L, result.entries.single().amount)
    }

    @Test fun `到期確認：金額不同、延到下月`() {
        val different = CheckInRules.build(
            snapshot,
            CheckInInput(confirms = mapOf(subsidyLine() to ConfirmDecision(ConfirmChoice.DIFFERENT_AMOUNT, 4_500))),
        )
        val entry = different.entries.single()
        assertEquals(FlowType.INCOME, entry.type)
        assertEquals(4_500L, entry.amount)
        assertEquals(BANK, entry.accountId)
        assertEquals(EntrySource.CONFIRMED, entry.source)
        assertEquals(124_500L, CheckInRules.reconciles(snapshot, different.entries).first().computed)

        val postponed = CheckInRules.build(
            snapshot,
            CheckInInput(confirms = mapOf(subsidyLine() to ConfirmDecision(ConfirmChoice.POSTPONE))),
        )
        assertTrue(postponed.entries.isEmpty())
        assertEquals(ActualStatus.POSTPONED, postponed.actuals.single().status)
        // 延期變成一筆獨立的延期款：金額固定、下個月到期（R-DEF-01）
        val deferral = postponed.deferrals.single()
        assertEquals(5_000L, deferral.amount)
        assertEquals(2026 to 10, deferral.dueYear to deferral.dueMonth)
        assertEquals(2026 to 9, deferral.fromYear to deferral.fromMonth)

        // 確認過的項目不再出現
        val done = snapshot.copy(actuals = different.actuals)
        assertTrue(CheckInRules.confirmLines(done).none { it.item.id == SUBSIDY })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `金額不同必須輸入金額`() {
        CheckInRules.build(snapshot, CheckInInput(confirms = mapOf(cardLine() to ConfirmDecision(ConfirmChoice.DIFFERENT_AMOUNT))))
    }

    // ---------- 分信封時的每週回報 ----------

    @Test fun `每週回報：現金問還剩多少，其他問累計，差額存成漏記`() {
        val envelope = snapshot.copy(
            items = snapshot.items.map { if (it.id == LIVING) it.copy(tracking = TrackingMode.REPORT) else it },
        )
        val lines = CheckInRules.reportLines(envelope)
        assertEquals(2, lines.size)
        val cash = lines.single { it.method == PaymentMethod.CASH }
        assertEquals(ReportInput.REMAINING, cash.input)
        assertEquals(4_600L, cash.prefill)
        val card = lines.single { it.method == PaymentMethod.CREDIT_CARD }
        assertEquals(ReportInput.SPENT_TO_DATE, card.input)
        assertEquals(9_800L, card.prefill)

        assertEquals(4_400L, CheckInRules.spentFromInput(ReportInput.REMAINING, 9_000, 4_600))
        assertEquals(0L, CheckInRules.spentFromInput(ReportInput.REMAINING, 9_000, 9_500))
        assertEquals(0L, CheckInRules.spentFromInput(ReportInput.SPENT_TO_DATE, 9_000, -5))
        assertEquals(120L, CheckInRules.difference(4_520, 4_400))
        assertEquals(-50L, CheckInRules.difference(4_350, 4_400))

        val result = CheckInRules.build(
            envelope,
            CheckInInput(reports = mapOf(cash.line to 4_480, card.line to 9_800)),
        )
        val entry = result.entries.single()
        assertEquals(120L, entry.amount)
        assertEquals(LIVING, entry.itemId)
        assertEquals(PaymentMethod.CASH, entry.method)
        assertEquals(EntrySource.MISSED, entry.source)

        // 回報比記帳少：存成多記差額
        val over = CheckInRules.build(envelope, CheckInInput(reports = mapOf(cash.line to 4_700)))
        assertEquals(-100L, over.entries.single().amount)
        assertTrue(over.missedEntries.isEmpty())
    }

    // ---------- 紀錄頁與本期頁 ----------

    @Test fun `紀錄標示與本月合計`() {
        val manual = snapshot.ledger.single { it.note == "買菜" }
        val missed = snapshot.ledger.single { it.source == EntrySource.MISSED }
        assertNull(RecordRules.sourceLabel(manual))
        assertEquals("漏記差額", RecordRules.sourceLabel(missed))
        assertEquals("多記差額", RecordRules.sourceLabel(missed.copy(amount = -100)))
        assertEquals("到期確認", RecordRules.sourceLabel(manual.copy(source = EntrySource.CONFIRMED)))

        assertEquals(RecordRules.MonthTotals(expense = 16_800, income = 0), RecordRules.monthTotals(snapshot, 2026, 9))
        assertEquals(RecordRules.MissedSummary(1, 120), RecordRules.missedSummary(snapshot))
        assertEquals("本月漏記 1 筆 · $120", RecordRules.missedLabel(RecordRules.missedSummary(snapshot)))
        assertNull(RecordRules.missedLabel(RecordRules.MissedSummary(0, 0)))
    }
}
