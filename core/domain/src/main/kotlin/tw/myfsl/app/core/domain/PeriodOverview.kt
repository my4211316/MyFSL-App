package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import java.time.temporal.ChronoUnit

/** 本期畫面的「接下來到期」一列。 */
data class UpcomingItem(val period: Period, val label: String, val amount: Money, val isIncome: Boolean)

data class CheckInReminder(
    /** 上次檢查距今幾天；從沒檢查過為 null。 */
    val daysSinceLast: Long?,
    val confirmCount: Int,
    val reconcileCount: Int,
    val missedLabel: String?,
    /** 今天是設定的每週檢查日。 */
    val isCheckInDay: Boolean = false,
) {
    val due: Boolean get() = daysSinceLast == null || daysSinceLast >= 7 || (isCheckInDay && daysSinceLast > 0)

    val text: String
        get() = listOfNotNull(
            daysSinceLast?.let { if (it == 0L) "今天檢查過" else "上次 $it 天前" } ?: "還沒檢查過",
            confirmCount.takeIf { it > 0 }?.let { "$it 項到期確認" },
            reconcileCount.takeIf { it > 0 }?.let { "$it 個帳戶對帳" },
        ).joinToString(" · ")
}

data class PeriodOverview(
    val period: Period,
    /** 本月時間進度（上下各半的算法）。 */
    val timePercent: Int,
    val liquid: Money,
    val liquidAccounts: List<Account>,
    /** 要留給卡費的現金（R-CARD-25）；[liquid] 扣掉它才是真正可以用的。 */
    val cardReserve: Money = 0,
    /** 未來每月最低水位，第一個值為本月。 */
    val monthlyLows: List<Money>,
    val monthLabels: List<String>,
    val safetyLevel: Money,
    val lowest: Money,
    val lowestLabel: String?,
    /** 首次低於安全線距今幾個月；不會低於時為 null。 */
    val monthsUntilBelowSafety: Int?,
    val checkIn: CheckInReminder,
    val budget: List<LineProgress>,
    val upcoming: List<UpcomingItem>,
    /** 需要提醒備份時的文字；最近備份過為 null。 */
    val backupReminder: String? = null,
    /** 總水位夠、但某個扣款帳戶會不夠扣時的提醒（R-FC-12）。 */
    val shortfall: String? = null,
)

/** 本期畫面的資料：現金、水位、本週檢查、可調支出進度、接下來到期。 */
object PeriodOverviewCalculator {

    fun build(snapshot: FinanceSnapshot, upcomingPeriods: Int = 3, upcomingLimit: Int = 8): PeriodOverview {
        val today = snapshot.today
        val input = BaselineBuilder.build(snapshot)
        val result = CashFlowEngine.run(input)
        val lows = ForecastSummary.monthlyLows(result)
        val labels = result.periods.map { it.period }.distinctBy { it.yearMonth }.map { ForecastSummary.shortLabel(it) }
        val start = Period.of(today)

        val upcoming = result.periods
            .filter { it.period.index < start.index + upcomingPeriods }
            .flatMap { period ->
                period.events.mapNotNull { resolved ->
                    val event = resolved.event
                    when {
                        resolved.amount <= 0 -> null
                        event.flexible -> null
                        event.kind == EventKind.INCOME -> UpcomingItem(event.period, event.label, resolved.amount, true)
                        event.kind == EventKind.EXPENSE && event.method == PaymentMethod.CREDIT_CARD -> null
                        else -> UpcomingItem(event.period, event.label, resolved.amount, false)
                    }
                }
            }
            .sortedWith(compareBy<UpcomingItem> { it.period.index }.thenByDescending { it.amount })
            .take(upcomingLimit)

        val last = snapshot.lastCheckIn?.date
        return PeriodOverview(
            period = start,
            timePercent = displayPercent(today.dayOfMonth.toDouble() / today.lengthOfMonth()),
            liquid = snapshot.activeAccounts.filter { it.kind.isLiquid }.sumOf { it.balance },
            liquidAccounts = snapshot.activeAccounts.filter { it.kind.isLiquid },
            cardReserve = CardRules.reserve(snapshot),
            monthlyLows = lows,
            monthLabels = labels,
            safetyLevel = snapshot.settings.safetyLevel,
            lowest = result.lowestLiquid,
            lowestLabel = result.lowest?.period?.let { ForecastSummary.shortLabel(it) },
            monthsUntilBelowSafety = result.firstBelowSafety?.let { ForecastSummary.monthsUntil(start, it.period) },
            checkIn = CheckInReminder(
                daysSinceLast = last?.let { ChronoUnit.DAYS.between(it, today) },
                // 到期確認＋到期還沒記下的項目（R-DUE-05）
                confirmCount = CheckInRules.confirmLines(snapshot).size + CheckInRules.dueLines(snapshot).size,
                reconcileCount = CheckInRules.reconciles(snapshot).size,
                missedLabel = RecordRules.missedLabel(RecordRules.missedSummary(snapshot)),
                isCheckInDay = today.dayOfWeek == snapshot.settings.checkInDay,
            ),
            budget = BudgetProgressCalculator.forMonth(snapshot),
            upcoming = upcoming,
            backupReminder = backupReminder(snapshot),
            shortfall = result.firstShortfall?.let { (p, id) ->
                "「${result.input.accountName(id)}」約 ${ForecastSummary.shortLabel(p.period)} 會不夠扣款，記得先從其他帳戶轉入"
            },
        )
    }

    /** 超過這麼多天沒備份就提醒。 */
    const val BACKUP_REMIND_DAYS = 30L

    /**
     * 資料只存在這支手機，所以有自己記的資料之後要提醒備份：
     * 從沒備份過、或距上次備份超過 [BACKUP_REMIND_DAYS] 天。只有示意資料時不提醒。
     */
    fun backupReminder(snapshot: FinanceSnapshot): String? {
        // 示意資料的記帳與校正寫入時間都是 0 或 1；使用者自己寫的一定是真實時間。
        val hasOwnData = snapshot.ledger.any { it.createdAt > 1 } || snapshot.lastCheckIn != null ||
            snapshot.accounts.any { it.balanceRecordedAt > 1 }
        if (!hasOwnData) return null
        val last = snapshot.settings.lastBackupEpochDay ?: return "還沒備份過。資料只存在這支手機，建議到設定匯出完整備份"
        val days = snapshot.today.toEpochDay() - last
        return if (days >= BACKUP_REMIND_DAYS) "上次備份是 $days 天前，建議再匯出一次" else null
    }
}
