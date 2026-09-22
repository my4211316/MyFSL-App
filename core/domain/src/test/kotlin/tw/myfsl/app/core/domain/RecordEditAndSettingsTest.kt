package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CASH
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.SALARY
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecordEditAndSettingsTest {

    private val snapshot = SampleHousehold.snapshot()
    private fun entry(id: Long) = snapshot.ledger.first { it.id == id }

    // ---------- 修改記帳 ----------

    @Test fun `改金額、項目、備註；保留 id、來源與寫入時間`() {
        val original = entry(7).copy(createdAt = 123)
        val result = RecordEditForm.validate(RecordDraft(original).copy(amount = "450", itemId = HOUSEHOLD, note = " 菜市場 "), snapshot)
        assertTrue(result.errors.toString(), result.ok)
        val e = result.entry!!
        assertEquals(7L, e.id)
        assertEquals(450L, e.amount)
        assertEquals(HOUSEHOLD, e.itemId)
        assertEquals("菜市場", e.note)
        assertEquals(123L, e.createdAt)
        assertEquals(EntrySource.MANUAL, e.source)
    }

    @Test fun `改付款方式：現金改刷卡時用選的卡，刷卡改現金時用現金帳戶`() {
        val toCard = RecordEditForm.validate(RecordDraft(entry(7)).copy(method = PaymentMethod.CREDIT_CARD, cardId = CARD_A), snapshot)
        assertEquals(CARD_A, toCard.entry!!.accountId)
        val toCash = RecordEditForm.validate(RecordDraft(entry(8)).copy(method = PaymentMethod.CASH), snapshot)
        assertEquals(CASH, toCash.entry!!.accountId)
        val unassigned = RecordEditForm.validate(RecordDraft(entry(8)).copy(cardId = null), snapshot)
        assertNull("不指定卡片", unassigned.entry!!.accountId)
    }

    @Test fun `錯誤：未來日期、0 元、自己記的負數、不同類型的項目`() {
        val draft = RecordDraft(entry(7))
        assertEquals("日期不能晚於今天", RecordEditForm.validate(draft.copy(date = snapshot.today.plusDays(1)), snapshot).errors[RecordEditForm.Field.DATE])
        assertEquals("金額不能是 0", RecordEditForm.validate(draft.copy(amount = "0"), snapshot).errors[RecordEditForm.Field.AMOUNT])
        assertEquals("金額要大於 0", RecordEditForm.validate(draft.copy(amount = "-5"), snapshot).errors[RecordEditForm.Field.AMOUNT])
        assertEquals("只能改成同樣是支出的項目", RecordEditForm.validate(draft.copy(itemId = SALARY), snapshot).errors[RecordEditForm.Field.ITEM])
    }

    @Test fun `對帳差額可以改成負數`() {
        val missed = entry(4)
        assertEquals(EntrySource.MISSED, missed.source)
        val result = RecordEditForm.validate(RecordDraft(missed).copy(amount = "-80"), snapshot)
        assertTrue(result.ok)
        assertEquals(-80L, result.entry!!.amount)
    }

    @Test fun `分期消費不能改金額與付款方式，可以改日期與備註`() {
        val installment = entry(8).copy(installmentId = 9)
        val draft = RecordDraft(installment)
        assertFalse(RecordEditForm.validate(draft.copy(amount = "999"), snapshot).ok)
        assertFalse(RecordEditForm.validate(draft.copy(method = PaymentMethod.CASH), snapshot).ok)
        val ok = RecordEditForm.validate(draft.copy(note = "改備註", date = LocalDate.of(2026, 9, 10)), snapshot)
        assertTrue(ok.ok)
        assertEquals(9L, ok.entry!!.installmentId)
    }

    @Test fun `移到帳戶上次校正之前會提醒`() {
        val result = RecordEditForm.validate(RecordDraft(entry(7)).copy(date = LocalDate.of(2026, 9, 1)), snapshot)
        assertTrue(result.ok)
        assertTrue(result.warnings.any { it.contains("零用現金") && it.contains("之前") })
    }

    // ---------- 設定 ----------

    @Test fun `設定：存回所有欄位`() {
        val draft = SettingsForm.fromSettings(snapshot.settings).copy(
            safetyLevel = "50,000", horizonMonths = 36, checkInDay = DayOfWeek.FRIDAY,
            pickCard = false, cashAccountId = null, transferAccountId = BANK, cardPostingDays = "3",
        )
        val result = SettingsForm.validate(draft, snapshot.settings, snapshot.accounts)
        assertTrue(result.ok)
        val s = result.settings!!
        assertEquals(50_000L, s.safetyLevel)
        assertEquals(36, s.horizonMonths)
        assertEquals(DayOfWeek.FRIDAY, s.checkInDay)
        assertFalse(s.pickCard)
        assertNull(s.cashAccountId)
        assertEquals(3, s.cardPostingDays)
        assertEquals("零用現金", SettingsForm.autoAccountName(snapshot.accounts, tw.myfsl.app.core.model.AccountKind.CASH))
    }

    @Test fun `設定：錯誤`() {
        val draft = SettingsForm.fromSettings(snapshot.settings)
        val bad = SettingsForm.validate(draft.copy(safetyLevel = "-1", cardPostingDays = "30", cashAccountId = CARD_A), snapshot.settings, snapshot.accounts)
        assertEquals("安全線要是 0 以上的金額", bad.errors[SettingsForm.Field.SAFETY])
        assertEquals("要在 0 到 14 天之間", bad.errors[SettingsForm.Field.POSTING_DAYS])
        assertEquals("信用卡不能當現金帳戶", "這個帳戶已不存在", bad.errors[SettingsForm.Field.CASH_ACCOUNT])
    }

    @Test fun `檢查日當天、距上次不到 7 天也提醒`() {
        val monday = snapshot.today // 2026-09-14 是週一
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val s = snapshot.copy(
            settings = snapshot.settings.copy(checkInDay = DayOfWeek.MONDAY),
            lastCheckIn = tw.myfsl.app.core.model.CheckIn(date = monday.minusDays(3)),
        )
        assertTrue(PeriodOverviewCalculator.build(s).checkIn.due)
        val other = s.copy(settings = s.settings.copy(checkInDay = DayOfWeek.SUNDAY))
        assertFalse(PeriodOverviewCalculator.build(other).checkIn.due)
        assertEquals(LIVING, entry(7).itemId)
    }
}
