package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanItemFormTest {

    private val snapshot = SampleHousehold.snapshot()
    private val year = 2026

    @Test fun `讀出既有項目：一個項目只有一組金額，支付方式在項目上`() {
        val living = snapshot.item(SampleHousehold.LIVING)!!
        val draft = PlanItemForm.fromItem(living, snapshot, year)
        assertEquals(PaymentMethod.CREDIT_CARD, draft.method)
        assertEquals("16000", draft.months[0])
        assertEquals(16_000L * 12, draft.total)
        assertTrue("預設打開依實際比例", draft.useActualMix)
    }

    @Test fun `原樣存回：金額與支付方式不變`() {
        val living = snapshot.item(SampleHousehold.LIVING)!!
        val result = PlanItemForm.validate(PlanItemForm.fromItem(living, snapshot, year), snapshot)
        assertTrue(result.errors.toString(), result.ok)
        assertEquals(living, result.item)
        assertNull(result.newGroupName)
        assertEquals(List(12) { 16_000L }, result.amounts)
    }

    @Test fun `使用者改支付方式：金額不變，只有項目的支付方式變了`() {
        val living = PlanItemForm.fromItem(snapshot.item(SampleHousehold.LIVING)!!, snapshot, year)
        val toTransfer = PlanItemForm.setMethod(living, PaymentMethod.TRANSFER)
        assertEquals(PaymentMethod.TRANSFER, toTransfer.method)
        assertEquals("金額不受支付方式影響", "16000", toTransfer.months[0])
        val result = PlanItemForm.validate(toTransfer, snapshot)
        assertTrue(result.ok)
        assertEquals(PaymentMethod.TRANSFER, result.item!!.method)
        assertEquals(List(12) { 16_000L }, result.amounts)
    }

    @Test fun `支出一定要有支付方式；收入與轉帳沒有`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, 5, snapshot).copy(name = "外食")
        assertEquals(PaymentMethod.CASH, draft.method)
        val missing = PlanItemForm.validate(draft.copy(method = null), snapshot)
        assertFalse(missing.ok)
        assertEquals("請選支付方式", missing.errors[PlanItemForm.Field.METHOD])

        val income = PlanItemForm.newDraft(FlowType.INCOME, 1, snapshot)
        assertNull(income.method)
        assertEquals("收入不能設支付方式", income, PlanItemForm.setMethod(income, PaymentMethod.CASH))
    }

    @Test fun `試算依實際比例可以關掉`() {
        val living = PlanItemForm.fromItem(snapshot.item(SampleHousehold.LIVING)!!, snapshot, year)
        val locked = PlanItemForm.validate(living.copy(useActualMix = false), snapshot)
        assertTrue(locked.ok)
        assertFalse(locked.item!!.useActualMix)
    }

    @Test fun `只填某幾個月：其他月份是 0`() {
        var draft = PlanItemForm.newDraft(FlowType.EXPENSE, 7, snapshot).copy(name = "年節採買")
        draft = PlanItemForm.fill(draft, PlanEditRules.onlyMonths(3_000, setOf(2)))
        val result = PlanItemForm.validate(draft, snapshot)
        assertTrue(result.ok)
        assertEquals(3_000L, result.amounts[1])
        assertEquals(0L, result.amounts[8])
    }

    @Test fun `必填與金額錯誤`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, null, snapshot)
        val empty = PlanItemForm.validate(draft, snapshot)
        assertEquals("請輸入項目名稱", empty.errors[PlanItemForm.Field.NAME])
        assertEquals("請選擇群組或輸入新群組名稱", empty.errors[PlanItemForm.Field.GROUP])

        val duplicate = PlanItemForm.validate(draft.copy(name = "生活費", groupId = 5), snapshot)
        assertEquals("這個群組已經有同名的項目", duplicate.errors[PlanItemForm.Field.NAME])

        val bad = PlanItemForm.setMonth(PlanItemForm.setMonth(draft.copy(name = "x", groupId = 5), 3, "三千"), 4, "-5")
        val badResult = PlanItemForm.validate(bad, snapshot)
        assertEquals("3 月金額看不懂", badResult.errors[PlanItemForm.Field.month(3)])
        assertEquals("4 月金額不能是負數", badResult.errors[PlanItemForm.Field.month(4)])
    }

    @Test fun `新群組：輸入的名稱已存在就沿用，不存在才建立`() {
        val base = PlanItemForm.newDraft(FlowType.EXPENSE, null, snapshot).copy(name = "寵物")
        val reuse = PlanItemForm.validate(base.copy(newGroupName = " 生活 "), snapshot)
        assertTrue(reuse.ok)
        assertNull(reuse.newGroupName)
        assertEquals(5L, reuse.item!!.groupId)

        val create = PlanItemForm.validate(base.copy(newGroupName = "寵物開銷"), snapshot)
        assertTrue(create.ok)
        assertEquals("寵物開銷", create.newGroupName)
    }

    @Test fun `收入要入帳帳戶；轉帳要轉出與轉入且不同`() {
        val income = PlanItemForm.newDraft(FlowType.INCOME, 1, snapshot).copy(name = "兼職", accountId = null)
        assertEquals("收入要選入帳帳戶", PlanItemForm.validate(income, snapshot).errors[PlanItemForm.Field.ACCOUNT])
        val ok = PlanItemForm.validate(income.copy(accountId = SampleHousehold.BANK), snapshot)
        assertTrue(ok.ok)
        assertNull(ok.item!!.method)

        val transfer = PlanItemForm.newDraft(FlowType.TRANSFER, 8, snapshot)
            .copy(name = "繳卡費", accountId = SampleHousehold.BANK, toAccountId = SampleHousehold.BANK)
        assertEquals("轉出與轉入不能是同一個帳戶", PlanItemForm.validate(transfer, snapshot).errors[PlanItemForm.Field.TO_ACCOUNT])
    }

    @Test fun `切換類型：金額留著，支付方式跟著類型`() {
        val living = PlanItemForm.fromItem(snapshot.item(SampleHousehold.LIVING)!!, snapshot, year)
        val income = PlanItemForm.changeType(living, FlowType.INCOME)
        assertNull(income.method)
        assertEquals("16000", income.months[0])
        val back = PlanItemForm.changeType(income, FlowType.EXPENSE)
        assertEquals(PaymentMethod.CASH, back.method)
        assertEquals("16000", back.months[0])
    }

    @Test fun `提醒：全部為 0、可調卻自動計入`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, 5, snapshot)
            .copy(name = "零用", flexibility = Flexibility.FLEXIBLE, tracking = TrackingMode.AUTO)
        val result = PlanItemForm.validate(draft, snapshot)
        assertTrue("提醒不擋存檔", result.ok)
        assertEquals("全部為 0、可調卻每月固定、每月固定沒填日期", 3, result.warnings.size)
        assertTrue(result.warnings.any { it.startsWith("每月固定沒填日期") })
        // 填了日期就不再提醒日期
        assertEquals(2, PlanItemForm.validate(draft.copy(dueDay = "10"), snapshot).warnings.size)
    }
}
