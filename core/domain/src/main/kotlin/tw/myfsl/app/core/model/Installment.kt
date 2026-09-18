package tw.myfsl.app.core.model

import java.time.LocalDate

/** 分期的費用方式；每一筆分期各自設定。 */
enum class InstallmentFee(val label: String) {
    /** 0 利率、免手續費。 */
    NONE("0 利率"),

    /** 每期固定手續費金額。 */
    PER_PERIOD("每期手續費"),

    /** 以總金額的百分比一次收取，平均分到各期。 */
    TOTAL_RATE("總額手續費率"),

    /** 年利率，依剩餘本金逐期計息。 */
    ANNUAL_RATE("年利率"),
}

/**
 * 一筆分期消費。
 *
 * 實務行為：刷卡當下**全額佔用信用卡額度**，但帳單是**每期入帳**，
 * 而且每一筆分期的期數與利率／手續費都可能不同，所以條件存在這裡，不在卡片上。
 */
data class CardInstallment(
    val id: Long = 0,
    /** 指定的卡片；未指定卡片時為 null（併入信用卡合計）。 */
    val cardAccountId: Long? = null,
    /** 歸到哪個預算項目。 */
    val itemId: Long? = null,
    val purchaseDate: LocalDate,
    /** 消費總金額。 */
    val amount: Money,
    val months: Int,
    val fee: InstallmentFee = InstallmentFee.NONE,
    /** [InstallmentFee.PER_PERIOD] 為每期金額；[InstallmentFee.TOTAL_RATE] 與 [InstallmentFee.ANNUAL_RATE] 為百分比。 */
    val feeValue: Double = 0.0,
    /** 第一期入帳的期別（[Period.index]），通常是消費後的下一個帳單月。 */
    val firstPeriodIndex: Int,
    val note: String = "",
    /** 已結清（提前清償或期數跑完）。 */
    val settled: Boolean = false,
)

/** 分期的其中一期。 */
data class InstallmentPeriod(
    val number: Int,
    val periodIndex: Int,
    val principal: Money,
    val fee: Money,
) {
    val total: Money get() = principal + fee
}
