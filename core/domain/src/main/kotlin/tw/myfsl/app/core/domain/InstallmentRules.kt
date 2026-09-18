package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.InstallmentPeriod
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.Period

/**
 * 分期的規則。
 *
 * - 額度：刷卡當下就全額佔用，直到各期入帳並繳掉為止。
 * - 卡債：每期入帳一期金額（本金＋該期手續費），不是一次全額。
 * - 費用：每一筆分期自己的方式與數值（0 利率／每期手續費／總額費率／年利率）。
 */
object InstallmentRules {

    /** 每期本金；除不盡的餘數放在第一期（銀行多數這樣算）。 */
    fun principals(amount: Money, months: Int): List<Money> {
        if (amount <= 0 || months <= 0) return emptyList()
        val each = amount / months
        val remainder = amount - each * months
        return List(months) { index -> if (index == 0) each + remainder else each }
    }

    /** 各期明細。 */
    fun schedule(installment: CardInstallment): List<InstallmentPeriod> {
        val principals = principals(installment.amount, installment.months)
        if (principals.isEmpty()) return emptyList()
        var remaining = installment.amount
        return principals.mapIndexed { index, principal ->
            val fee = when (installment.fee) {
                InstallmentFee.NONE -> 0L
                InstallmentFee.PER_PERIOD -> Math.round(installment.feeValue)
                InstallmentFee.TOTAL_RATE ->
                    totalFee(installment).let { total ->
                        val each = total / installment.months
                        if (index == 0) total - each * (installment.months - 1) else each
                    }

                InstallmentFee.ANNUAL_RATE -> Math.round(remaining * installment.feeValue / 100.0 / 12.0)
            }
            remaining -= principal
            InstallmentPeriod(
                number = index + 1,
                periodIndex = installment.firstPeriodIndex + index * 2,
                principal = principal,
                fee = fee,
            )
        }
    }

    /** 總手續費（[InstallmentFee.TOTAL_RATE] 用）。 */
    private fun totalFee(installment: CardInstallment): Money =
        Math.round(installment.amount * installment.feeValue / 100.0)

    /** 全部費用合計。 */
    fun totalFees(installment: CardInstallment): Money = schedule(installment).sumOf { it.fee }

    /** 連本帶費的總支出。 */
    fun totalCost(installment: CardInstallment): Money = installment.amount + totalFees(installment)

    /** 已經入帳的期數（期別 < [beforeIndex] 者）。 */
    fun postedPeriods(installment: CardInstallment, beforeIndex: Int): List<InstallmentPeriod> =
        schedule(installment).filter { it.periodIndex < beforeIndex }

    /** 還沒入帳的期數。 */
    fun pendingPeriods(installment: CardInstallment, fromIndex: Int): List<InstallmentPeriod> =
        schedule(installment).filter { it.periodIndex >= fromIndex }

    /** 還沒入帳的本金：這部分已經佔用額度，但還沒變成要繳的卡債。 */
    fun pendingPrincipal(installment: CardInstallment, fromIndex: Int): Money =
        pendingPeriods(installment, fromIndex).sumOf { it.principal }

    /** 剩下幾期還沒入帳。 */
    fun remainingPeriods(installment: CardInstallment, fromIndex: Int): Int = pendingPeriods(installment, fromIndex).size

    /**
     * 提前清償要付的錢：未入帳本金，加上未入帳手續費（[includeFees] 為 true 時）。
     * 多數銀行提前清償會把未到期本金一次結清，手續費是否減免各家不同，所以做成選項。
     */
    fun payoffAmount(installment: CardInstallment, fromIndex: Int, includeFees: Boolean = false): Money {
        val pending = pendingPeriods(installment, fromIndex)
        return pending.sumOf { it.principal } + if (includeFees) pending.sumOf { it.fee } else 0L
    }

    /** 某一期要入帳的金額（沒有那一期時為 0）。 */
    fun amountIn(installment: CardInstallment, periodIndex: Int): Money =
        schedule(installment).firstOrNull { it.periodIndex == periodIndex }?.total ?: 0L

    /** 一張卡（或全部卡）未入帳的本金合計。 */
    fun pendingPrincipal(installments: List<CardInstallment>, fromIndex: Int, cardAccountId: Long? = null): Money =
        installments
            .filter { !it.settled && (cardAccountId == null || it.cardAccountId == cardAccountId) }
            .sumOf { pendingPrincipal(it, fromIndex) }

    /** 卡片畫面用的摘要。 */
    data class CardInstallments(
        val count: Int,
        /** 未入帳本金（佔用額度的部分）。 */
        val pendingPrincipal: Money,
        /** 下一期要入帳的金額。 */
        val nextAmount: Money,
    )

    fun summary(installments: List<CardInstallment>, period: Period, cardAccountId: Long? = null): CardInstallments {
        val active = installments.filter {
            !it.settled &&
                (cardAccountId == null || it.cardAccountId == cardAccountId) &&
                remainingPeriods(it, period.index) > 0
        }
        return CardInstallments(
            count = active.size,
            pendingPrincipal = active.sumOf { pendingPrincipal(it, period.index) },
            nextAmount = active.sumOf { installment ->
                pendingPeriods(installment, period.index).minByOrNull { it.periodIndex }?.total ?: 0L
            },
        )
    }

    /**
     * 第一期入帳的期別：消費後的下一個月，落在該卡繳款日所在的半月。
     * 沒有繳款日時用 15 日（上半月）。
     */
    fun firstPeriodIndex(purchaseDate: java.time.LocalDate, payDay: Int?): Int {
        val next = purchaseDate.plusMonths(1)
        return Period(next.year, next.monthValue, Period.halfOfDay(payDay ?: 15)).index
    }

    /** 記下分期消費後的提示尾巴。 */
    fun savedSuffix(installment: CardInstallment): String {
        val first = schedule(installment).firstOrNull() ?: return ""
        return " · 分 ${installment.months} 期，每期 ${MoneyFormat.currency(first.total)}"
    }

    /** 記帳畫面的說明文字。 */
    fun description(installment: CardInstallment): String {
        val first = schedule(installment).firstOrNull() ?: return "分期"
        val feeText = when (installment.fee) {
            InstallmentFee.NONE -> "0 利率"
            InstallmentFee.PER_PERIOD -> "每期手續費 ${MoneyFormat.currency(Math.round(installment.feeValue))}"
            InstallmentFee.TOTAL_RATE -> "手續費 ${trim(installment.feeValue)}%"
            InstallmentFee.ANNUAL_RATE -> "年利率 ${trim(installment.feeValue)}%"
        }
        return "分 ${installment.months} 期 · 每期約 ${MoneyFormat.currency(first.total)} · $feeText" +
            if (installment.fee != InstallmentFee.NONE) {
                "（總共多付 ${MoneyFormat.currency(totalFees(installment))}）"
            } else {
                ""
            }
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
