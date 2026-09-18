package tw.myfsl.app.core.domain

import tw.myfsl.app.core.domain.TextDecoding
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccountFormTest {

    private val bank = Account(1, "薪轉帳戶", AccountKind.BANK, balance = 120_000)
    private val existing = listOf(bank)

    // ---------- 必填 ----------

    @Test fun `只填名稱、種類、餘額就能存`() {
        val result = AccountForm.validate(AccountDraft(name = " 錢包 ", kind = AccountKind.CASH, balance = "15,000"), existing)
        assertTrue(result.ok)
        assertEquals("錢包", result.account!!.name)
        assertEquals(15_000L, result.account.balance)
        assertEquals(15_000L, result.balance)
        assertNull(result.account.card)
        assertNull(result.account.loan)
    }

    @Test fun `必填錯誤：沒名稱、同名、沒餘額、金額看不懂`() {
        val empty = AccountForm.validate(AccountDraft(kind = AccountKind.CASH), existing)
        assertFalse(empty.ok)
        assertEquals("請輸入名稱", empty.errors[AccountForm.Field.NAME])
        assertEquals("請輸入目前餘額", empty.errors[AccountForm.Field.BALANCE])

        val card = AccountForm.validate(AccountDraft(name = "卡", kind = AccountKind.CREDIT_CARD), existing)
        assertEquals("負債帳戶問欠款", "請輸入目前欠款", card.errors[AccountForm.Field.BALANCE])

        val duplicate = AccountForm.validate(AccountDraft(name = "薪轉帳戶", kind = AccountKind.BANK, balance = "1"), existing)
        assertEquals("已經有同名的帳戶", duplicate.errors[AccountForm.Field.NAME])

        val bad = AccountForm.validate(AccountDraft(name = "錢包", balance = "一萬"), existing)
        assertEquals("金額看不懂", bad.errors[AccountForm.Field.BALANCE])
    }

    @Test fun `編輯：名稱同自己不算重複；餘額沒改就不寫校正`() {
        val draft = AccountForm.fromAccount(bank)
        val same = AccountForm.validate(draft, existing)
        assertTrue(same.ok)
        assertNull("餘額沒改", same.balance)

        val changed = AccountForm.validate(draft.copy(balance = "118000"), existing)
        assertEquals(118_000L, changed.balance)
    }

    // ---------- 信用卡進階（選填） ----------

    @Test fun `信用卡：不填進階就只是一個餘額`() {
        val result = AccountForm.validate(AccountDraft(name = "信用卡 A", kind = AccountKind.CREDIT_CARD, balance = "60000"), existing)
        assertTrue(result.ok)
        assertNull(result.account!!.card)
        assertNull(result.account.creditLimit)
    }

    @Test fun `信用卡：填了進階欄位就存進去`() {
        val result = AccountForm.validate(
            AccountDraft(
                name = "信用卡 A", kind = AccountKind.CREDIT_CARD, balance = "60000",
                issuer = "某銀行", creditLimit = "150,000", statementDay = "28", payDay = "15",
                revolvingEnabled = true, revolvingRate = "15", minPercent = "10", minFloor = "1000",
                payMode = CardPayMode.FIXED, fixedPayment = "18000", payAccountId = 1,
            ),
            existing,
        )
        assertTrue(result.errors.toString(), result.ok)
        val account = result.account!!
        assertEquals("某銀行", account.issuer)
        assertEquals(150_000L, account.creditLimit)
        assertEquals(28, account.statementDay)
        assertEquals(15, account.paymentDueDay)
        assertEquals(
            CardTerms(15.0, 10.0, 1_000, CardPayMode.FIXED, 18_000, payAccountId = 1, payDay = 15, statementDay = 28),
            account.card,
        )
    }

    @Test fun `信用卡：結帳日不開循環條件也能填`() {
        val result = AccountForm.validate(
            AccountDraft(name = "信用卡 B", kind = AccountKind.CREDIT_CARD, balance = "0", statementDay = "5"),
            existing,
        )
        assertTrue(result.ok)
        assertEquals(5, result.account!!.statementDay)
        assertNull(result.account.card)
    }

    @Test fun `信用卡進階錯誤`() {
        val result = AccountForm.validate(
            AccountDraft(
                name = "卡", kind = AccountKind.CREDIT_CARD, balance = "0",
                creditLimit = "0", statementDay = "32", payDay = "abc",
                revolvingEnabled = true, revolvingRate = "", minPercent = "150",
                payMode = CardPayMode.FIXED, fixedPayment = "",
            ),
            existing,
        )
        assertFalse(result.ok)
        assertEquals("額度要大於 0", result.errors[AccountForm.Field.LIMIT])
        assertEquals("日期要在 1 到 31 之間", result.errors[AccountForm.Field.STATEMENT_DAY])
        assertEquals("日期要在 1 到 31 之間", result.errors[AccountForm.Field.PAY_DAY])
        assertEquals("請輸入循環年利率", result.errors[AccountForm.Field.RATE])
        assertEquals("要在 0 到 100 之間", result.errors[AccountForm.Field.MIN_PERCENT])
        assertEquals("固定金額繳款要填每月金額", result.errors[AccountForm.Field.FIXED])
    }

    // ---------- 貸款進階 ----------

    @Test fun `貸款：開攤還條件時要填利率、期數、繳款日、扣款帳戶`() {
        val missing = AccountForm.validate(
            AccountDraft(name = "信貸", kind = AccountKind.LOAN, balance = "600000", loanEnabled = true),
            existing,
        )
        assertEquals("請輸入年利率", missing.errors[AccountForm.Field.LOAN_RATE])
        assertEquals("請輸入剩餘期數", missing.errors[AccountForm.Field.LOAN_MONTHS])
        assertEquals("請輸入繳款日（1–31）", missing.errors[AccountForm.Field.PAY_DAY])
        assertEquals("請選擇扣款帳戶", missing.errors[AccountForm.Field.PAY_ACCOUNT])

        val ok = AccountForm.validate(
            AccountDraft(
                name = "信貸", kind = AccountKind.LOAN, balance = "600000", loanEnabled = true,
                loanRate = "7.5", loanMonths = "48", payDay = "20", loanOriginal = "800000", payAccountId = 1,
            ),
            existing,
        )
        assertTrue(ok.errors.toString(), ok.ok)
        assertEquals(LoanTerms(7.5, 48, RepaymentMethod.EQUAL_PAYMENT, 1, 20, 800_000), ok.account!!.loan)

        // 0 利率的貸款也可以
        assertTrue(
            AccountForm.validate(
                AccountDraft(name = "親友借款", kind = AccountKind.LOAN, balance = "50000", loanEnabled = true, loanRate = "0", loanMonths = "10", payDay = "5", payAccountId = 1),
                existing,
            ).ok,
        )
    }

    @Test fun `帳戶轉回草稿再存，內容不變`() {
        val card = Account(
            3, "信用卡 A", AccountKind.CREDIT_CARD, balance = 60_000, creditLimit = 150_000, issuer = "某銀行",
            paymentDueDay = 15, statementDay = 28,
            card = CardTerms(15.0, 10.0, 1_000, CardPayMode.MINIMUM, null, payAccountId = 1, payDay = 15, statementDay = 28),
        )
        val result = AccountForm.validate(AccountForm.fromAccount(card), existing + card)
        assertTrue(result.errors.toString(), result.ok)
        assertEquals(card, result.account)
    }

    // ---------- 分期第一期與提示 ----------

    @Test fun `分期第一期：下個月、依繳款日決定上下半月`() {
        assertEquals(Period(2026, 10, Half.FIRST).index, InstallmentRules.firstPeriodIndex(LocalDate.of(2026, 9, 5), 15))
        assertEquals(Period(2026, 10, Half.SECOND).index, InstallmentRules.firstPeriodIndex(LocalDate.of(2026, 9, 5), 25))
        assertEquals(Period(2027, 1, Half.FIRST).index, InstallmentRules.firstPeriodIndex(LocalDate.of(2026, 12, 20), null))
        val installment = CardInstallment(
            amount = 36_000, months = 12, purchaseDate = LocalDate.of(2026, 9, 5),
            fee = InstallmentFee.PER_PERIOD, feeValue = 50.0, firstPeriodIndex = Period(2026, 10, Half.FIRST).index,
        )
        assertEquals(" · 分 12 期，每期 $3,050", InstallmentRules.savedSuffix(installment))
    }

    // ---------- CSV 編碼 ----------

    @Test fun `CSV 編碼：UTF-8（含 BOM）與 Big5 都能讀`() {
        val text = "群組,項目\n生活,生活費"
        val utf8 = TextDecoding.decode(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, utf8.text)
        assertEquals(TextDecoding.Encoding.UTF8, utf8.encoding)

        val bom = TextDecoding.decode(TextDecoding.encodeForExcel(text))
        assertEquals("BOM 會被去掉", text, bom.text)

        val big5 = TextDecoding.decode(text.toByteArray(charset("Big5")))
        assertEquals(text, big5.text)
        assertEquals(TextDecoding.Encoding.BIG5, big5.encoding)
    }
}
