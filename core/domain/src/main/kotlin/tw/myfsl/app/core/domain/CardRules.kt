package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat

/**
 * 信用卡循環信用的規則。
 *
 * 重點：最低應繳裡面含當期循環利息，繳進去的錢不是全部都還到本金。
 * 只要「繳款 − 利息」小於當月新刷金額，卡債就會繼續變多。
 */
object CardRules {

    /** 當期循環利息（每月計一次，四捨五入到元）。 */
    fun monthlyInterest(balance: Money, annualRatePercent: Double): Money =
        if (balance <= 0 || annualRatePercent <= 0) 0 else Math.round(balance * annualRatePercent / 100.0 / 12.0)

    /** 本期會計息的金額：有填既有卡循時取既有卡循（不超過欠款），否則為整筆欠款。 */
    fun interestBase(balance: Money, terms: CardTerms?): Money {
        val debt = balance.coerceAtLeast(0)
        return terms?.revolvingBalance?.coerceIn(0, debt) ?: debt
    }

    /** 最低應繳 = 未清餘額 × 比例，但不低於下限，也不低於當期利息；最多為全部欠款。 */
    fun minimumPayment(balance: Money, terms: CardTerms): Money {
        if (balance <= 0) return 0
        val byPercent = Math.round(balance * terms.minPaymentPercent / 100.0)
        val interest = monthlyInterest(interestBase(balance, terms), terms.revolvingRatePercent)
        return maxOf(terms.minPaymentFloor, byPercent, interest).coerceAtMost(balance)
    }

    /** 這樣繳下去，卡債會往哪裡走。 */
    data class CardOutlook(
        val balance: Money,
        val interest: Money,
        val payment: Money,
        /** 當月預計新刷金額。 */
        val spending: Money,
    ) {
        /** 本月卡債變化：新刷 ＋ 利息 − 繳款。正數代表卡債變多。 */
        val change: Money get() = spending + interest - payment

        /** 還到本金 = 繳款 − 利息，最低 0；繳款不夠付利息時，沒付到的利息留在卡債裡。 */
        val principalRepaid: Money get() = (payment - interest).coerceAtLeast(0)

        /** 沒付到的利息。 */
        val unpaidInterest: Money get() = (interest - payment).coerceAtLeast(0)
        val growing: Boolean get() = change > 0

        /** 每月還要多繳多少，卡債才**不再增加**（多繳這個數字是持平；要下降須再多一些）。 */
        val extraToStop: Money get() = if (change > 0) change else 0
    }

    fun outlook(balance: Money, terms: CardTerms, monthlySpending: Money, payment: Money? = null): CardOutlook {
        val interest = monthlyInterest(balance, terms.revolvingRatePercent)
        val actual = payment ?: when (terms.payMode) {
            CardPayMode.MINIMUM -> minimumPayment(balance, terms)
            CardPayMode.FULL -> balance + interest
            CardPayMode.FIXED -> terms.fixedPayment ?: 0
        }
        return CardOutlook(balance, interest, actual, monthlySpending)
    }

    /** 卡債會變多時的提醒文字；會下降時為 null。 */
    fun warning(outlook: CardOutlook): String? {
        if (!outlook.growing) return null
        val extra = MoneyFormat.currency(outlook.extraToStop)
        return if (outlook.payment <= outlook.interest) {
            "繳的錢還不夠付循環利息 ${MoneyFormat.currency(outlook.interest)}，卡債只會變多；每月至少要多繳 $extra 卡債才不會再增加"
        } else {
            "每月刷 ${MoneyFormat.currency(outlook.spending)}、利息 ${MoneyFormat.currency(outlook.interest)}，" +
                "繳 ${MoneyFormat.currency(outlook.payment)} 不夠；每月至少要多繳 $extra 卡債才不會再增加"
        }
    }

    /** 只繳最低應繳、且不再刷卡時，大約幾個月能還完；永遠還不完時為 null。 */
    fun monthsToClear(balance: Money, terms: CardTerms, monthlySpending: Money = 0, maxMonths: Int = 600): Int? {
        var remaining = balance
        for (month in 1..maxMonths) {
            val interest = monthlyInterest(remaining, terms.revolvingRatePercent)
            val payment = when (terms.payMode) {
                CardPayMode.MINIMUM -> minimumPayment(remaining, terms)
                CardPayMode.FULL -> remaining + interest
                CardPayMode.FIXED -> terms.fixedPayment ?: 0
            }
            val next = remaining + interest + monthlySpending - payment
            if (next >= remaining && next > 0) return null
            remaining = next.coerceAtLeast(0)
            if (remaining == 0L) return month
        }
        return null
    }
}
