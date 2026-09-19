package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.RepaymentMethod

/**
 * 帳戶編輯畫面的草稿。欄位都是字串，方便直接綁輸入框；存檔前由 [AccountForm.validate] 檢查。
 *
 * 必填只有名稱、種類、目前餘額（負債帳戶填欠款）。
 * 信用卡與貸款的細節都放在「進階（選填）」。信用卡打開「依帳單繳款」後才會在本月到期列出繳款（R-CARD-20）。
 */
data class AccountDraft(
    val id: Long = 0,
    val name: String = "",
    val kind: AccountKind = AccountKind.CASH,
    val balance: String = "",
    // ---- 信用卡進階 ----
    val issuer: String = "",
    val creditLimit: String = "",
    val statementDay: String = "",
    val payDay: String = "",
    /** 依帳單繳款：每期在截止日列出繳款（R-CARD-20）。 */
    val scheduleEnabled: Boolean = false,
    val payMode: CardPayMode = CardPayMode.FULL,
    /** 循環年利率：自由、最低必填；全額選填。 */
    val revolvingRate: String = "",
    /** 自由、最低的預估每月繳款。 */
    val estimatedPayment: String = "",
    // ---- 貸款進階 ----
    val loanEnabled: Boolean = false,
    val loanRate: String = "",
    val loanMonths: String = "",
    val loanMethod: RepaymentMethod = RepaymentMethod.EQUAL_PAYMENT,
    val loanOriginal: String = "",
    val payAccountId: Long? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    val isCard: Boolean get() = kind == AccountKind.CREDIT_CARD
    val isLoan: Boolean get() = kind == AccountKind.LOAN || kind == AccountKind.POLICY_LOAN
    val balanceLabel: String get() = if (kind.isLiability) "目前欠款" else "目前餘額"
}

object AccountForm {

    /** 欄位名稱，用來把錯誤訊息放在對的輸入框下面。 */
    object Field {
        const val NAME = "name"
        const val BALANCE = "balance"
        const val LIMIT = "limit"
        const val STATEMENT_DAY = "statementDay"
        const val PAY_DAY = "payDay"
        const val RATE = "rate"
        const val ESTIMATE = "estimate"
        const val LOAN_RATE = "loanRate"
        const val LOAN_MONTHS = "loanMonths"
        const val LOAN_ORIGINAL = "loanOriginal"
        const val PAY_ACCOUNT = "payAccount"
    }

    data class Result(
        val account: Account?,
        /** 要寫入的校正餘額；編輯時餘額沒改為 null。 */
        val balance: Money?,
        val errors: Map<String, String>,
    ) {
        val ok: Boolean get() = errors.isEmpty() && account != null
    }

    fun fromAccount(account: Account): AccountDraft = AccountDraft(
        id = account.id,
        name = account.name,
        kind = account.kind,
        balance = account.balance.toString(),
        issuer = account.issuer,
        creditLimit = account.creditLimit?.toString().orEmpty(),
        statementDay = account.statementDay?.toString().orEmpty(),
        payDay = (account.loan?.payDay ?: account.paymentDueDay)?.toString().orEmpty(),
        scheduleEnabled = account.card != null,
        payMode = account.card?.payMode ?: CardPayMode.FULL,
        revolvingRate = account.card?.revolvingRatePercent?.let(::trim).orEmpty(),
        estimatedPayment = account.card?.estimatedPayment?.toString().orEmpty(),
        loanEnabled = account.loan != null,
        loanRate = account.loan?.annualRatePercent?.let(::trim).orEmpty(),
        loanMonths = account.loan?.remainingMonths?.toString().orEmpty(),
        loanMethod = account.loan?.method ?: RepaymentMethod.EQUAL_PAYMENT,
        loanOriginal = account.loan?.originalPrincipal?.toString().orEmpty(),
        payAccountId = account.card?.payAccountId ?: account.loan?.payAccountId,
        archived = account.archived,
        sortOrder = account.sortOrder,
    )

    fun validate(draft: AccountDraft, accounts: List<Account>): Result {
        val errors = mutableMapOf<String, String>()
        val name = draft.name.trim()
        if (name.isEmpty()) {
            errors[Field.NAME] = "請輸入名稱"
        } else if (accounts.any { it.id != draft.id && !it.archived && it.name.trim() == name }) {
            errors[Field.NAME] = "已經有同名的帳戶"
        }

        val existing = accounts.firstOrNull { it.id == draft.id && draft.id != 0L }
        val balance = when {
            draft.balance.isBlank() && existing == null -> {
                errors[Field.BALANCE] = "請輸入${draft.balanceLabel}"
                null
            }

            draft.balance.isBlank() -> existing?.balance
            else -> MoneyFormat.parse(draft.balance).also { if (it == null) errors[Field.BALANCE] = "金額看不懂" }
        }

        var card: CardTerms? = null
        var cardStatementDay: Int? = null
        var creditLimit: Money? = null
        var paymentDueDay: Int? = null
        var loan: LoanTerms? = null

        if (draft.isCard) {
            creditLimit = optionalMoney(draft.creditLimit, Field.LIMIT, errors)?.also {
                if (it <= 0) errors[Field.LIMIT] = "額度要大於 0"
            }
            val statementDay = optionalDay(draft.statementDay, Field.STATEMENT_DAY, errors)
            paymentDueDay = optionalDay(draft.payDay, Field.PAY_DAY, errors)
            if (draft.scheduleEnabled) {
                if (draft.statementDay.isBlank()) errors[Field.STATEMENT_DAY] = "依帳單繳款要填結帳日"
                if (draft.payDay.isBlank()) errors[Field.PAY_DAY] = "依帳單繳款要填繳款截止日"
                val partial = draft.payMode != CardPayMode.FULL
                // 全額繳清不計息，利率選填；自由、最低沒繳清的部分要計息，利率與預估繳款必填。
                val rate = if (partial || draft.revolvingRate.isNotBlank()) {
                    requiredPercent(draft.revolvingRate, Field.RATE, "自由或最低繳款要填循環年利率", errors)
                } else {
                    null
                }
                val estimate = if (partial || draft.estimatedPayment.isNotBlank()) {
                    MoneyFormat.parse(draft.estimatedPayment).also {
                        if (it == null || it <= 0) errors[Field.ESTIMATE] = "自由或最低繳款要填預估每月繳款"
                    }
                } else {
                    null
                }
                card = CardTerms(
                    payMode = draft.payMode,
                    revolvingRatePercent = rate,
                    estimatedPayment = estimate,
                    payAccountId = draft.payAccountId,
                )
            }
            cardStatementDay = statementDay
        }

        if (draft.isLoan && draft.loanEnabled) {
            val rate = requiredPercent(draft.loanRate, Field.LOAN_RATE, "請輸入年利率", errors, allowZero = true)
            val months = draft.loanMonths.trim().toIntOrNull()
            if (months == null || months <= 0) errors[Field.LOAN_MONTHS] = "請輸入剩餘期數"
            val payDay = draft.payDay.trim().toIntOrNull()
            if (payDay == null || payDay !in 1..31) errors[Field.PAY_DAY] = "請輸入繳款日（1–31）"
            val original = optionalMoney(draft.loanOriginal, Field.LOAN_ORIGINAL, errors)
            val payAccount = draft.payAccountId?.takeIf { id -> accounts.any { it.id == id && it.kind.isLiquid } }
            if (payAccount == null) errors[Field.PAY_ACCOUNT] = "請選擇扣款帳戶"
            if (rate != null && months != null && months > 0 && payDay != null && payDay in 1..31 && payAccount != null) {
                loan = LoanTerms(rate, months, draft.loanMethod, payAccount, payDay, original)
            }
        }

        if (errors.isNotEmpty() || balance == null) return Result(null, null, errors)

        val account = Account(
            id = draft.id,
            name = name,
            kind = draft.kind,
            balance = balance,
            creditLimit = creditLimit,
            issuer = if (draft.isCard) draft.issuer.trim() else "",
            paymentDueDay = paymentDueDay,
            statementDay = cardStatementDay,
            card = card,
            loan = loan,
            archived = draft.archived,
            sortOrder = draft.sortOrder,
        )
        val balanceToRecord = if (existing != null && existing.balance == balance) null else balance
        return Result(account, balanceToRecord, emptyMap())
    }

    private fun optionalMoney(text: String, field: String, errors: MutableMap<String, String>): Money? {
        if (text.isBlank()) return null
        return MoneyFormat.parse(text).also { if (it == null) errors[field] = "金額看不懂" }
    }

    private fun optionalDay(text: String, field: String, errors: MutableMap<String, String>): Int? {
        if (text.isBlank()) return null
        val day = text.trim().toIntOrNull()
        if (day == null || day !in 1..31) {
            errors[field] = "日期要在 1 到 31 之間"
            return null
        }
        return day
    }

    private fun requiredPercent(
        text: String,
        field: String,
        missing: String,
        errors: MutableMap<String, String>,
        allowZero: Boolean = false,
    ): Double? {
        if (text.isBlank()) {
            errors[field] = missing
            return null
        }
        val value = text.trim().removeSuffix("%").toDoubleOrNull()
        val valid = value != null && value <= 100 && (value > 0 || (allowZero && value == 0.0))
        if (!valid) {
            errors[field] = "要在 0 到 100 之間"
            return null
        }
        return value
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
