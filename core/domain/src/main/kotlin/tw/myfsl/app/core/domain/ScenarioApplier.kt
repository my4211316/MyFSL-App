package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.ScenarioChange

/** 把情境變動依序套用到基準線輸入上，產生新的模擬輸入。純函式。 */
object ScenarioApplier {

    /** 情境中新增的貸款帳戶使用負數 id，避免與資料庫 id 及替代卡片衝突。 */
    const val SCENARIO_ACCOUNT_BASE = -1000L

    fun apply(base: ForecastInput, changes: List<ScenarioChange>): ForecastInput {
        var events = base.events
        var accounts = base.accounts
        var beyond = base.installmentsBeyond
        val endIndex = base.start.index + base.periodCount

        // 調整、停止、改支付方式只作用在計畫產生的事件；既有分期、延期款與合約繳款是已經發生的義務，
        // 不會因為「之後少花一點」而變少（R-SC-01）。
        fun isPlan(event: FlowEvent) = event.source == EventSource.PLAN

        changes.forEachIndexed { index, change ->
            when (change) {
                is ScenarioChange.AdjustItems -> {
                    val ids = change.itemIds.toSet()
                    val factor = 1 + change.percent / 100.0
                    events = events.map {
                        if (isPlan(it) && it.itemId in ids && it.period.index >= change.fromIndex) {
                            it.copy(amount = Math.round(it.amount * factor).coerceAtLeast(0))
                        } else {
                            it
                        }
                    }
                }

                is ScenarioChange.StopItem -> events = events.filterNot {
                    isPlan(it) && it.itemId == change.itemId && it.period.index >= change.fromIndex
                }

                is ScenarioChange.ChangeMethod -> {
                    val ids = change.itemIds.toSet()
                    val target = base.methodAccounts[change.toMethod]
                    events = events.map {
                        if (isPlan(it) && it.itemId in ids && it.kind == EventKind.EXPENSE && it.method == change.fromMethod &&
                            it.period.index >= change.fromIndex
                        ) {
                            it.copy(method = change.toMethod, fromAccountId = target)
                        } else {
                            it
                        }
                    }
                }

                is ScenarioChange.OneOff -> {
                    val isIncome = change.type == FlowType.INCOME
                    events = events + FlowEvent(
                        period = Period.fromIndex(change.atIndex),
                        kind = if (isIncome) EventKind.INCOME else EventKind.EXPENSE,
                        amount = change.amount,
                        label = change.name,
                        fromAccountId = if (isIncome) null else change.method?.let { base.methodAccounts[it] } ?: base.mapAccount(change.accountId),
                        toAccountId = if (isIncome) base.mapAccount(change.accountId) else null,
                        method = if (isIncome) null else change.method,
                        source = EventSource.SCENARIO,
                    )
                }

                is ScenarioChange.PayOffDebts -> {
                    val ids = change.accountIds.mapNotNull { base.mapAccount(it) }.toSet()
                    var installmentPrincipal = 0L
                    if (change.includeInstallments) {
                        val future = events.filter {
                            it.source == EventSource.INSTALLMENT &&
                                it.period.index >= change.atIndex &&
                                it.fromAccountId in ids
                        }
                        // 試算期間之後才入帳的本金也一次結清。
                        installmentPrincipal = future.filterNot { it.countAsExpense }.sumOf { it.amount } +
                            ids.sumOf { beyond[it] ?: 0L }
                        events = events - future.toSet()
                        beyond = beyond - ids
                    }
                    if (change.stopScheduledPayments) {
                        events = events.filterNot {
                            it.source != EventSource.SCENARIO &&
                                // 分期入帳不是繳款，不在這裡停掉。
                                it.source != EventSource.INSTALLMENT &&
                                // 卡片的合約（計息與繳款）照舊：清償後欠款是 0 就不會有利息與繳款，
                                // 之後還有新刷卡或保留的分期時，新欠款仍依原條件計息（F12）。停的是計畫裡的繳卡費。
                                it.source != EventSource.CARD_SCHEDULE &&
                                it.period.index >= change.atIndex &&
                                (it.kind == EventKind.TRANSFER && it.toAccountId in ids || it.relatedAccountId in ids)
                        }
                    }
                    if (installmentPrincipal > 0) {
                        // 未到期分期還沒進卡債，所以這筆只從流動資金付出去，不再扣卡片餘額；
                        // 消費在刷卡當月已經算過預算，因此也不算成支出。
                        events = events + FlowEvent(
                            period = Period.fromIndex(change.atIndex),
                            kind = EventKind.EXPENSE,
                            amount = installmentPrincipal,
                            label = "結清未到期分期",
                            fromAccountId = base.mapAccount(change.fromAccountId),
                            relatedAccountId = ids.firstOrNull(),
                            countAsExpense = false,
                            source = EventSource.SCENARIO,
                        )
                    }
                    events = events + ids.map { debtId ->
                        FlowEvent(
                            period = Period.fromIndex(change.atIndex),
                            kind = EventKind.TRANSFER,
                            amount = 0,
                            label = "清償 ${accounts.firstOrNull { it.id == debtId }?.name.orEmpty()}".trim(),
                            fromAccountId = base.mapAccount(change.fromAccountId),
                            toAccountId = debtId,
                            relatedAccountId = debtId,
                            payFullBalance = true,
                            source = EventSource.SCENARIO,
                        )
                    }
                }

                is ScenarioChange.AddLoan -> {
                    val loanId = SCENARIO_ACCOUNT_BASE - index
                    accounts = accounts + AccountSeed(loanId, change.name, AccountKind.LOAN, 0)
                    val startPeriod = Period.fromIndex(change.startIndex)
                    val payAccount = base.mapAccount(change.payAccountId) ?: change.payAccountId
                    val disbursement = FlowEvent(
                        period = startPeriod,
                        kind = EventKind.TRANSFER,
                        amount = change.principal,
                        label = "${change.name} 撥款",
                        fromAccountId = loanId,
                        toAccountId = base.mapAccount(change.depositAccountId),
                        relatedAccountId = loanId,
                        source = EventSource.SCENARIO,
                    )
                    val firstPayment = Period(startPeriod.year, startPeriod.month, change.payHalf).plus(2)
                    val payments = LoanAmortization
                        .schedule(change.principal, change.annualRatePercent, change.months, change.method)
                        .flatMapIndexed { i, installment ->
                            BaselineBuilder.loanEvents(
                                loanAccountId = loanId,
                                loanName = change.name,
                                payAccountId = payAccount,
                                period = firstPayment.plus(i * 2),
                                installment = installment,
                                source = EventSource.SCENARIO,
                            )
                        }
                        .filter { it.period.index < endIndex }
                    events = events + disbursement + payments
                }
            }
        }
        return base.copy(accounts = accounts, events = events, installmentsBeyond = beyond)
    }

    fun run(base: ForecastInput, changes: List<ScenarioChange>): ForecastResult =
        CashFlowEngine.run(apply(base, changes))
}
