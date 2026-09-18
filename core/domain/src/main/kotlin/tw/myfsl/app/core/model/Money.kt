package tw.myfsl.app.core.model

import java.util.Locale
import kotlin.math.abs

/** 金額一律以「新台幣元」的 Long 表示，避免浮點誤差。 */
typealias Money = Long

object MoneyFormat {
    /** 12,345 */
    fun plain(amount: Money): String = String.format(Locale.US, "%,d", amount)

    /** $12,345 / −$12,345 */
    fun currency(amount: Money): String =
        (if (amount < 0) "−$" else "$") + String.format(Locale.US, "%,d", abs(amount))

    /** +$12,345 / −$12,345 / $0 */
    fun signed(amount: Money): String = if (amount > 0) "+" + currency(amount) else currency(amount)

    /** 12.3萬 / 8,500：圖表軸與小空間使用。 */
    fun compact(amount: Money): String {
        val a = abs(amount)
        val sign = if (amount < 0) "−" else ""
        if (a < 10_000) return sign + String.format(Locale.US, "%,d", a)
        val wan = a / 10_000.0
        val text = if (wan >= 100) {
            String.format(Locale.US, "%,.0f", wan)
        } else {
            String.format(Locale.US, "%.1f", wan).removeSuffix(".0")
        }
        return "$sign${text}萬"
    }

    /** 解析使用者輸入（允許逗號與空白），無法解析時回傳 null。 */
    fun parse(text: String): Money? =
        text.replace(",", "").replace(" ", "").takeIf { it.isNotEmpty() }?.toLongOrNull()
}
