package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.MoneyFormat
import java.time.DayOfWeek

/** 設定畫面的草稿；數字欄位用字串。 */
data class SettingsDraft(
    val safetyLevel: String,
    val horizonMonths: Int,
    val checkInDay: DayOfWeek,
    val pickCard: Boolean,
    val cashAccountId: Long?,
    val transferAccountId: Long?,
    val cardPostingDays: String,
    val defaultCardId: Long? = null,
    /** 到期前幾天提醒，例如「7, 3」；空白為不提醒（R-REM-01）。 */
    val reminderDays: String = "7, 3",
)

object SettingsForm {

    val HORIZONS = listOf(12, 24, 36)

    object Field {
        const val SAFETY = "safety"
        const val POSTING_DAYS = "postingDays"
        const val CASH_ACCOUNT = "cashAccount"
        const val TRANSFER_ACCOUNT = "transferAccount"
        const val DEFAULT_CARD = "defaultCard"
        const val REMINDER_DAYS = "reminderDays"
    }

    data class Result(val settings: AppSettings?, val errors: Map<String, String>) {
        val ok: Boolean get() = errors.isEmpty() && settings != null
    }

    fun fromSettings(settings: AppSettings) = SettingsDraft(
        safetyLevel = settings.safetyLevel.toString(),
        horizonMonths = settings.horizonMonths,
        checkInDay = settings.checkInDay,
        pickCard = settings.pickCard,
        cashAccountId = settings.cashAccountId,
        transferAccountId = settings.transferAccountId,
        cardPostingDays = settings.cardPostingDays.toString(),
        defaultCardId = settings.defaultCardId,
        reminderDays = settings.reminderDays.joinToString(", "),
    )

    fun validate(draft: SettingsDraft, current: AppSettings, accounts: List<Account>): Result {
        val errors = linkedMapOf<String, String>()
        val safety = MoneyFormat.parse(draft.safetyLevel)
        if (safety == null || safety < 0) errors[Field.SAFETY] = "安全線要是 0 以上的金額"
        val days = draft.cardPostingDays.trim().toIntOrNull()
        if (days == null || days !in 0..14) errors[Field.POSTING_DAYS] = "要在 0 到 14 天之間"
        val liquid = accounts.filter { !it.archived && it.kind.isLiquid }
        if (draft.cashAccountId != null && liquid.none { it.id == draft.cashAccountId }) errors[Field.CASH_ACCOUNT] = "這個帳戶已不存在"
        if (draft.transferAccountId != null && liquid.none { it.id == draft.transferAccountId }) {
            errors[Field.TRANSFER_ACCOUNT] = "這個帳戶已不存在"
        }
        if (draft.defaultCardId != null && accounts.none { !it.archived && it.id == draft.defaultCardId && it.kind == AccountKind.CREDIT_CARD }) {
            errors[Field.DEFAULT_CARD] = "這張卡已不存在"
        }
        val reminders = parseReminderDays(draft.reminderDays)
        if (reminders == null) errors[Field.REMINDER_DAYS] = "用逗號分開的天數，每個 1 到 30，例如 7, 3"
        if (errors.isNotEmpty()) return Result(null, errors)
        return Result(
            current.copy(
                safetyLevel = safety!!,
                horizonMonths = draft.horizonMonths.takeIf { it in HORIZONS } ?: 24,
                checkInDay = draft.checkInDay,
                pickCard = draft.pickCard,
                cashAccountId = draft.cashAccountId,
                transferAccountId = draft.transferAccountId,
                cardPostingDays = days!!,
                defaultCardId = draft.defaultCardId,
                reminderDays = reminders!!,
            ),
            emptyMap(),
        )
    }

    /** 「7, 3」→ [7, 3]（由大到小、不重複）；空白為不提醒；看不懂或超出 1–30 天為 null。 */
    fun parseReminderDays(text: String): List<Int>? {
        val parts = text.split(',', '、', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val days = parts.map { it.toIntOrNull() ?: return null }
        if (days.any { it !in 1..30 }) return null
        return days.distinct().sortedDescending()
    }

    /** 沒有指定時實際會用哪個帳戶，給畫面顯示「自動：xxx」。 */
    fun autoAccountName(accounts: List<Account>, kind: AccountKind): String? {
        val liquid = accounts.filter { !it.archived && it.kind.isLiquid }
        return (liquid.firstOrNull { it.kind == kind } ?: liquid.firstOrNull())?.name
    }

    private val WEEKDAY = listOf("一", "二", "三", "四", "五", "六", "日")
    fun weekdayLabel(day: DayOfWeek): String = "週" + WEEKDAY[day.value - 1]
}
