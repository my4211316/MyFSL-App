package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.RepaymentMethod
import kotlin.math.pow

data class Installment(
    val number: Int,
    val payment: Money,
    val principal: Money,
    val interest: Money,
    val balanceAfter: Money,
)

object LoanAmortization {

    /** 產生攤還表；最後一期會把剩餘本金全部還清。 */
    fun schedule(
        balance: Money,
        annualRatePercent: Double,
        months: Int,
        method: RepaymentMethod,
    ): List<Installment> {
        if (balance <= 0 || months <= 0) return emptyList()
        val monthlyRate = annualRatePercent / 100.0 / 12.0
        val level = levelPayment(balance, annualRatePercent, months)
        val result = ArrayList<Installment>(months)
        var remaining = balance
        for (n in 1..months) {
            val interest = Math.round(remaining * monthlyRate)
            val principal = when {
                n == months -> remaining
                method == RepaymentMethod.EQUAL_PAYMENT -> (level - interest).coerceIn(0, remaining)
                method == RepaymentMethod.EQUAL_PRINCIPAL -> (balance / months).coerceAtMost(remaining)
                else -> 0L
            }
            remaining -= principal
            result += Installment(n, principal + interest, principal, interest, remaining)
        }
        return result
    }

    /** 本息平均攤還的每月付款，四捨五入到元。 */
    fun levelPayment(balance: Money, annualRatePercent: Double, months: Int): Money {
        if (balance <= 0 || months <= 0) return 0
        val monthlyRate = annualRatePercent / 100.0 / 12.0
        if (monthlyRate == 0.0) return Math.round(balance.toDouble() / months)
        return Math.round(balance * monthlyRate / (1 - (1 + monthlyRate).pow(-months)))
    }

    /** 第一期應繳金額，用於顯示「每月約繳」。 */
    fun firstPayment(balance: Money, annualRatePercent: Double, months: Int, method: RepaymentMethod): Money =
        schedule(balance, annualRatePercent, months, method).firstOrNull()?.payment ?: 0
}
