package tw.myfsl.app.core.model

enum class FlowType(val label: String) {
    INCOME("收入"),
    EXPENSE("支出"),
    TRANSFER("轉帳"),
}

/**
 * 支出的支付方式。**計畫列自己帶支付方式**（R-MIX-01）：那是使用者編預算時的決定，不是 App 的推估。
 * 刷卡是隔月才真的付錢，所以「這筆打算用現金付還是刷卡」會直接改變月現金流的時間點。
 */
enum class PaymentMethod(val label: String) {
    CASH("現金"),
    CREDIT_CARD("信用卡"),
    TRANSFER("轉帳"),
}

enum class Flexibility(val label: String) {
    FIXED("固定"),
    FLEXIBLE("可調"),
}

/** 執行控管時如何取得實際數字。 */
enum class TrackingMode(val label: String, val hint: String) {
    AUTO("每月固定", "固定金額：到期時出現在「本月到期」（記帳頁右上角的今天總覽；3 天內到期會在記帳頁提示），點一下選支付方式記下"),
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

/** 預算項目，例如「生活費」。支出的金額依支付方式分列在 [PlanLine]（R-MIX-01）。 */
data class PlanItem(
    val id: Long = 0,
    val name: String,
    val groupId: Long,
    val type: FlowType,
    /** 收入：入帳帳戶；轉帳：轉出帳戶。支出不使用（由支付方式決定扣款帳戶）。 */
    val accountId: Long? = null,
    /** 轉帳：轉入帳戶，例如繳卡費時的信用卡。 */
    val toAccountId: Long? = null,
    val flexibility: Flexibility = Flexibility.FIXED,
    val tracking: TrackingMode = TrackingMode.AUTO,
    val note: String = "",
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    /**
     * 每月幾號發生（1–31；短月份取月底）。「每月固定」在這一天到期，也會提醒（R-REM-01）。
     * **沒填就是沒填**（R-PER-02）：App 不替你猜哪一天。沒填的項目整個月都可以點一下付掉，
     * 不會提醒，試算也只算在「這個月」，不放在任何一天。
     */
    val dueDay: Int? = null,
    /** 轉帳到已依合約自動繳款的卡片或貸款時：true 表示這是「額外還款」，會另外計入；false 則不計（避免重複）。 */
    val extraRepayment: Boolean = false,
    /** 封存的起始月（年 × 12 + 月 − 1）；這之前的計畫仍算在歷史報表裡。 */
    val archivedFrom: Int? = null,
) {
    /**
     * 某月的到期日：有填 [dueDay] 才有（短月份取月底）；沒填時為 null，
     * 表示「這個月，但不知道哪一天」（R-PER-02）。
     */
    fun dueDateIn(year: Int, month: Int): java.time.LocalDate? {
        val day = dueDay ?: return null
        val ym = java.time.YearMonth.of(year, month)
        return ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
    }
}

/**
 * 計畫表的一列：**一個項目 × 一種支付方式**（R-MIX-01）。
 * 例如「生活費」可以同時有現金列（每月 10,000）與信用卡列（每月 13,000）。
 * 收入與轉帳沒有支付方式（[method] 為 null），一個項目就是一列。
 */
data class PlanLine(
    val itemId: Long,
    val method: PaymentMethod? = null,
)

/** 某年度所有計畫列的 12 個月金額：索引 0 = 1 月。 */
typealias MonthlyAmounts = Map<PlanLine, List<Money>>

