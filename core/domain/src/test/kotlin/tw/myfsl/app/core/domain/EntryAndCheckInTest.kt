package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.CONTEST
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.PAY_CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.sample.SampleHousehold.SUBSIDY
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EntryAndCheckInTest {

    private val snapshot = SampleHousehold.snapshot()
    private fun item(id: Long): PlanItem = snapshot.item(id)!!

    // ---------- 金額鍵盤 ----------

    @Test fun `金額鍵盤：前導零、00、上限 7 位、倒退`() {
        var s = ""
        s = AmountInput.press(s, "0"); assertEquals("", s)
        s = AmountInput.press(s, "00"); assertEquals("", s)
        s = AmountInput.press(s, "1"); s = AmountInput.press(s, "2"); assertEquals("12", s)
        s = AmountInput.press(s, "00"); assertEquals("1200", s)
        s = AmountInput.backspace(s); assertEquals("120", s)
        assertEquals(120L, AmountInput.value(s))
        var big = ""
        repeat(8) { big = AmountInput.press(big, "9") }
        assertEquals("9999999", big)
        assertEquals("9999999", AmountInput.press("999999", "00").let { if (it.length > 7) "x" else AmountInput.press("999999", "9") })
        assertEquals("999999", AmountInput.press("999999", "00"))
        assertEquals("", AmountInput.backspace(""))
        assertEquals("0", AmountInput.display(""))
        assertEquals("9,999,999", AmountInput.display(big))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `金額鍵盤：非數字按鍵不接受`() {
        AmountInput.press("1", "a")
    }

    // ---------- 預設支付方式與卡片 ----------

    @Test fun `預設支付方式：這個項目上次用的 優先，否則整體最常用的，都沒有則現金`() {
        // 示意資料最後一筆現金伙食是現金（早餐 $85）
        assertEquals(PaymentMethod.CASH, EntryRules.defaultMethod(snapshot, item(LIVING)))
        val fresh = snapshot.copy(ledger = emptyList())
        assertEquals("完全沒有紀錄時用現金", PaymentMethod.CASH, EntryRules.defaultMethod(fresh, item(LIVING)))
        // 這個項目沒記過，但整體最常刷卡時帶刷卡
        val mostlyCard = snapshot.copy(
            ledger = List(6) { expense(FUEL, PaymentMethod.CREDIT_CARD, 500, null).copy(id = 600L + it) },
        )
        assertEquals(PaymentMethod.CREDIT_CARD, EntryRules.defaultMethod(mostlyCard, item(CONTEST)))
        val used = snapshot.copy(ledger = snapshot.ledger + expense(LIVING, PaymentMethod.TRANSFER, 500, BANK).copy(id = 100))
        assertEquals(PaymentMethod.TRANSFER, EntryRules.defaultMethod(used, item(LIVING)))
        assertEquals(PaymentMethod.CREDIT_CARD, EntryRules.defaultMethod(snapshot, item(FUEL)))
        // 比賽報名沒記過：示意資料現金 5 筆多於刷卡 4 筆，所以帶現金
        assertEquals(PaymentMethod.CASH, EntryRules.defaultMethod(snapshot, item(CONTEST)))
        val brandNew = PlanItem(990, "新項目", 5, FlowType.EXPENSE)
        assertEquals(PaymentMethod.CASH, EntryRules.defaultMethod(snapshot.copy(items = snapshot.items + brandNew), brandNew))
        // 本週檢查產生的漏記差額不算「上次用的」
        val missedOnly = snapshot.copy(
            ledger = listOf(expense(CONTEST, PaymentMethod.CREDIT_CARD, 300, null).copy(source = EntrySource.MISSED)),
        )
        assertEquals(PaymentMethod.CASH, EntryRules.defaultMethod(missedOnly, item(CONTEST)))
        assertNull(EntryRules.defaultMethod(snapshot, item(SALARY)))
    }

    @Test fun `預設卡片：上次刷的卡；指定信用卡關閉或沒刷過時為不指定`() {
        // 示意資料最後一筆生活費刷卡是信用卡 A（午餐 $150）
        assertEquals(CARD_A, EntryRules.defaultCard(snapshot, item(LIVING)))
        assertNull(EntryRules.defaultCard(snapshot.copy(ledger = emptyList()), item(LIVING)))
        assertNull(EntryRules.defaultCard(snapshot.copy(settings = snapshot.settings.copy(pickCard = false)), item(LIVING)))
        val archived = snapshot.copy(accounts = snapshot.accounts.map { if (it.id == CARD_A) it.copy(archived = true) else it })
        assertNull(EntryRules.defaultCard(archived, item(LIVING)))
        assertNull("交通油資上次未指定卡片", EntryRules.defaultCard(snapshot, item(FUEL)))
    }

    // ---------- 預算提示 ----------

    @Test fun `預算提示文字`() {
        fun text(id: Long, method: PaymentMethod?, amount: Long) = EntryRules.hintText(EntryRules.budgetHint(snapshot, item(id), method, amount))
        // 記帳提示看的是項目整筆預算（R-MIX-01）：現金列 9,000 ＋ 信用卡列 16,000 = 25,000，已花 14,200
        assertEquals("生活費 本月剩 $10,800", text(LIVING, PaymentMethod.CASH, 0))
        assertEquals("記下後 生活費 剩 $10,680", text(LIVING, PaymentMethod.CASH, 120))
        assertEquals("記下後超出 生活費 計畫 $1,200", text(LIVING, PaymentMethod.CASH, 12_000))
        assertEquals("生活費 本月剩 $10,800", text(LIVING, PaymentMethod.CREDIT_CARD, 0))
        // 換一種方式付，仍然算在同一筆預算裡（R-MIX-01）
        assertEquals("生活費 本月剩 $10,800", text(LIVING, PaymentMethod.TRANSFER, 0))
        assertEquals("記下後 生活費 剩 $10,300", text(LIVING, PaymentMethod.TRANSFER, 500))
        assertEquals("不在本月計畫內，會列入計畫外支出", text(CONTEST, PaymentMethod.CASH, 100))
        assertEquals("不影響支出進度", text(SALARY, null, 65_000))
        val overspent = snapshot.copy(ledger = snapshot.ledger + expense(HOUSEHOLD, PaymentMethod.CASH, 1_000, CASH))
        assertEquals("家用 本月已超出 $300", EntryRules.hintText(EntryRules.budgetHint(overspent, item(HOUSEHOLD), PaymentMethod.CASH, 0)))
    }

    @Test fun `預算提示警示色`() {
        fun warn(id: Long, method: PaymentMethod?, amount: Long) = EntryRules.isWarning(EntryRules.budgetHint(snapshot, item(id), method, amount))
        assertFalse(warn(LIVING, PaymentMethod.CASH, 10_800))
        assertTrue(warn(LIVING, PaymentMethod.CASH, 10_801))
        assertFalse("換方式付不再是警示（R-MIX-01）", warn(LIVING, PaymentMethod.TRANSFER, 0))
        assertFalse(warn(CONTEST, PaymentMethod.CASH, 100))
        assertFalse(warn(SALARY, null, 1))
    }

    // ---------- 建立記帳 ----------

    @Test fun `扣款帳戶：現金、轉帳用設定帳戶；刷卡依指定信用卡設定`() {
        assertEquals(CASH, EntryRules.resolveAccount(snapshot, PaymentMethod.CASH, null))
        assertEquals(BANK, EntryRules.resolveAccount(snapshot, PaymentMethod.TRANSFER, null))
        assertEquals(CARD_B, EntryRules.resolveAccount(snapshot, PaymentMethod.CREDIT_CARD, CARD_B))
        val off = snapshot.copy(settings = snapshot.settings.copy(pickCard = false))
        assertNull(EntryRules.resolveAccount(off, PaymentMethod.CREDIT_CARD, CARD_B))
        val noSetting = snapshot.copy(settings = snapshot.settings.copy(cashAccountId = null, transferAccountId = 999))
        assertEquals(CASH, EntryRules.resolveAccount(noSetting, PaymentMethod.CASH, null))
        assertEquals(BANK, EntryRules.resolveAccount(noSetting, PaymentMethod.TRANSFER, null))
    }

    @Test fun `建立記帳：金額為零不可記下；支出、收入、轉帳各自帶入帳戶`() {
        assertNull(EntryRules.buildEntry(snapshot, item(LIVING), PaymentMethod.CASH, null, "", "早餐"))
        val card = EntryRules.buildEntry(snapshot, item(LIVING), PaymentMethod.CREDIT_CARD, CARD_A, "150", " 午餐 ")!!
        assertEquals(LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = 150, itemId = LIVING, method = PaymentMethod.CREDIT_CARD, accountId = CARD_A, note = "午餐"), card)
        val income = EntryRules.buildEntry(snapshot, item(SALARY), null, null, "65000", "")!!
        assertEquals(BANK, income.accountId); assertNull(income.method)
        val transfer = EntryRules.buildEntry(snapshot, item(PAY_CARD_B), null, null, "9000", "")!!
        assertEquals(BANK, transfer.accountId); assertEquals(CARD_B, transfer.toAccountId)
        // 退款：存成負數，退回原付款帳戶
        val refund = EntryRules.buildEntry(snapshot, item(LIVING), PaymentMethod.CASH, null, "300", "退貨", refund = true)!!
        assertEquals(-300L, refund.amount)
        assertEquals(CASH, refund.accountId)
        assertEquals("退款", RecordRules.sourceLabel(refund))
        assertEquals("現金帳戶多回 300", 300L, BalanceRules.effect(refund, CASH, tw.myfsl.app.core.model.AccountKind.CASH))
    }

    @Test fun `記下後的提示與今天提示列`() {
        assertEquals("已記下 早餐 $85 · 生活費剩 $10,715", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.CASH, 85, "早餐"))
        assertEquals("已記下 生活費 $85 · 生活費剩 $10,715", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.CASH, 85, "  "))
        assertEquals("已記下 生活費 $500 · 生活費剩 $10,300", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.TRANSFER, 500, ""))
        assertEquals("已記下 薪資 $65,000", EntryRules.savedMessage(snapshot, item(SALARY), null, 65_000, ""))
        val today = snapshot.copy(
            ledger = listOf(
                expense(LIVING, PaymentMethod.CASH, 420, CASH),
                expense(LIVING, PaymentMethod.CREDIT_CARD, 150, CARD_A),
                expense(LIVING, PaymentMethod.CASH, 85, CASH),
                LedgerEntry(date = snapshot.today, type = FlowType.INCOME, amount = 65_000, itemId = SALARY, accountId = BANK),
                expense(FUEL, PaymentMethod.CREDIT_CARD, 800, null).copy(date = LocalDate.of(2026, 9, 13)),
            ),
        )
        assertEquals("9/14 今天已記 3 筆 · $655", EntryRules.todayStrip(today))
    }

    @Test fun `沒點過項目時，畫面顯示與記下用同一個項目：常用項目的第一個，不是排序第一個`() {
        val common = EntryRules.commonItems(snapshot, FlowType.EXPENSE)
        val first = snapshot.activeItems.filter { it.type == FlowType.EXPENSE }.minBy { it.sortOrder }
        assertEquals(common.first(), EntryRules.currentItem(snapshot, FlowType.EXPENSE, null))
        assertTrue("示意資料裡排序第一的支出項目不在常用項目裡，正好重現舊的錯誤", first !in common)
        assertEquals("點過的優先", item(FUEL), EntryRules.currentItem(snapshot, FlowType.EXPENSE, FUEL))
        assertEquals("點的是別的種類的項目時退回常用項目", common.first(), EntryRules.currentItem(snapshot, FlowType.EXPENSE, SALARY))
    }

    @Test fun `本月沒有任何計畫時，常用項目仍依排序補滿，不會只剩一個`() {
        // 年中才開始用、這個月沒設預算：沒有記帳、也沒有任何計畫金額
        val empty = snapshot.copy(amountsByYear = emptyMap(), ledger = emptyList())
        val expense = empty.activeItems.filter { it.type == FlowType.EXPENSE }
        val common = EntryRules.commonItems(empty, FlowType.EXPENSE)
        assertEquals(minOf(8, expense.size), common.size)
        assertEquals("沒點過項目時預設第一個常用項目", common.first(), EntryRules.currentItem(empty, FlowType.EXPENSE, null))
    }

    @Test fun `今天總覽：今天已花只算自己記的支出，今天記的含到期記下，後記的在前`() {
        val today = snapshot.copy(
            ledger = listOf(
                expense(LIVING, PaymentMethod.CASH, 420, CASH).copy(id = 1, createdAt = 100),
                expense(LIVING, PaymentMethod.CASH, -120, CASH).copy(id = 2, createdAt = 200, note = "退款"),
                expense(FUEL, PaymentMethod.CASH, 300, CASH).copy(id = 3, createdAt = 300, source = tw.myfsl.app.core.model.EntrySource.DUE),
                expense(FUEL, PaymentMethod.CASH, 50, CASH).copy(id = 4, createdAt = 400, source = tw.myfsl.app.core.model.EntrySource.MISSED),
                expense(FUEL, PaymentMethod.CASH, 999, CASH).copy(id = 5, createdAt = 500, date = LocalDate.of(2026, 9, 13)),
            ),
        )
        assertEquals("420 − 120 退款；到期記下、漏記差額、昨天的都不算", 300L, EntryRules.todaySpent(today))
        assertEquals(listOf(3L, 2L, 1L), EntryRules.todayEntries(today).map { it.id })
    }

    @Test fun `這個月還剩：只列有計畫的可調支出，剩得比例最少的在前`() {
        val rows = EntryRules.monthRemaining(snapshot)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.planned > 0 && it.item.type == FlowType.EXPENSE })
        val ratios = rows.map { it.remaining.toDouble() / it.planned }
        assertEquals(ratios.sorted(), ratios)
    }

    private fun expense(itemId: Long, method: PaymentMethod, amount: Long, account: Long?) =
        LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = amount, itemId = itemId, method = method, accountId = account)
}
