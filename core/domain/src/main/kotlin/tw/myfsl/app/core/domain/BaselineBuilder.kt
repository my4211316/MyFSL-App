package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Half
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

    /** 計畫列某月至今的實際金額 = 當月該列所有記帳的合計（含本週檢查產生的差額與補記）。 */
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
            it.itemId == line.itemId && it.method == line.method &&
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
 * 產生「現況」基準線：從今天所在的半月開始，未來用計畫、當月扣掉已發生的實際數字。
 * 起始餘額為各帳戶目前推算的餘額；所有信用卡合併成一個信用卡合計。
 */
object BaselineBuilder {

    fun build(snapshot: FinanceSnapshot, periodCount: Int = snapshot.settings.horizonMonths * 2): ForecastInput {
        val start = Period.of(snapshot.today)
        val end = start.index + periodCount
        val active = snapshot.activeAccounts
        val cards = active.filter { it.kind == AccountKind.CREDIT_CARD }
        val cardIds = cards.map { it.id }.toSet()

        val seeds = active.filter { it.kind != AccountKind.CREDIT_CARD }
            .map { AccountSeed(it.id, it.name, it.kind, it.balance) } +
            AccountSeed(
                CashFlowEngine.CARD_POOL_ID,
                "信用卡",
                AccountKind.CREDIT_CARD,
                cards.sumOf { it.balance } + snapshot.unassignedCardSpending,
            )

        val methodAccounts = buildMap {
            PaymentMethod.entries.forEach { method ->
                val id = if (method == PaymentMethod.CREDIT_CARD) CashFlowEngine.CARD_POOL_ID else snapshot.methodAccountId(method)
                if (id != null) put(method, id)
            }
        }
        val input = ForecastInput(start, periodCount, seeds, emptyList(), snapshot.settings.safetyLevel, methodAccounts, cardIds)
        val events = ArrayList<FlowEvent>()

        val months = Period.range(start, periodCount).map { it.yearMonth }.distinct()
        val currentMonth = start.yearMonth
        val postponed = mutableMapOf<Pair<PlanLine, YearMonth>, Money>()

        for (ym in months) {
            for (item in snapshot.activeItems) {
                val lines = if (item.type == FlowType.EXPENSE) {
                    snapshot.linesOf(item.id).filter { it.method != null }
                } else {
                    listOf(PlanLine(item.id, null))
                }
                for (line in lines) {
                    var monthAmount = snapshot.planAmount(line, ym.year, ym.monthValue) +
                        (postponed.remove(line to ym) ?: 0L)
                    var firstHalfPassed = false

                    if (ym == currentMonth) {
                        val actual = ActualCalculator.actualFor(line, ym.year, ym.monthValue, snapshot.actuals, snapshot.ledger)
                        when {
                            actual.status == ActualStatus.POSTPONED -> {
                                postponed[line to ym.plusMonths(1)] = monthAmount
                                monthAmount = 0
                            }

                            actual.status == ActualStatus.DONE -> monthAmount = 0
                            item.tracking == TrackingMode.AUTO && !actual.reported -> Unit
                            else -> monthAmount = (monthAmount - actual.amount).coerceAtLeast(0)
                        }
                        firstHalfPassed = start.half == Half.SECOND
                        if (firstHalfPassed && item.tracking != TrackingMode.AUTO && monthAmount > 0) {
                            // 回報型項目：剩下的額度都算在下半月。
                            events.addIfInRange(item, line, Period(ym.year, ym.monthValue, Half.SECOND), monthAmount, input, end)
                            continue
                        }
                    }

                    if (monthAmount <= 0L) continue
                    val (first, second) = item.timing.split(monthAmount)
                    if (!firstHalfPassed) {
                        events.addIfInRange(item, line, Period(ym.year, ym.monthValue, Half.FIRST), first, input, end)
                    }
                    events.addIfInRange(item, line, Period(ym.year, ym.monthValue, Half.SECOND), second, input, end)
                }
            }
        }

        addInstallments(snapshot, input, end, events)
        addCardSchedule(snapshot, input, end, events)
        addLoanSchedules(snapshot, input, end, events)
        return input.copy(events = events)
    }

    private fun MutableList<FlowEvent>.addIfInRange(
        item: PlanItem,
        line: PlanLine,
        period: Period,
        amount: Money,
        input: ForecastInput,
        endIndex: Int,
    ) {
        if (amount <= 0L || period.index < input.start.index || period.index >= endIndex) return
        add(toEvent(item, line.method, period, amount, input))
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
     * 分期：每期入帳一期金額。本金不再算成支出（刷卡當月已算過預算），手續費算成支出。
     */
    private fun addInstallments(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        val pool = CashFlowEngine.CARD_POOL_ID
        snapshot.installments.filter { !it.settled }.forEach { installment ->
            val card = input.mapAccount(installment.cardAccountId) ?: pool
            InstallmentRules.pendingPeriods(installment, input.start.index)
                .filter { it.periodIndex < endIndex }
                .forEach { period ->
                    val at = Period.fromIndex(period.periodIndex)
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
    }

    /**
     * 信用卡循環利息與繳款：所有設了循環條件的卡合併成信用卡合計處理。
     * 利息視為刷在卡上的支出，繳款則從扣款帳戶轉到信用卡合計。
     */
    private fun addCardSchedule(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        val cards = snapshot.activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD }
        val terms = CardRules.pooled(cards) ?: return
        val pool = CashFlowEngine.CARD_POOL_ID
        val payAccount = input.mapAccount(terms.payAccountId) ?: snapshot.methodAccountId(PaymentMethod.TRANSFER) ?: return
        var period = Period(input.start.year, input.start.month, Period.halfOfDay(terms.payDay))
        if (period < input.start) period = period.plus(2)
        var first = true
        while (period.index < endIndex) {
            events += FlowEvent(
                period = period,
                kind = EventKind.EXPENSE,
                amount = 0,
                label = "信用卡循環利息",
                fromAccountId = pool,
                relatedAccountId = pool,
                interestRatePercent = terms.revolvingRatePercent,
                // 既有卡循只影響第一次計息；之後沒繳清的欠款都會計息。
                interestBase = if (first) terms.revolvingBalance else null,
                source = EventSource.CARD_SCHEDULE,
            )
            first = false
            val payment = when (terms.payMode) {
                CardPayMode.FULL -> FlowEvent(
                    period = period, kind = EventKind.TRANSFER, amount = 0, label = "繳卡費（當期全額）",
                    fromAccountId = payAccount, toAccountId = pool, relatedAccountId = pool,
                    payFullBalance = true, source = EventSource.CARD_SCHEDULE,
                )

                CardPayMode.MINIMUM -> FlowEvent(
                    period = period, kind = EventKind.TRANSFER, amount = 0, label = "繳卡費（最低應繳）",
                    fromAccountId = payAccount, toAccountId = pool, relatedAccountId = pool,
                    minimumPayment = MinimumPaymentRule(terms.minPaymentPercent, terms.minPaymentFloor, terms.revolvingRatePercent),
                    source = EventSource.CARD_SCHEDULE,
                )

                CardPayMode.FIXED -> FlowEvent(
                    period = period, kind = EventKind.TRANSFER, amount = terms.fixedPayment ?: 0, label = "繳卡費",
                    fromAccountId = payAccount, toAccountId = pool, relatedAccountId = pool,
                    source = EventSource.CARD_SCHEDULE,
                )
            }
            if (payment.amount > 0 || payment.payFullBalance || payment.minimumPayment != null) events += payment
            period = period.plus(2)
        }
    }

    private fun addLoanSchedules(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        val start = input.start
        snapshot.activeAccounts
            .filter { it.kind == AccountKind.LOAN || it.kind == AccountKind.POLICY_LOAN }
            .forEach { loan ->
                val terms = loan.loan ?: return@forEach
                if (loan.balance <= 0) return@forEach
                var first = Period(start.year, start.month, Period.halfOfDay(terms.payDay))
                if (first < start) first = first.plus(2)
                LoanAmortization.schedule(loan.balance, terms.annualRatePercent, terms.remainingMonths, terms.method)
                    .forEachIndexed { i, installment ->
                        val period = first.plus(i * 2)
                        if (period.index >= endIndex) return@forEach
                        events += loanEvents(
                            loan.id, loan.name, input.mapAccount(terms.payAccountId) ?: terms.payAccountId,
                            period, installment, EventSource.LOAN_SCHEDULE,
                        )
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
