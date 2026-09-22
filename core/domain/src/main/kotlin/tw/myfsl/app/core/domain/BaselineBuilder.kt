package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.InstallmentPeriod
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.TrackingMode
import java.time.YearMonth

data class ItemActualView(
    val amount: Money,
    val status: ActualStatus?,
    /** 本月是否已經有記帳，或做過到期確認。 */
    val reported: Boolean,
)

object ActualCalculator {

    /**
     * 項目某月至今的實際金額 = 當月這個項目記帳的合計（含對帳差額、到期確認、到期記下）。
     *
     * **不分支付方式**（R-MIX-01）：怎麼付的都算在同一筆預算裡，所以計畫編現金、實際刷卡付
     * 也算進這個項目，不會變成「計畫外支出」。進度條、記帳提示、這個月還剩都用這個數字。
     *
     * 要知道錢**什麼時候**離開帳戶（現金當月扣、刷卡隔月繳）時改用 [actualForMethod]：
     * 那是現金流的問題，要看同一種支付方式的記帳，不能用項目合計去猜。
     *
     * 分期各期入帳的本金不算（消費當月已經算過全額）；延期付款算在原本的月份以外，不在這裡。
     */
    fun actualFor(
        itemId: Long,
        year: Int,
        month: Int,
        actuals: List<ItemActual>,
        ledger: List<LedgerEntry>,
    ): ItemActualView = view(entriesOf(itemId, year, month, ledger), statusOf(itemId, year, month, actuals))

    /**
     * 項目某月、**某一種支付方式**至今的實際金額（R-MIX-01）：現金流預測用。
     * 計畫列是「項目 × 支付方式」，所以現金列看現金的記帳、刷卡列看刷卡的記帳，各算各的。
     */
    fun actualForMethod(
        itemId: Long,
        method: PaymentMethod?,
        year: Int,
        month: Int,
        actuals: List<ItemActual>,
        ledger: List<LedgerEntry>,
    ): ItemActualView =
        view(entriesOf(itemId, year, month, ledger).filter { it.method == method }, statusOf(itemId, year, month, actuals))

    private fun statusOf(itemId: Long, year: Int, month: Int, actuals: List<ItemActual>): ActualStatus? =
        actuals.firstOrNull { it.itemId == itemId && it.year == year && it.month == month }?.status

    private fun entriesOf(itemId: Long, year: Int, month: Int, ledger: List<LedgerEntry>): List<LedgerEntry> =
        ledger.filter {
            it.itemId == itemId && it.countsForBudget &&
                it.postingKey?.startsWith(tw.myfsl.app.core.model.PostingKeys.DEFERRAL) != true &&
                it.budgetMonth.year == year && it.budgetMonth.monthValue == month
        }

    private fun view(entries: List<LedgerEntry>, status: ActualStatus?) = ItemActualView(
        amount = entries.sumOf { it.amount },
        status = status,
        reported = status != null || entries.isNotEmpty(),
    )
}

/**
 * 產生「現況」基準線（R-FC）：從今天所在的月份開始，只放**今天以後**才會發生的事。
 *
 * - 起始餘額為各帳戶目前推算的餘額；每張信用卡各自一個帳戶，未指定卡片的刷卡算在預設卡片。
 * - 一期就是一個月（R-PER-01）：計畫金額整筆放在那個月，不管有沒有填付款日、也不分攤到哪一天。
 *   日期只用在本月到期、提醒與卡片的結帳週期（R-PER-02）。
 * - **支付方式看計畫列自己寫的**（R-MIX-01）：現金與轉帳當月從帳戶扣，刷卡進卡片、等繳卡費才動到現金。
 *   這是使用者編預算時的決定，App 不推估。
 * - 到期項目（R-DUE）：起算日（含）以前到期的視為已在餘額裡；之後到期、已經記下的不再預測；
 *   到期了還沒記下的放在今天所在的月份。
 * - 每月固定的項目本月以前看到期清單；依記帳、回報、確認的項目本月放「計畫 − 已發生」。
 * - 延期款項獨立列出，金額固定。
 */
object BaselineBuilder {

    fun build(snapshot: FinanceSnapshot, periodCount: Int = snapshot.settings.horizonMonths): ForecastInput {
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
                if (!snapshot.countsAsOwnTransfer(item)) continue
                // 計畫列是「項目 × 支付方式」（R-MIX-01）：每一列各自算，現金列看現金的記帳、刷卡列看刷卡的記帳。
                val byMethod = snapshot.plannedByMethod(item.id, ym.year, ym.monthValue)
                if (byMethod.isEmpty()) continue
                if (item.tracking == TrackingMode.AUTO) {
                    // 每月固定：本月以前看到期清單（還沒記下的才算，見 addDueItems）；之後的月份整筆放在那個月。
                    if (!ym.isAfter(currentMonth)) continue
                    byMethod.forEach { (method, amount) ->
                        events.addIfInRange(item, method, Period.of(ym), amount, input, end)
                    }
                    continue
                }
                byMethod.forEach { (method, planned) ->
                    var monthAmount = planned
                    if (ym == currentMonth) {
                        val actual = ActualCalculator
                            .actualForMethod(item.id, method, ym.year, ym.monthValue, snapshot.actuals, snapshot.ledger)
                        monthAmount = when (actual.status) {
                            ActualStatus.DONE, ActualStatus.POSTPONED -> 0
                            else -> (planned - actual.amount).coerceAtLeast(0)
                        }
                    }
                    if (monthAmount <= 0L) return@forEach
                    events.addIfInRange(item, method, Period.of(ym), monthAmount, input, end)
                }
            }
        }

        addDueItems(snapshot, input, end, events)
        addDeferrals(snapshot, input, end, events)
        val beyond = addInstallments(snapshot, input, end, events)
        val open = addCardSchedule(snapshot, input, end, events)
        addLoanSchedules(snapshot, input, end, events)
        return input.copy(events = events, installmentsBeyond = beyond, openStatements = open)
    }

    private fun MutableList<FlowEvent>.addIfInRange(
        item: PlanItem,
        method: PaymentMethod?,
        period: Period,
        amount: Money,
        input: ForecastInput,
        endIndex: Int,
        source: EventSource = EventSource.PLAN,
    ) {
        if (amount <= 0L || period.index < input.start.index || period.index >= endIndex) return
        add(toEvent(item, method, period, amount, input).copy(source = source))
    }

    /** 下一個到期日：本月的 [day] 還沒到就是本月，否則下個月（短月份取月底）。 */
    fun nextDue(today: java.time.LocalDate, day: Int): java.time.LocalDate {
        val ym = YearMonth.from(today)
        val thisMonth = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
        if (thisMonth.isAfter(today)) return thisMonth
        val next = ym.plusMonths(1)
        return next.atDay(day.coerceIn(1, next.lengthOfMonth()))
    }

    /** 本月以前每月固定、還沒記下的到期項目：到期了放今天所在的月份，還沒到期的依到期月份放。 */
    private fun addDueItems(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        DueItems.list(snapshot).filter { it.kind == DueKind.PLAN }.forEach { due ->
            val item = due.item ?: return@forEach
            events.addIfInRange(item, due.method, periodFor(snapshot, due.date, input), due.amount, input, endIndex)
        }
    }

    /** 到期日所在的月份；已經到期（還沒記下）的放在今天所在的月份。 */
    private fun periodFor(snapshot: FinanceSnapshot, date: java.time.LocalDate, input: ForecastInput): Period =
        if (date.isAfter(snapshot.today)) Period.of(date) else input.start

    /** 延期款項（R-DEF）：未付清的整筆放在到期月份，已過期的算在本期。 */
    private fun addDeferrals(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>) {
        val today = snapshot.today
        snapshot.deferrals.filter { !it.settled && it.amount > 0 }.forEach { deferral ->
            val item = snapshot.item(deferral.itemId) ?: return@forEach
            val due = YearMonth.of(deferral.dueYear, deferral.dueMonth)
            // 延期款的支付方式在延期當下就固定了。
            val method = deferral.method
            if (!due.isAfter(YearMonth.from(today))) {
                events.addIfInRange(item, method, input.start, deferral.amount, input, endIndex, EventSource.DEFERRAL)
            } else {
                events.addIfInRange(item, method, Period.of(due), deferral.amount, input, endIndex, EventSource.DEFERRAL)
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
     * 分期：還沒入帳的每期（R-DUE；到期了還沒記下的放今天所在的月份）。本金不再算成支出（刷卡當月已算過預算），手續費算成支出。
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
     * 有繳款條件的卡（R-CARD-20–22），逐卡：
     * - 今天以前到期、還沒記下的利息與繳款：用本月到期算好的金額，放在今天所在的半月。
     * - 目前這一期（最近一次結帳、截止日在今天之後）：帳單還沒繳的部分當成試算開始時的帳單（回傳值）。
     * - 今天以後：結帳日先計上一期沒繳清的利息、再結帳；截止日依預設方式繳（全額繳帳單，自由與最低繳預估金額）。
     */
    private fun addCardSchedule(snapshot: FinanceSnapshot, input: ForecastInput, endIndex: Int, events: MutableList<FlowEvent>): Map<Long, Money> {
        val today = snapshot.today
        val open = mutableMapOf<Long, Money>()
        // 已到期還沒記下的（照建議金額）：放在今天所在的半月。它們已經算進下面「目前這一期」的帳單，不再算成結帳後的繳款。
        val overdue = DueItems.list(snapshot, through = today).filter { it.kind == DueKind.CARD_INTEREST || it.kind == DueKind.CARD_PAYMENT }
        overdue.forEach { due ->
            val entry = due.entries.single()
            events += FlowEvent(
                period = input.start,
                kind = if (due.kind == DueKind.CARD_INTEREST) EventKind.EXPENSE else EventKind.TRANSFER,
                amount = due.amount,
                label = due.title,
                fromAccountId = entry.accountId,
                toAccountId = entry.toAccountId,
                relatedAccountId = due.relatedAccountId,
                countsTowardStatement = false,
                source = EventSource.CARD_SCHEDULE,
            )
        }
        val overdueEntries = overdue.flatMap { it.entries }
        val endDate = Period.fromIndex(endIndex).startDate
        snapshot.activeCards.filter { it.hasCardSchedule }.forEach { card ->
            val terms = card.card!!
            val assumption = CardRules.assumption(snapshot, card)
            val payAccount = terms.payAccountId?.takeIf { id -> input.accounts.any { it.id == id && it.kind.isLiquid } }
                ?: snapshot.methodAccountId(PaymentMethod.TRANSFER)
            CardRules.latestCycle(card, today)?.let { current ->
                if (current.due.isAfter(snapshot.trackingFrom)) {
                    val options = DueItems.paymentOptions(snapshot, card, CardRules.baseBalance(snapshot, card), current, overdueEntries, Long.MAX_VALUE)
                    open[card.id] = options.full
                }
            }
            CardRules.cycles(card, today, endDate).forEach { cycle ->
                val ym = YearMonth.from(cycle.statement)
                if (cycle.statement.isAfter(today) && Period.of(cycle.statement).index < endIndex) {
                    val period = Period.of(cycle.statement)
                    val early = Period.of(cycle.due).index == period.index
                    if (!DueItems.isRecorded(snapshot, DueItems.cardInterestKey(card.id, ym))) {
                        events += FlowEvent(
                            period = period, kind = EventKind.EXPENSE, amount = 0, label = "${card.name} 循環利息",
                            fromAccountId = card.id, relatedAccountId = card.id,
                            interestRatePercent = terms.revolvingRatePercent ?: 0.0, statementEarly = early, source = EventSource.CARD_SCHEDULE,
                        )
                    }
                    events += FlowEvent(
                        period = period, kind = EventKind.EXPENSE, amount = 0, label = "${card.name} 結帳",
                        relatedAccountId = card.id, statementOf = card.id, statementEarly = early, source = EventSource.CARD_SCHEDULE,
                    )
                }
                if (payAccount != null && cycle.due.isAfter(today) && Period.of(cycle.due).index < endIndex &&
                    !DueItems.isRecorded(snapshot, DueItems.cardPaymentKey(card.id, ym))
                ) {
                    val label = "繳 ${card.name}"
                    val base = FlowEvent(
                        period = Period.of(cycle.due), kind = EventKind.TRANSFER, amount = 0, label = label,
                        fromAccountId = payAccount, toAccountId = card.id, relatedAccountId = card.id, source = EventSource.CARD_SCHEDULE,
                    )
                    // 依繳款推估（R-CARD-26）：全額繳帳單；只繳一部分時繳推估的金額，都不超過這一期帳單還沒繳的部分。
                    events += when (assumption.source) {
                        CardRules.PaymentAssumption.Source.LAST_FULL, CardRules.PaymentAssumption.Source.NO_RECORD -> base.copy(payStatement = true)
                        CardRules.PaymentAssumption.Source.LAST_AMOUNT -> base.copy(amount = assumption.amount, capToStatement = true)
                        CardRules.PaymentAssumption.Source.STATEMENT_MINIMUM -> base.copy(
                            amount = snapshot.statementOf(card.id, ym)?.minimumPayment ?: assumption.amount,
                            capToStatement = true,
                        )
                        // 照計畫編的金額（R-CARD-27）：逐期用截止日那個月編的金額，
                        // 和本月到期共用 assumptionFor，不再各自算一次（V37-01）。
                        CardRules.PaymentAssumption.Source.PLANNED ->
                            base.copy(amount = CardRules.assumptionFor(snapshot, card, cycle.due).amount, capToStatement = true)
                    }
                }
            }
        }
        return open
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
                    if (!DueItems.isRecorded(snapshot, DueItems.loanKey(loan.id, YearMonth.from(due)))) {
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
