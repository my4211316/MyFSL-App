package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.InstallmentPeriod
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.TrackingMode
import tw.myfsl.app.core.model.split
import java.time.YearMonth

data class ItemActualView(
    val amount: Money,
    val status: ActualStatus?,
    /** 本月是否已經有記帳，或做過到期確認。 */
    val reported: Boolean,
)

object ActualCalculator {

    /**
     * 計畫列某月至今的實際金額 = 當月該列記帳的合計（含對帳差額、到期確認、到期記下）。
     * 分期各期入帳的本金不算（消費當月已經算過全額）；延期付款算在原本的月份以外，不在這裡。
     */
    fun actualFor(
        line: PlanLine,
        year: Int,
        month: Int,
        actuals: List<ItemActual>,
        ledger: List<LedgerEntry>,
    ): ItemActualView {
        val status = actuals.firstOrNull {
            it.itemId == line.itemId && it.method == line.method && it.year == year && it.month == month
        }?.status
        val entries = ledger.filter {
            it.itemId == line.itemId && it.method == line.method && it.countsForBudget &&
                it.postingKey?.startsWith(tw.myfsl.app.core.model.PostingKeys.DEFERRAL) != true &&
                it.date.year == year && it.date.monthValue == month
        }
        return ItemActualView(
            amount = entries.sumOf { it.amount },
            status = status,
            reported = status != null || entries.isNotEmpty(),
        )
    }
}

/**
 * 產生「現況」基準線（R-FC）：從今天所在的半月開始，只放**今天以後**才會發生的事。
 *
 * - 起始餘額為各帳戶目前推算的餘額；每張信用卡各自一個帳戶，未指定卡片的刷卡算在預設卡片。
 * - 到期項目（R-DUE）：起算日（含）以前到期的視為已在餘額裡；之後到期、已經記下的不再預測；
 *   到期了還沒記下的放在今天所在的半月，還沒到期的依到期日放。
 * - 每月固定的項目本月以前看到期清單；依記帳、回報、確認的項目本月放「計畫 − 已發生」。
 * - 延期款項獨立列出，金額固定。
 */
object BaselineBuilder {

    fun build(snapshot: FinanceSnapshot, periodCount: Int = snapshot.settings.horizonMonths * 2): ForecastInput {
        val today = snapshot.today
        val start = Period.of(today)
        val end = start.index + periodCount
        val active = snapshot.activeAccounts
        val defaultCard = snapshot.defaultCardId ?: CashFlowEngine.FALLBACK_CARD_ID

        val seeds = active.map { account ->
            val extra = if (account.id == defaultCard) snapshot.unassignedCardSpending else 0L
            AccountSeed(account.id, account.name, account.kind, account.balance + extra)
        } + if (snapshot.defaultCardId == null) {
            listOf(AccountSeed(CashFlowEngine.FALLBACK_CARD_ID, "信用卡", AccountKind.CREDIT_CARD, snapshot.unassignedCardSpending))
        } else {
            emptyList()
        }

        val methodAccounts = buildMap {
            PaymentMethod.entries.forEach { method ->
                val id = if (method == PaymentMethod.CREDIT_CARD) defaultCard else snapshot.methodAccountId(method)
                if (id != null) put(method, id)
            }
        }
        val input = ForecastInput(start, periodCount, seeds, emptyList(), snapshot.settings.safetyLevel, methodAccounts)
        val events = ArrayList<FlowEvent>()

        val months = Period.range(start, periodCount).map { it.yearMonth }.distinct()
        val currentMonth = YearMonth.from(today)

        for (ym in months) {
            for (item in snapshot.items) {
                if (!snapshot.isItemActiveIn(item, ym.year, ym.monthValue)) continue
                // 繳給已依合約自動繳款的卡片或貸款：除非標成額外還款，否則不計（R-PAY-01）。
                if (item.type == FlowType.TRANSFER && snapshot.isAutoManagedDebt(item.toAccountId) && !item.extraRepayment) continue
                val lines = if (item.type == FlowType.EXPENSE) {
                    snapshot.linesOf(item.id).filter { it.method != null }
                } else {
                    listOf(PlanLine(item.id, null))
                }
                for (line in lines) {
                    val planned = snapshot.planAmount(line, ym.year, ym.monthValue)
                    if (planned <= 0) continue
                    if (item.tracking == TrackingMode.AUTO) {
                        // 每月固定：本月以前看到期清單（還沒記下的才算，見 addDueItems）；之後的月份依到期日放。
                        if (!ym.isAfter(currentMonth)) continue
                        item.occurrences(ym.year, ym.monthValue, planned)
                            .forEach { (date, amount) -> events.addIfInRange(item, line, Period.of(date), amount, input, end) }
                        continue
                    }
                    var monthAmount = planned
                    if (ym == currentMonth) {
                        val actual = ActualCalculator.actualFor(line, ym.year, ym.monthValue, snapshot.actuals, snapshot.ledger)
                        monthAmount = when (actual.status) {
                            ActualStatus.DONE, ActualStatus.POSTPONED -> 0
                            else -> (planned - actual.amount).coerceAtLeast(0)
                        }
                    }
                    if (monthAmount <= 0L) continue
                    // 已經過了的發生日，剩下的額度移到今天所在的半月。
                    item.occurrences(ym.year, ym.monthValue, monthAmount).forEach { (date, amount) ->
                        val period = if (date.isAfter(today)) Period.of(date) else start
                        events.addIfInRange(item, line, period, amount, input, end)
                    }
                }
            }
        }

        addDueItems(snapshot, input, end, events)
        addDeferrals(snapshot, input, end, events)
        val beyond = addInstallments(snapshot, input, end, events)
        addCardSchedule(snapshot, input, end, events)
        addLoanSchedules(snapshot, input, end, events)
        return input.copy(events = events, installmentsBeyond = beyond)
    }

    private fun MutableList<FlowEvent>.addIfInRange(
        item: PlanItem,
        line: PlanLine,
        period: Period,
        amount: Money,
        input: ForecastInput,
        endIndex: Int,
        source: EventSource = EventSource.PLAN,
    ) {
        if (amount <= 0L || period.index < input.start.index || period.index >= endIndex) return
        add(toEvent(item, line.method, period, amount, input).copy(source = source))
    }

    /** 下一個到期日：本月的 [day] 還沒到就是本月，否則下個月（短月份取月底）。 */
    fun nextDue(today: java.time.LocalDate, day: Int): java.time.LocalDate {
        val ym = YearMonth.from(today)
        val thisMonth = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
        if (thisMonth.isAfter(today)) return thisMonth
        val next = ym.plusMonths(1)
        return next.atDay(day.coerceIn(1, next.lengthOfMonth()))
    }

    /** 本月以前每月固定、還沒記下的到期項目：到期了放今天所在的半月，還沒到期的依到期日放。 */
    private fun addDueItems(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        DueItems.list(snapshot).filter { it.kind == DueKind.PLAN }.forEach { due ->
            val item = due.item ?: return@forEach
            events.addIfInRange(item, PlanLine(item.id, due.method), periodFor(snapshot, due.date, input), due.amount, input, endIndex)
        }
    }

    /** 到期日所在的期別；已經到期（還沒記下）的放在今天所在的半月。 */
    private fun periodFor(snapshot: FinanceSnapshot, date: java.time.LocalDate, input: ForecastInput): Period =
        if (date.isAfter(snapshot.today)) Period.of(date) else input.start

    /** 延期款項（R-DEF）：未付清的，在到期月份（已過期的算在本期）依項目時點放入。 */
    private fun addDeferrals(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        val today = snapshot.today
        snapshot.deferrals.filter { !it.settled && it.amount > 0 }.forEach { deferral ->
            val item = snapshot.item(deferral.itemId) ?: return@forEach
            val due = YearMonth.of(deferral.dueYear, deferral.dueMonth)
            val line = deferral.line
            if (!due.isAfter(YearMonth.from(today))) {
                events.addIfInRange(item, line, input.start, deferral.amount, input, endIndex, EventSource.DEFERRAL)
            } else {
                item.occurrences(due.year, due.monthValue, deferral.amount).forEach { (date, amount) ->
                    events.addIfInRange(item, line, Period.of(date), amount, input, endIndex, EventSource.DEFERRAL)
                }
            }
        }
    }

    fun toEvent(item: PlanItem, method: PaymentMethod?, period: Period, amount: Money, input: ForecastInput): FlowEvent =
        FlowEvent(
            period = period,
            kind = when (item.type) {
                FlowType.INCOME -> EventKind.INCOME
                FlowType.EXPENSE -> EventKind.EXPENSE
                FlowType.TRANSFER -> EventKind.TRANSFER
            },
            amount = amount,
            label = item.name,
            fromAccountId = when (item.type) {
                FlowType.INCOME -> null
                FlowType.EXPENSE -> method?.let { input.methodAccounts[it] }
                FlowType.TRANSFER -> input.mapAccount(item.accountId)
            },
            toAccountId = when (item.type) {
                FlowType.INCOME -> input.mapAccount(item.accountId)
                FlowType.EXPENSE -> null
                FlowType.TRANSFER -> input.mapAccount(item.toAccountId)
            },
            itemId = item.id,
            groupId = item.groupId,
            method = if (item.type == FlowType.EXPENSE) method else null,
            flexible = item.flexibility == Flexibility.FLEXIBLE,
            source = EventSource.PLAN,
        )

    /**
     * 分期：還沒入帳的每期（R-DUE；到期了還沒記下的放今天所在的半月）。本金不再算成支出（刷卡當月已算過預算），手續費算成支出。
     * 回傳試算期間之後才入帳的本金（依卡片），期末總負債要算進去。
     */
    private fun addInstallments(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>): Map<Long, Money> {
        val fallback = input.methodAccounts[PaymentMethod.CREDIT_CARD] ?: CashFlowEngine.FALLBACK_CARD_ID
        val beyond = mutableMapOf<Long, Money>()
        snapshot.installments.filter { !it.settled }.forEach { installment ->
            val card = installment.cardAccountId?.takeIf { id -> input.accounts.any { it.id == id } } ?: fallback
            val pending = InstallmentRules.unposted(snapshot, installment)
            fun at(period: InstallmentPeriod) = periodFor(snapshot, Period.fromIndex(period.periodIndex).startDate, input)
            pending.filter { at(it).index >= endIndex }.sumOf { it.principal }.takeIf { it > 0 }?.let {
                beyond[card] = (beyond[card] ?: 0L) + it
            }
            pending
                .filter { at(it).index < endIndex }
                .forEach { period ->
                    val at = at(period)
                    val name = snapshot.item(installment.itemId)?.name ?: "分期"
                    if (period.principal > 0) {
                        events += FlowEvent(
                            period = at, kind = EventKind.EXPENSE, amount = period.principal,
                            label = "$name 分期 ${period.number}/${installment.months}",
                            fromAccountId = card, itemId = installment.itemId,
                            method = PaymentMethod.CREDIT_CARD, relatedAccountId = card,
                            countAsExpense = false, source = EventSource.INSTALLMENT,
                        )
                    }
                    if (period.fee > 0) {
                        events += FlowEvent(
                            period = at, kind = EventKind.EXPENSE, amount = period.fee,
                            label = "$name 分期手續費", fromAccountId = card, itemId = installment.itemId,
                            method = PaymentMethod.CREDIT_CARD, relatedAccountId = card,
                            source = EventSource.INSTALLMENT,
                        )
                    }
                }
        }
        return beyond
    }

    /**
     * 有循環條件的卡，逐卡在各自的繳款日產生：先計息，再依該卡繳款方式扣款（R-CARD-03）。
     * 從起算日之後的第一個繳款日開始；已經記下的月份跳過，到期了還沒記下的放在今天所在的半月（R-DUE）。
     */
    private fun addCardSchedule(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        snapshot.activeCards.forEach { card ->
            val terms = card.card ?: return@forEach
            val payAccount = terms.payAccountId?.takeIf { id -> input.accounts.any { it.id == id && it.kind.isLiquid } }
                ?: snapshot.methodAccountId(PaymentMethod.TRANSFER)
            var due = nextDue(snapshot.trackingFrom, terms.payDay)
            var first = true
            while (periodFor(snapshot, due, input).index < endIndex) {
                val period = periodFor(snapshot, due, input)
                val ym = YearMonth.from(due)
                if (DueItems.cardInterestKey(card.id, ym) !in snapshot.recordedKeys) events += FlowEvent(
                    period = period,
                    kind = EventKind.EXPENSE,
                    amount = 0,
                    label = "${card.name} 循環利息",
                    fromAccountId = card.id,
                    relatedAccountId = card.id,
                    interestRatePercent = terms.revolvingRatePercent,
                    // 既有卡循只影響第一次計息；之後沒繳清的欠款都會計息。
                    interestBase = if (first) terms.revolvingBalance else null,
                    source = EventSource.CARD_SCHEDULE,
                ).also { first = false }
                if (payAccount != null && DueItems.cardPaymentKey(card.id, ym) !in snapshot.recordedKeys) {
                    val payment = when (terms.payMode) {
                        CardPayMode.FULL -> FlowEvent(
                            period = period, kind = EventKind.TRANSFER, amount = 0, label = "繳 ${card.name}（當期全額）",
                            fromAccountId = payAccount, toAccountId = card.id, relatedAccountId = card.id,
                            payFullBalance = true, source = EventSource.CARD_SCHEDULE,
                        )

                        CardPayMode.MINIMUM -> FlowEvent(
                            period = period, kind = EventKind.TRANSFER, amount = 0, label = "繳 ${card.name}（最低應繳）",
                            fromAccountId = payAccount, toAccountId = card.id, relatedAccountId = card.id,
                            minimumPayment = MinimumPaymentRule(terms.minPaymentPercent, terms.minPaymentFloor, terms.revolvingRatePercent),
                            source = EventSource.CARD_SCHEDULE,
                        )

                        CardPayMode.FIXED -> FlowEvent(
                            period = period, kind = EventKind.TRANSFER, amount = terms.fixedPayment ?: 0, label = "繳 ${card.name}",
                            fromAccountId = payAccount, toAccountId = card.id, relatedAccountId = card.id,
                            source = EventSource.CARD_SCHEDULE,
                        )
                    }
                    if (payment.amount > 0 || payment.payFullBalance || payment.minimumPayment != null) events += payment
                }
                due = nextDue(due, terms.payDay)
            }
        }
    }

    /** 有攤還條件的貸款：從起算日之後還沒記下的繳款日開始，依目前餘額與剩餘期數攤還（R-DUE）。 */
    private fun addLoanSchedules(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        snapshot.activeAccounts
            .filter { it.kind == AccountKind.LOAN || it.kind == AccountKind.POLICY_LOAN }
            .forEach { loan ->
                val terms = loan.loan ?: return@forEach
                if (loan.balance <= 0) return@forEach
                val schedule = LoanAmortization.schedule(loan.balance, terms.annualRatePercent, terms.remainingMonths, terms.method)
                var due = nextDue(snapshot.trackingFrom, terms.payDay)
                var next = 0
                while (next < schedule.size) {
                    val period = periodFor(snapshot, due, input)
                    if (period.index >= endIndex) break
                    // 已經記下（或選了這個月沒有）的月份，餘額與期數已經反映，跳過。
                    if (DueItems.loanKey(loan.id, YearMonth.from(due)) !in snapshot.recordedKeys) {
                        events += loanEvents(loan.id, loan.name, terms.payAccountId, period, schedule[next], EventSource.LOAN_SCHEDULE)
                        next++
                    }
                    due = nextDue(due, terms.payDay)
                }
            }
    }

    fun loanEvents(
        loanAccountId: Long,
        loanName: String,
        payAccountId: Long,
        period: Period,
        installment: Installment,
        source: EventSource,
    ): List<FlowEvent> = buildList {
        if (installment.principal > 0) {
            add(
                FlowEvent(
                    period = period, kind = EventKind.TRANSFER, amount = installment.principal,
                    label = "$loanName 本金", fromAccountId = payAccountId, toAccountId = loanAccountId,
                    relatedAccountId = loanAccountId, source = source,
                ),
            )
        }
        if (installment.interest > 0) {
            add(
                FlowEvent(
                    period = period, kind = EventKind.EXPENSE, amount = installment.interest,
                    label = "$loanName 利息", fromAccountId = payAccountId,
                    relatedAccountId = loanAccountId, source = source,
                ),
            )
        }
    }
}
