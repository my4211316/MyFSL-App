package tw.myfsl.app.core.model

enum class FlowType(val label: String) {
    INCOME("收入"),
    EXPENSE("支出"),
    TRANSFER("轉帳"),
}

/** 支出的支付方式。預算依「項目 × 支付方式」歸屬。 */
enum class PaymentMethod(val label: String) {
    CASH("現金"),
    CREDIT_CARD("信用卡"),
    TRANSFER("轉帳"),
}

/** 金額落在月內哪一段。 */
enum class Timing(val label: String) {
    FIRST_HALF("上半月"),
    SECOND_HALF("下半月"),
    SPLIT("上下各半"),
}

enum class Flexibility(val label: String) {
    FIXED("固定"),
    FLEXIBLE("可調"),
}

/** 執行控管時如何取得實際數字。 */
enum class TrackingMode(val label: String, val hint: String) {
    AUTO("每月固定", "固定金額：到期時出現在記帳畫面的「本月到期」，點一下選支付方式記下"),
    LEDGER("依記帳", "可調項目：花多少由記帳加總，本週檢查用帳戶對帳補漏"),
    REPORT("每週回報", "現金分信封或能單獨查到金額時，每週回報還剩多少或累計多少"),
    CONFIRM("到期確認", "不定期項目：到期時確認實際金額"),
}

/** 本週檢查的輸入方式：現金問「還剩多少」，其他支付方式問「累計花費」。 */
enum class ReportInput(val label: String) {
    REMAINING("還剩"),
    SPENT_TO_DATE("累計"),
}

data class PlanGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/** 預算項目，例如「生活費」。支出的金額依支付方式分列在 [PlanLine]。 */
data class PlanItem(
    val id: Long = 0,
    val name: String,
    val groupId: Long,
    val type: FlowType,
    /** 收入：入帳帳戶；轉帳：轉出帳戶。支出不使用（由支付方式決定扣款帳戶）。 */
    val accountId: Long? = null,
    /** 轉帳：轉入帳戶，例如繳卡費時的信用卡。 */
    val toAccountId: Long? = null,
    val timing: Timing = Timing.SPLIT,
    val flexibility: Flexibility = Flexibility.FIXED,
    val tracking: TrackingMode = TrackingMode.AUTO,
    val note: String = "",
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    /**
     * 每月幾號發生（1–31；短月份取月底）。「每月固定」在這一天到期。
     * 沒填時依時點：上半月 1 號、下半月 16 號、上下各半兩天都有。
     */
    val dueDay: Int? = null,
    /** 轉帳到已依合約自動繳款的卡片或貸款時：true 表示這是「額外還款」，會另外計入；false 則不計（避免重複）。 */
    val extraRepayment: Boolean = false,
    /** 封存的起始月（年 × 12 + 月 − 1）；這之前的計畫仍算在歷史報表裡。 */
    val archivedFrom: Int? = null,
) {
    /**
     * 某月的發生日與金額：有 [dueDay] 時一天；沒有時依時點拆成上、下半月。
     * 回傳 (日期, 金額) 清單，金額為 0 的略過。
     */
    fun occurrences(year: Int, month: Int, monthAmount: Money): List<Pair<java.time.LocalDate, Money>> {
        val ym = java.time.YearMonth.of(year, month)
        fun day(d: Int) = ym.atDay(d.coerceIn(1, ym.lengthOfMonth()))
        val list = if (dueDay != null) {
            listOf(day(dueDay) to monthAmount)
        } else {
            val (first, second) = timing.split(monthAmount)
            listOf(day(1) to first, day(16) to second)
        }
        return list.filter { it.second != 0L }
    }
}

/** 計畫表的一列：支出為「項目 × 支付方式」；收入與轉帳的 [method] 為 null。 */
data class PlanLine(
    val itemId: Long,
    val method: PaymentMethod? = null,
)

/** 某年度所有計畫列的 12 個月金額：索引 0 = 1 月。 */
typealias MonthlyAmounts = Map<PlanLine, List<Money>>

/** 依時點把月金額拆成（上半月, 下半月）；平分時奇數的 1 元放在下半月。 */
fun Timing.split(monthAmount: Money): Pair<Money, Money> = when (this) {
    Timing.FIRST_HALF -> monthAmount to 0L
    Timing.SECOND_HALF -> 0L to monthAmount
    Timing.SPLIT -> (monthAmount / 2).let { it to monthAmount - it }
}

fun Timing.amountIn(half: Half, monthAmount: Money): Money =
    split(monthAmount).let { if (half == Half.FIRST) it.first else it.second }
