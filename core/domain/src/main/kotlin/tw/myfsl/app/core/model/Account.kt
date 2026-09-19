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

/** 繳卡費時「這一期」選的繳款方式（R-CARD-21）。卡片本身不設預設，每期到期時由使用者選。 */
enum class CardPayMode(val label: String) {
    /** 繳帳單金額（結帳日以前的欠款）；截止日前繳清不計息。 */
    FULL("全額"),

    /** 使用者自己決定繳多少；沒繳清的部分計息。 */
    FREE("自由"),

    /** 照帳單上的最低應繳（使用者輸入，App 不計算）；沒繳清的部分計息。 */
    MINIMUM("最低"),
}

/**
 * 信用卡「依帳單繳款」的條件（R-CARD-20）。有設定時，這張卡依結帳日與繳款截止日（[Account.statementDay]、[Account.paymentDueDay]）
 * 在本月到期列出繳款，每期由使用者選怎麼繳；試算從實際的繳款紀錄推估（R-CARD-26）。
 * 沒有設定時卡片只是一個餘額，不計息，繳款完全由計畫中的「繳卡費」項目決定。
 */
data class CardTerms(
    /** 循環年利率：沒繳清的帳單計息用；沒填時不計息。 */
    val revolvingRatePercent: Double? = null,
    /** 繳款的扣款帳戶；未設定時用支付方式「轉帳」的帳戶。 */
    val payAccountId: Long? = null,
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

    /** 有繳款條件、結帳日與截止日都有填的卡：依期別在本月到期列出繳款（R-CARD-20）。 */
    val hasCardSchedule: Boolean
        get() = kind == AccountKind.CREDIT_CARD && card != null && statementDay != null && paymentDueDay != null

    /** 貸款已還比例 0–1；沒有原始金額時為 null。 */
    val loanRepaidRatio: Double?
        get() {
            val original = loan?.originalPrincipal ?: return null
            if (original <= 0) return null
            return ((original - balance).toDouble() / original).coerceIn(0.0, 1.0)
        }
}
