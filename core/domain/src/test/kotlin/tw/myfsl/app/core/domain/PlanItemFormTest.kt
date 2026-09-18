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

    @Test fun `讀出既有項目：生活費有現金與信用卡兩列`() {
        val living = snapshot.item(SampleHousehold.LIVING)!!
        val draft = PlanItemForm.fromItem(living, snapshot, year)
        assertEquals(listOf(PaymentMethod.CASH, PaymentMethod.CREDIT_CARD), draft.lines.map { it.method })
        assertEquals("9000", draft.lines[0].months[0])
        assertEquals(16_000L * 12, draft.lineTotal(1))
        assertEquals(listOf(PaymentMethod.TRANSFER), draft.unusedMethods)
    }

    @Test fun `原樣存回：金額與支付方式不變`() {
        val living = snapshot.item(SampleHousehold.LIVING)!!
        val result = PlanItemForm.validate(PlanItemForm.fromItem(living, snapshot, year), snapshot)
        assertTrue(result.errors.toString(), result.ok)
        assertEquals(living, result.item)
        assertNull(result.newGroupName)
        assertEquals(List(12) { 9_000L }, result.amounts[PaymentMethod.CASH])
        assertEquals(List(12) { 16_000L }, result.amounts[PaymentMethod.CREDIT_CARD])
    }

    @Test fun `使用者改支付方式：現金列改成轉帳；改成已存在的方式時兩列交換`() {
        val living = PlanItemForm.fromItem(snapshot.item(SampleHousehold.LIVING)!!, snapshot, year)
        val toTransfer = PlanItemForm.setMethod(living, 0, PaymentMethod.TRANSFER)
        assertEquals(listOf(PaymentMethod.TRANSFER, PaymentMethod.CREDIT_CARD), toTransfer.lines.map { it.method })
        assertEquals("金額跟著列走", "9000", toTransfer.lines[0].months[0])

        val swapped = PlanItemForm.setMethod(living, 0, PaymentMethod.CREDIT_CARD)
        assertEquals(listOf(PaymentMethod.CREDIT_CARD, PaymentMethod.CASH), swapped.lines.map { it.method })
        assertTrue(PlanItemForm.validate(swapped, snapshot).ok)
    }

    @Test fun `新增與刪除支付方式列：同一方式不能重複、至少留一列`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, 5, snapshot).copy(name = "外食")
        val two = PlanItemForm.addLine(draft, PaymentMethod.CREDIT_CARD)
        assertEquals(2, two.lines.size)
        assertEquals("重複新增不會多一列", two, PlanItemForm.addLine(two, PaymentMethod.CREDIT_CARD))
        val one = PlanItemForm.removeLine(two, 0)
        assertEquals(listOf(PaymentMethod.CREDIT_CARD), one.lines.map { it.method })
        assertEquals(one, PlanItemForm.removeLine(one, 0))

        val duplicate = two.copy(lines = two.lines.map { it.copy(method = PaymentMethod.CASH) })
        val result = PlanItemForm.validate(duplicate, snapshot)
        assertFalse(result.ok)
        assertTrue(result.errors[PlanItemForm.Field.method(1)]!!.contains("重複"))
    }

    @Test fun `不同月份走不同支付方式：各列只填該月`() {
        var draft = PlanItemForm.newDraft(FlowType.EXPENSE, 7, snapshot).copy(name = "年節採買")
        draft = PlanItemForm.addLine(draft, PaymentMethod.CREDIT_CARD)
        draft = PlanItemForm.fill(draft, 0, PlanEditRules.onlyMonths(3_000, setOf(2)))
        draft = PlanItemForm.fill(draft, 1, PlanEditRules.onlyMonths(8_000, setOf(9)))
        val result = PlanItemForm.validate(draft, snapshot)
        assertTrue(result.ok)
        assertEquals(3_000L, result.amounts[PaymentMethod.CASH]!![1])
        assertEquals(0L, result.amounts[PaymentMethod.CASH]!![8])
        assertEquals(8_000L, result.amounts[PaymentMethod.CREDIT_CARD]!![8])
    }

    @Test fun `必填與金額錯誤`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, null, snapshot)
        val empty = PlanItemForm.validate(draft, snapshot)
        assertEquals("請輸入項目名稱", empty.errors[PlanItemForm.Field.NAME])
        assertEquals("請選擇群組或輸入新群組名稱", empty.errors[PlanItemForm.Field.GROUP])

        val duplicate = PlanItemForm.validate(draft.copy(name = "生活費", groupId = 5), snapshot)
        assertEquals("這個群組已經有同名的項目", duplicate.errors[PlanItemForm.Field.NAME])

        val bad = PlanItemForm.setMonth(PlanItemForm.setMonth(draft.copy(name = "x", groupId = 5), 0, 3, "三千"), 0, 4, "-5")
        val badResult = PlanItemForm.validate(bad, snapshot)
        assertEquals("3 月金額看不懂", badResult.errors[PlanItemForm.Field.month(0, 3)])
        assertEquals("4 月金額不能是負數", badResult.errors[PlanItemForm.Field.month(0, 4)])
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
        assertEquals(setOf<PaymentMethod?>(null), ok.amounts.keys)

        val transfer = PlanItemForm.newDraft(FlowType.TRANSFER, 8, snapshot)
            .copy(name = "繳卡費", accountId = SampleHousehold.BANK, toAccountId = SampleHousehold.BANK)
        assertEquals("轉出與轉入不能是同一個帳戶", PlanItemForm.validate(transfer, snapshot).errors[PlanItemForm.Field.TO_ACCOUNT])
    }

    @Test fun `切換類型：支出多列合併成一列，再切回支出變成現金列`() {
        val living = PlanItemForm.fromItem(snapshot.item(SampleHousehold.LIVING)!!, snapshot, year)
        val income = PlanItemForm.changeType(living, FlowType.INCOME)
        assertEquals(1, income.lines.size)
        assertNull(income.lines[0].method)
        assertEquals("25000", income.lines[0].months[0])
        val back = PlanItemForm.changeType(income, FlowType.EXPENSE)
        assertEquals(PaymentMethod.CASH, back.lines[0].method)
    }

    @Test fun `提醒：全部為 0、可調卻自動計入`() {
        val draft = PlanItemForm.newDraft(FlowType.EXPENSE, 5, snapshot)
            .copy(name = "零用", flexibility = Flexibility.FLEXIBLE, tracking = TrackingMode.AUTO)
        val result = PlanItemForm.validate(draft, snapshot)
        assertTrue("提醒不擋存檔", result.ok)
        assertEquals(2, result.warnings.size)
    }
}
