package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.RepaymentMethod
import java.time.LocalDate

/**
 * 到期提醒（R-REM-01）：截止日前 7 天、3 天；結帳日隔天提醒對照帳單。
 * 卡片 22 日結帳、次月 7 日截止、全額；貸款 18 日繳（200 萬、8.7%、84 期）。起算日 9/20。
 */
class ReminderTest {

    private val BANK = 1L
    private val CARD = 2L
    private val LOAN = 3L

    private fun snapshot(today: LocalDate, days: List<Int> = listOf(7, 3)) = FinanceSnapshot.empty(today).copy(
        accounts = listOf(
            Account(BANK, "銀行", AccountKind.BANK, balance = 300_000),
            Account(CARD, "台新", AccountKind.CREDIT_CARD, balance = 12_000, statementDay = 22, paymentDueDay = 7, card = CardTerms(CardPayMode.FULL, payAccountId = BANK)),
            Account(LOAN, "永豐", AccountKind.LOAN, balance = 2_000_000, loan = LoanTerms(8.7, 84, RepaymentMethod.EQUAL_PAYMENT, BANK, 18, 2_000_000)),
        ),
        settings = AppSettings(autoPostFrom = LocalDate.of(2026, 9, 20).toEpochDay(), transferAccountId = BANK, reminderDays = days),
    )

    @Test fun `截止日前 7 天與 3 天各提醒一次，其他天不提醒`() {
        Reminders.forDate(snapshot(LocalDate.of(2026, 9, 30))).single { it.id.startsWith("cardpay:") }.run {
            assertEquals("cardpay:2:2026-09:7", id)
            assertEquals("繳 台新 還有 7 天截止", title)
            assertTrue(text, text.startsWith("10/7 截止，本期要繳 $12,000（全額）"))
            assertNull(billCardId)
        }
        assertEquals("cardpay:2:2026-09:3", Reminders.forDate(snapshot(LocalDate.of(2026, 10, 4))).single { it.id.startsWith("cardpay:") }.id)
        assertTrue(Reminders.forDate(snapshot(LocalDate.of(2026, 10, 1))).none { it.id.startsWith("cardpay:") })
    }

    @Test fun `貸款月繳也提醒：金額是依攤還條件算的本期應繳`() {
        val payment = LoanAmortization.firstPayment(2_000_000, 8.7, 84, RepaymentMethod.EQUAL_PAYMENT)
        Reminders.forDate(snapshot(LocalDate.of(2026, 10, 11))).single { it.id.startsWith("loan:") }.run {
            assertEquals("永豐 月繳 還有 7 天到期", title)
            assertTrue(text, text.startsWith("10/18 到期，本期要繳 ${MoneyFormat.currency(payment)}"))
        }
    }

    @Test fun `已經記下的不提醒；設定成不提醒時都不發`() {
        val s = snapshot(LocalDate.of(2026, 9, 30))
        val paid = s.copy(ledger = listOf(LedgerEntry(id = 1, date = LocalDate.of(2026, 9, 29), type = FlowType.TRANSFER, amount = 12_000,
            accountId = BANK, toAccountId = CARD, source = EntrySource.DUE, postingKey = "cardpay:2:2026-09")))
        assertTrue(Reminders.forDate(paid).none { it.id.startsWith("cardpay:") })
        assertTrue(Reminders.forDate(snapshot(LocalDate.of(2026, 9, 30), days = emptyList())).isEmpty())
        assertTrue(Reminders.forDate(snapshot(LocalDate.of(2026, 9, 23), days = emptyList())).isEmpty())
    }

    @Test fun `結帳日隔天提醒對照帳單，點了打開帳單校正；已經輸入帳單就不提醒`() {
        val s = snapshot(LocalDate.of(2026, 9, 23))
        Reminders.forDate(s).single { it.id.startsWith("bill:") }.run {
            assertEquals("bill:2:2026-09", id)
            assertEquals("台新 帳單已結帳", title)
            assertEquals(CARD, billCardId)
            assertTrue(text, text.startsWith("App 估計 $12,000，10/7 截止"))
        }
        assertTrue(Reminders.forDate(snapshot(LocalDate.of(2026, 9, 24))).none { it.id.startsWith("bill:") })
        val entered = s.copy(cardStatements = listOf(CardStatement(CARD, 2026, 9, 12_300)))
        assertTrue(Reminders.forDate(entered).none { it.id.startsWith("bill:") })
    }

    @Test fun `設定：提醒天數用逗號分開，由大到小、不重複`() {
        assertEquals(listOf(7, 3), SettingsForm.parseReminderDays("7, 3"))
        assertEquals(listOf(7, 3), SettingsForm.parseReminderDays("3，7、7"))
        assertEquals(emptyList<Int>(), SettingsForm.parseReminderDays(" "))
        assertNull(SettingsForm.parseReminderDays("0"))
        assertNull(SettingsForm.parseReminderDays("31"))
        assertNull(SettingsForm.parseReminderDays("七天"))
        val draft = SettingsForm.fromSettings(AppSettings()).copy(reminderDays = "5")
        assertEquals(listOf(5), SettingsForm.validate(draft, AppSettings(), emptyList()).settings!!.reminderDays)
        assertEquals(
            "用逗號分開的天數，每個 1 到 30，例如 7, 3",
            SettingsForm.validate(draft.copy(reminderDays = "abc"), AppSettings(), emptyList()).errors[SettingsForm.Field.REMINDER_DAYS],
        )
    }
}
