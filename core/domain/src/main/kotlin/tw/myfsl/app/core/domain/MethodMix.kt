package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一個項目的支付結構（R-MIX-02／R-MIX-03）。
 *
 * 預算只有一個金額，試算仍需要知道這筆錢是「當月從帳戶扣掉」還是「變成卡債」，
 * 所以每個項目都算出一個刷卡比例：
 * - 預設照項目自己的支付方式（刷卡 100% 或 0%）。
 * - 項目打開「依實際比例」且最近幾個完整月份累積到足夠筆數時，改用實際的刷卡金額比例。
 *
 * [rest] 是非刷卡的那一份要用哪種方式付（決定從哪個帳戶扣）。
 */
data class MethodMix(
    val itemId: Long,
    /** 刷卡佔多少，0.0–1.0。 */
    val cardRatio: Double,
    /** 非刷卡的那一份用哪種方式付。 */
    val rest: PaymentMethod,
    /** true = 這個比例來自實際紀錄，false = 照項目設定。 */
    val fromActual: Boolean,
    /** 取樣的筆數與金額（給畫面說明用）。 */
    val sampleCount: Int = 0,
    val sampleCard: Money = 0,
    val sampleTotal: Money = 0,
) {
    val cardPercent: Int get() = displayPercent(cardRatio)

    /** 只用一種方式時回傳那個方式；混合時為 null。 */
    val singleMethod: PaymentMethod?
        get() = when {
            cardRatio >= 1.0 -> PaymentMethod.CREDIT_CARD
            cardRatio <= 0.0 -> rest
            else -> null
        }

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

object MethodMixRules {

    /** 取樣範圍：本月之前這麼多個完整月份。 */
    const val SAMPLE_MONTHS = 3

    /** 少於這麼多筆就不採用實際比例，照項目設定推。 */
    const val MIN_SAMPLES = 6

    /** 項目的支付方式；沒設定時當成現金。 */
    fun methodOf(item: PlanItem): PaymentMethod = item.method ?: PaymentMethod.CASH

    /** 項目設定的支付結構（不看實際紀錄）。 */
    fun fromItem(item: PlanItem): MethodMix {
        val method = methodOf(item)
        return MethodMix(
            itemId = item.id,
            cardRatio = if (method == PaymentMethod.CREDIT_CARD) 1.0 else 0.0,
            rest = if (method == PaymentMethod.CREDIT_CARD) PaymentMethod.CASH else method,
            fromActual = false,
        )
    }

    /**
     * 項目實際的支付結構（R-MIX-03）。
     * 只看 [SAMPLE_MONTHS] 個完整月份、算進預算的支出；筆數不足或項目關掉自動比例時回到 [fromItem]。
     */
    fun of(snapshot: FinanceSnapshot, item: PlanItem, today: LocalDate = snapshot.today): MethodMix {
        val base = fromItem(item)
        if (item.type != FlowType.EXPENSE || !item.useActualMix) return base
        val end = YearMonth.from(today)
        val start = end.minusMonths(SAMPLE_MONTHS.toLong())
        val sample = snapshot.ledger.filter { entry ->
            entry.itemId == item.id &&
                entry.type == FlowType.EXPENSE &&
                entry.countsForBudget &&
                entry.amount > 0 &&
                entry.method != null &&
                YearMonth.from(entry.date) >= start && YearMonth.from(entry.date) < end
        }
        if (sample.size < MIN_SAMPLES) return base
        val total = sample.sumOf { it.amount }
        if (total <= 0) return base
        val card = sample.filter { it.method == PaymentMethod.CREDIT_CARD }.sumOf { it.amount }
        val rest = sample.filter { it.method != PaymentMethod.CREDIT_CARD }
            .groupBy { it.method!! }
            .maxByOrNull { (_, entries) -> entries.sumOf { it.amount } }
            ?.key
            ?: base.rest
        return MethodMix(
            itemId = item.id,
            cardRatio = card.toDouble() / total,
            rest = rest,
            fromActual = true,
            sampleCount = sample.size,
            sampleCard = card,
            sampleTotal = total,
        )
    }

    /** 一次算好所有項目，避免逐項重掃記帳。 */
    fun all(snapshot: FinanceSnapshot, today: LocalDate = snapshot.today): Map<Long, MethodMix> =
        snapshot.items.associate { it.id to of(snapshot, it, today) }
}
