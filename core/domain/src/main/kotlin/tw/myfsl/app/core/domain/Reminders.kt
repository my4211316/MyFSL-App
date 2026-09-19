package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.MoneyFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一則到期提醒（R-REM-01）。[billCardId] 不是 null 時，點了打開那張卡的帳單校正。 */
data class Reminder(
    /** 同一則提醒的識別碼（通知用它避免重複）。 */
    val id: String,
    val title: String,
    val text: String,
    val billCardId: Long? = null,
)

/**
 * 到期提醒（R-REM-01）：
 * - 本月到期會列出的付款（繳卡費、貸款月繳、每月固定的支出與轉帳），在到期前 [tw.myfsl.app.core.model.AppSettings.reminderDays] 天各提醒一次。
 *   已經記下或選了「這個月沒有」的不提醒；收入、循環利息與分期入帳不提醒（不是要付的錢）。
 * - 依帳單繳款的卡，結帳日隔天提醒對照帳單（這一期還沒輸入帳單時）。
 * - 設定成不提醒（天數空白）時都不發。
 */
object Reminders {

    fun forDate(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<Reminder> {
        val days = snapshot.settings.reminderDays
        // 設定成不提醒（空白）：到期與結帳提醒都不發。
        if (days.isEmpty()) return emptyList()
        val result = mutableListOf<Reminder>()
        run {
            DueItems.list(snapshot, through = date.plusDays(days.max().toLong()))
                .filter { it.kind == DueKind.CARD_PAYMENT || it.kind == DueKind.LOAN || (it.kind == DueKind.PLAN && !it.isIncome) }
                .forEach { due ->
                    val left = ChronoUnit.DAYS.between(date, due.date).toInt()
                    if (left !in days) return@forEach
                    val whenText = "${due.date.monthValue}/${due.date.dayOfMonth}"
                    val options = due.payOptions
                    val verb = if (due.kind == DueKind.CARD_PAYMENT) "截止" else "到期"
                    val amountText = if (options != null) {
                        "本期帳單 ${MoneyFormat.currency(options.full)}" + (options.minimum?.let { "，帳單最低 ${MoneyFormat.currency(it)}" } ?: "")
                    } else {
                        "本期要繳 ${MoneyFormat.currency(due.amount)}"
                    }
                    result += Reminder(
                        id = "${due.key}:$left",
                        title = "${due.title} 還有 $left 天$verb",
                        text = "$whenText $verb，$amountText。付了之後到記帳頁右上角的今天總覽，在「本月到期」點一下記下。",
                    )
                }
        }
        snapshot.activeCards.filter { it.hasCardSchedule }.forEach { card ->
            val cycle = CardRules.latestCycle(card, date.minusDays(1)) ?: return@forEach
            if (cycle.statement != date.minusDays(1)) return@forEach
            if (!cycle.statement.isAfter(snapshot.trackingFrom.minusDays(1))) return@forEach
            if (snapshot.statementOf(card.id, cycle.yearMonth) != null) return@forEach
            val estimate = BillCorrection.estimate(snapshot, card, cycle)
            result += Reminder(
                id = "bill:${card.id}:${DueItems.ymKey(cycle.yearMonth)}",
                title = "${card.name} 帳單已結帳",
                text = "App 估計 ${MoneyFormat.currency(estimate)}，${cycle.due.monthValue}/${cycle.due.dayOfMonth} 截止。收到帳單後點這裡對照，金額不同可以校正。",
                billCardId = card.id,
            )
        }
        return result
    }
}
