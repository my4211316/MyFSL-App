package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod

/**
 * 試算的付款假設（R-MIX-02）。
 *
 * 預算不分支付方式（R-MIX-01），但水位圖要知道錢哪一天離開帳戶：付現金是當月就扣，
 * 刷卡是進帳單、之後才用繳卡費扣，中間沒繳清的還會計息。所以試算需要一個假設。
 *
 * 這個假設是**設定裡的一個數字**，不是項目的屬性：
 * - 預設 0%「全部當現金付」——消費當月就扣款。刷卡遞延、分期、卡循是調度現金的手段，
 *   屬於情境比較要回答的問題，不該寫進基準線。
 * - 想看「繼續刷會怎樣」就把比例調高，或在試算開一個情境。
 *
 * **只作用在未來的計畫支出。** 已經欠的卡債、已經發生的分期、每期要繳的卡費與循環利息
 * 都照合約算，不受這個假設影響。
 */
data class PaymentAssumptionMix(
    /** 刷卡佔多少，0.0–1.0。 */
    val cardRatio: Double,
    /** 非刷卡的那一份用哪種方式付（決定從哪個帳戶扣）。 */
    val rest: PaymentMethod,
) {
    val cardPercent: Int get() = displayPercent(cardRatio)

    /** 全部當現金付。 */
    val allCash: Boolean get() = cardRatio <= 0.0

    /** 把某月的計畫金額拆成（刷卡, 非刷卡）；餘數留給非刷卡那一份。 */
    fun split(amount: Money): Pair<Money, Money> {
        if (amount == 0L) return 0L to 0L
        val card = when {
            cardRatio >= 1.0 -> amount
            cardRatio <= 0.0 -> 0L
            else -> Math.round(amount * cardRatio)
        }.coerceIn(0L, amount)
        return card to amount - card
    }
}

object PaymentAssumption {

    /** 非刷卡的部分用哪種方式付：現金（扣款帳戶依設定 R-ACC-01）。 */
    private val REST = PaymentMethod.CASH

    fun of(settings: AppSettings): PaymentAssumptionMix =
        PaymentAssumptionMix(settings.forecastCardPercent.coerceIn(0, 100) / 100.0, REST)

    fun of(snapshot: FinanceSnapshot): PaymentAssumptionMix = of(snapshot.settings)

    /** 給畫面看的說明。 */
    fun label(settings: AppSettings): String {
        val percent = settings.forecastCardPercent.coerceIn(0, 100)
        return if (percent == 0) "全部當現金付" else "刷卡 $percent%"
    }

    fun hint(settings: AppSettings): String {
        val percent = settings.forecastCardPercent.coerceIn(0, 100)
        return if (percent == 0) {
            "試算假設計畫的支出在消費當月就從帳戶扣。已經欠的卡債、分期與每期卡費照樣算。"
        } else {
            "試算假設計畫的支出有 $percent% 會刷卡，之後靠繳卡費扣款；其餘當月從帳戶扣。"
        }
    }
}
