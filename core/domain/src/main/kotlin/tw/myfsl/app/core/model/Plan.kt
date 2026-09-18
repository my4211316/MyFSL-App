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
    AUTO("自動計入", "固定金額，到期自動視為已發生"),
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
)

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
