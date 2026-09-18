package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.CONTEST
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
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

    @Test fun `預設支付方式：上次用的 優先，否則計畫金額最大，否則現金`() {
        // 示意資料最後一筆生活費是現金（早餐 $85）
        assertEquals(PaymentMethod.CASH, EntryRules.defaultMethod(snapshot, item(LIVING)))
        val fresh = snapshot.copy(ledger = emptyList())
        assertEquals("沒記過就用計畫最大的", PaymentMethod.CREDIT_CARD, EntryRules.defaultMethod(fresh, item(LIVING)))
        val used = snapshot.copy(ledger = snapshot.ledger + expense(LIVING, PaymentMethod.TRANSFER, 500, BANK).copy(id = 100))
        assertEquals(PaymentMethod.TRANSFER, EntryRules.defaultMethod(used, item(LIVING)))
        assertEquals(PaymentMethod.CREDIT_CARD, EntryRules.defaultMethod(snapshot, item(FUEL)))
        // 比賽報名 9 月沒有計畫：用第一個計畫列（現金）
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
        assertEquals("生活費・現金 本月剩 $4,600", text(LIVING, PaymentMethod.CASH, 0))
        assertEquals("記下後 生活費・現金 剩 $4,480", text(LIVING, PaymentMethod.CASH, 120))
        assertEquals("記下後超出 生活費・現金 計畫 $7,400", text(LIVING, PaymentMethod.CASH, 12_000))
        assertEquals("生活費・信用卡 本月剩 $6,200", text(LIVING, PaymentMethod.CREDIT_CARD, 0))
        assertEquals("生活費沒有規劃用轉帳，會算進生活費總額（剩 $10,800）", text(LIVING, PaymentMethod.TRANSFER, 0))
        assertEquals("生活費沒有規劃用轉帳，會算進生活費總額（剩 $10,300）", text(LIVING, PaymentMethod.TRANSFER, 500))
        assertEquals("不在本月計畫內，會列入計畫外支出", text(CONTEST, PaymentMethod.CASH, 100))
        assertEquals("不影響支出進度", text(SALARY, null, 65_000))
        val overspent = snapshot.copy(ledger = snapshot.ledger + expense(HOUSEHOLD, PaymentMethod.CASH, 1_000, CASH))
        assertEquals("家用・現金 本月已超出 $300", EntryRules.hintText(EntryRules.budgetHint(overspent, item(HOUSEHOLD), PaymentMethod.CASH, 0)))
    }

    @Test fun `預算提示警示色`() {
        fun warn(id: Long, method: PaymentMethod?, amount: Long) = EntryRules.isWarning(EntryRules.budgetHint(snapshot, item(id), method, amount))
        assertFalse(warn(LIVING, PaymentMethod.CASH, 4_600))
        assertTrue(warn(LIVING, PaymentMethod.CASH, 4_601))
        assertTrue(warn(LIVING, PaymentMethod.TRANSFER, 0))
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
        assertEquals("已記下 早餐 $85 · 生活費・現金剩 $4,515", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.CASH, 85, "早餐"))
        assertEquals("已記下 生活費 $85 · 生活費・現金剩 $4,515", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.CASH, 85, "  "))
        assertEquals("已記下 生活費 $500", EntryRules.savedMessage(snapshot, item(LIVING), PaymentMethod.TRANSFER, 500, ""))
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

    private fun expense(itemId: Long, method: PaymentMethod, amount: Long, account: Long?) =
        LedgerEntry(date = snapshot.today, type = FlowType.EXPENSE, amount = amount, itemId = itemId, method = method, accountId = account)
}
