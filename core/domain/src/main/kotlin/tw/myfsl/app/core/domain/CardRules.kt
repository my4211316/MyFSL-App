package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
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
        val interest = monthlyInterest(balance, terms.revolvingRatePercent)
        return maxOf(terms.minPaymentFloor, byPercent, interest).coerceAtMost(balance)
    }

    /**
     * 多張卡合併成「信用卡合計」的條件：
     * 利率與最低應繳比例依餘額加權，下限相加，繳款方式與扣款帳戶以餘額最大的卡為準，
     * 計息與繳款日取最早的一天。沒有設定循環條件的卡不納入。
     */
    fun pooled(cards: List<Account>): CardTerms? {
        val withTerms = cards.filter { it.card != null }
        if (withTerms.isEmpty()) return null
        val total = withTerms.sumOf { it.balance.coerceAtLeast(0) }
        fun weighted(pick: (CardTerms) -> Double): Double =
            if (total <= 0) {
                withTerms.map { pick(it.card!!) }.average()
            } else {
                withTerms.sumOf { pick(it.card!!) * it.balance.coerceAtLeast(0) } / total
            }

        val lead = withTerms.maxByOrNull { it.balance }!!.card!!
        val fixed = withTerms.sumOf { it.card!!.fixedPayment ?: 0L }
        return CardTerms(
            revolvingRatePercent = weighted { it.revolvingRatePercent },
            minPaymentPercent = weighted { it.minPaymentPercent },
            minPaymentFloor = withTerms.sumOf { it.card!!.minPaymentFloor },
            payMode = lead.payMode,
            fixedPayment = fixed.takeIf { it > 0 },
            payAccountId = lead.payAccountId,
            payDay = withTerms.minOf { it.card!!.payDay },
            // 任一張卡填了既有卡循才合計；沒填的卡以整筆欠款計。
            revolvingBalance = if (withTerms.any { it.card!!.revolvingBalance != null }) {
                withTerms.sumOf { interestBase(it.balance, it.card) }
            } else {
                null
            },
        )
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
        val principalRepaid: Money get() = payment - interest
        val growing: Boolean get() = change > 0

        /** 要讓卡債開始下降，每月還要多繳多少。 */
        val extraToShrink: Money get() = if (change > 0) change else 0
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
        val extra = MoneyFormat.currency(outlook.extraToShrink)
        return if (outlook.payment <= outlook.interest) {
            "繳的錢還不夠付循環利息 ${MoneyFormat.currency(outlook.interest)}，卡債只會變多；每月要多繳 $extra 才會開始下降"
        } else {
            "每月刷 ${MoneyFormat.currency(outlook.spending)}、利息 ${MoneyFormat.currency(outlook.interest)}，" +
                "繳 ${MoneyFormat.currency(outlook.payment)} 不夠；每月要多繳 $extra 卡債才會開始下降"
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
