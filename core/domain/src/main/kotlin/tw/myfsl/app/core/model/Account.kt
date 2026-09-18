package tw.myfsl.app.core.model

import java.time.LocalDate

enum class AccountKind(val label: String, val isLiquid: Boolean, val isLiability: Boolean) {
    CASH("現金", isLiquid = true, isLiability = false),
    BANK("銀行存款", isLiquid = true, isLiability = false),
    STORED_VALUE("儲值卡", isLiquid = true, isLiability = false),
    CREDIT_CARD("信用卡", isLiquid = false, isLiability = true),
    LOAN("貸款", isLiquid = false, isLiability = true),
    POLICY_LOAN("保單借款", isLiquid = false, isLiability = true),
}

enum class RepaymentMethod(val label: String) {
    EQUAL_PAYMENT("本息平均攤還"),
    EQUAL_PRINCIPAL("本金平均攤還"),
    INTEREST_ONLY("只繳利息"),
}

enum class CardPayMode(val label: String) {
    FIXED("固定金額"),
    MINIMUM("最低應繳"),
    FULL("當期全額"),
}

/**
 * 信用卡的循環與繳款條件。沒有設定時，卡債不計息，繳款完全由計畫中的「繳卡費」項目決定。
 *
 * 最低應繳含當期循環利息，所以繳進去的錢不會全部還到本金。
 */
data class CardTerms(
    val revolvingRatePercent: Double,
    /** 最低應繳 = 未清餘額 × 這個百分比。 */
    val minPaymentPercent: Double = 10.0,
    /** 最低應繳的下限金額。 */
    val minPaymentFloor: Money = 1_000,
    val payMode: CardPayMode = CardPayMode.MINIMUM,
    /** [CardPayMode.FIXED] 時的月付金。 */
    val fixedPayment: Money? = null,
    /** 繳款的扣款帳戶；未設定時用支付方式「轉帳」的帳戶。 */
    val payAccountId: Long? = null,
    /** 計息與繳款的日；決定落在上半月或下半月。 */
    val payDay: Int = 15,
    /** 結帳日（帳單結算日）；選填，只用來顯示與推估入帳月份。 */
    val statementDay: Int? = null,
    /**
     * 既有卡循：目前欠款中已經在計循環利息的金額（帳單上前期沒繳清的部分）；選填。
     * 沒填時整筆欠款都當成會計息。只影響第一次計息，之後沒繳清的部分都會計息。
     */
    val revolvingBalance: Money? = null,
)

/** 貸款條件：由目前欠款與剩餘期數產生每月還款排程。 */
data class LoanTerms(
    val annualRatePercent: Double,
    val remainingMonths: Int,
    val method: RepaymentMethod,
    val payAccountId: Long,
    val payDay: Int,
    /** 原始貸款金額，用來顯示還款進度；未知時為 null。 */
    val originalPrincipal: Money? = null,
)

/**
 * 帳戶。
 *
 * [balance] 是推算出的目前餘額：最近一次校正的餘額，加上之後的記帳。
 * 資產帳戶代表持有金額；負債帳戶代表欠款（正數 = 欠錢）。
 */
data class Account(
    val id: Long = 0,
    val name: String,
    val kind: AccountKind,
    val balance: Money = 0,
    val balanceAsOf: LocalDate? = null,
    /** 最近一次校正的寫入時間（epoch 毫秒）；同一天的記帳以此判斷先後。 */
    val balanceRecordedAt: Long = 0,
    val creditLimit: Money? = null,
    /** 發卡銀行或機構；選填。 */
    val issuer: String = "",
    val paymentDueDay: Int? = null,
    /** 結帳日；選填，不需要設定循環條件也可以填。 */
    val statementDay: Int? = null,
    val card: CardTerms? = null,
    val loan: LoanTerms? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    /** 信用卡使用率 0–1；沒有設定額度時為 null。 */
    val utilization: Double?
        get() = if (kind == AccountKind.CREDIT_CARD && creditLimit != null && creditLimit > 0) {
            (balance.toDouble() / creditLimit).coerceIn(0.0, 1.0)
        } else {
            null
        }

    val availableCredit: Money?
        get() = if (kind == AccountKind.CREDIT_CARD && creditLimit != null) {
            (creditLimit - balance).coerceAtLeast(0)
        } else {
            null
        }

    /** 貸款已還比例 0–1；沒有原始金額時為 null。 */
    val loanRepaidRatio: Double?
        get() {
            val original = loan?.originalPrincipal ?: return null
            if (original <= 0) return null
            return ((original - balance).toDouble() / original).coerceIn(0.0, 1.0)
        }
}
