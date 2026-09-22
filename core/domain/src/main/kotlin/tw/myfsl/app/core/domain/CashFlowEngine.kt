package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period

enum class EventKind { INCOME, EXPENSE, TRANSFER }

/** 事件來源。情境的「調整／停止項目／改支付方式」只作用在 [PLAN]，不會動到既有分期、延期與合約繳款。 */
enum class EventSource { PLAN, DEFERRAL, LOAN_SCHEDULE, CARD_SCHEDULE, INSTALLMENT, SCENARIO }

/**
 * 模擬中的一筆金流。
 * INCOME 使用 [toAccountId]；EXPENSE 使用 [fromAccountId]；TRANSFER 兩者都要。
 */
data class FlowEvent(
    val period: Period,
    val kind: EventKind,
    val amount: Money,
    val label: String,
    val fromAccountId: Long? = null,
    val toAccountId: Long? = null,
    val itemId: Long? = null,
    val groupId: Long? = null,
    /** 支出的支付方式。 */
    val method: PaymentMethod? = null,
    val flexible: Boolean = false,
    /** 例如貸款利息所屬的貸款帳戶，用來在清償情境中一併停止。 */
    val relatedAccountId: Long? = null,
    /** true 時忽略 [amount]，改以當期目的帳戶的全部欠款清償。 */
    val payFullBalance: Boolean = false,
    /** true 時忽略 [amount]，改繳目的卡片目前這一期帳單還沒繳的部分（全額，R-CARD-20）。 */
    val payStatement: Boolean = false,
    /** true 時 [amount] 最多繳到目的卡片這一期帳單還沒繳的部分（自由、最低，R-CARD-20；不會提早繳還沒出帳的刷卡）。 */
    val capToStatement: Boolean = false,
    /** 設定時忽略 [amount]，改以來源卡片上一期帳單沒繳清的部分計算循環利息（年利率，R-CARD-22）。 */
    val interestRatePercent: Double? = null,
    /** 設定時這是卡片的結帳：記下這張卡當時的欠款成為新的一期帳單（金額為 0，不影響餘額）。 */
    val statementOf: Long? = null,
    /**
     * 結帳（與結帳時的計息）時點：false 在同一個半月的其他收支之後（上一期的繳款先繳）；
     * true 在卡片繳款之前（這一期的截止日也落在同一個半月時）。
     */
    val statementEarly: Boolean = false,
    /** false：這筆繳款已經算在試算開始時的帳單裡（[ForecastInput.openStatements]），不再算成結帳後的繳款。 */
    val countsTowardStatement: Boolean = true,
    /**
     * false 時這筆支出不算進「支出」：用在分期每期入帳的本金，
     * 因為那筆消費在刷卡當月就已經算過預算，這裡只是變成要繳的卡債。
     */
    val countAsExpense: Boolean = true,
    val source: EventSource = EventSource.PLAN,
)

data class AccountSeed(
    val id: Long,
    val name: String,
    val kind: AccountKind,
    val balance: Money,
)

data class ForecastInput(
    val start: Period,
    val periodCount: Int,
    val accounts: List<AccountSeed>,
    val events: List<FlowEvent>,
    val safetyLevel: Money,
    /** 支付方式 → 模擬用的扣款帳戶（信用卡為預設卡片）。 */
    val methodAccounts: Map<PaymentMethod, Long> = emptyMap(),
    /**
     * 試算期間結束後才入帳的分期本金，依卡片分列。
     * 這些已經是消費過的付款義務，期末總負債要算進去（R-FC-10）。
     */
    val installmentsBeyond: Map<Long, Money> = emptyMap(),
    /** 有繳款條件的卡：試算開始時這一期帳單還沒繳的部分（R-CARD-20）。 */
    val openStatements: Map<Long, Money> = emptyMap(),
) {
    /** 帳戶 id 原樣回傳（每張卡各自模擬）。保留給情境套用時統一使用。 */
    fun mapAccount(id: Long?): Long? = id

    val accountName: (Long) -> String get() = { id -> accounts.firstOrNull { it.id == id }?.name ?: "帳戶" }
}

data class ResolvedEvent(val event: FlowEvent, val amount: Money)

data class PeriodResult(
    val period: Period,
    val liquidStart: Money,
    val liquidIn: Money,
    val liquidOut: Money,
    /**
     * 這個月的水位（R-FC-09）：月底的可動用餘額。一期就是一個月（R-PER-01），
     * 月內誰先誰後不猜——沒填付款日的項目本來就沒有「哪一天」。
     */
    val liquidEnd: Money,
    val cardDebtEnd: Money,
    val loanDebtEnd: Money,
    val income: Money,
    val expense: Money,
    val flexibleExpense: Money,
    val cardSpending: Money,
    /** 當期的信用卡循環利息。 */
    val cardInterest: Money,
    /** 當期入帳的分期本金（刷卡當月已算過支出，這裡只是變成卡債）。 */
    val installmentPosted: Money,
    val cardPayments: Money,
    val borrowing: Money,
    val loanPrincipalRepaid: Money,
    val debtPayoff: Money,
    val balances: Map<Long, Money>,
    val events: List<ResolvedEvent>,
)

class ForecastResult(
    val input: ForecastInput,
    val periods: List<PeriodResult>,
) {
    val startLiquid: Money = input.accounts.filter { it.kind.isLiquid }.sumOf { it.balance }
    val lowest: PeriodResult? = periods.minByOrNull { it.liquidEnd }
    val lowestLiquid: Money = lowest?.liquidEnd ?: startLiquid
    val firstBelowSafety: PeriodResult? = periods.firstOrNull { it.liquidEnd < input.safetyLevel }
    val firstNegative: PeriodResult? = periods.firstOrNull { it.liquidEnd < 0 }
    val totalIncome: Money = periods.sumOf { it.income }
    val totalExpense: Money = periods.sumOf { it.expense }
    val totalPrincipalRepaid: Money = periods.sumOf { it.loanPrincipalRepaid }
    val totalCardInterest: Money = periods.sumOf { it.cardInterest }
    val totalInstallmentPosted: Money = periods.sumOf { it.installmentPosted }
    val totalBorrowing: Money = periods.sumOf { it.borrowing }
    val endLiquid: Money = periods.lastOrNull()?.liquidEnd ?: startLiquid
    val endCardDebt: Money = periods.lastOrNull()?.cardDebtEnd
        ?: input.accounts.filter { it.kind == AccountKind.CREDIT_CARD }.sumOf { it.balance }
    val endLoanDebt: Money = periods.lastOrNull()?.loanDebtEnd
        ?: input.accounts.filter { it.kind.isLiability && it.kind != AccountKind.CREDIT_CARD }.sumOf { it.balance }

    /** 期末仍未入帳的分期本金（試算期間之後才入帳）。 */
    val endPendingInstallments: Money = input.installmentsBeyond.values.sum()

    /** 期末總負債 = 卡債 ＋ 未入帳分期本金 ＋ 貸款與保單借款。 */
    val endTotalDebt: Money = endCardDebt + endPendingInstallments + endLoanDebt

    /**
     * 試算期間年化缺口：(收入 − 支出 − 貸款本金還款) × 12 ÷ 期數。一期就是一個月（R-PER-01）。
     * 支出含一般消費、貸款利息、循環利息、分期手續費；不含分期本金入帳（消費當月已算）、一次清償與新借款。
     * 第一期若只剩半個月，仍以整個月計算（R-FC-10）。
     */
    val structuralGapPerYear: Money =
        if (periods.isEmpty()) 0
        else Math.round((totalIncome - totalExpense - totalPrincipalRepaid).toDouble() * 12 / periods.size)

    /** 第一次有扣款帳戶餘額變成負數的期別與帳戶；總水位夠、個別帳戶不夠時也要提醒。 */
    val firstShortfall: Pair<PeriodResult, Long>? = run {
        val liquidIds = input.accounts.filter { it.kind.isLiquid }.map { it.id }.toSet()
        periods.firstNotNullOfOrNull { p ->
            p.balances.entries.firstOrNull { it.key in liquidIds && it.value < 0 }?.let { p to it.key }
        }
    }
}

/** 以月為單位逐期模擬所有帳戶餘額（R-PER-01）。純函式，無副作用。 */
object CashFlowEngine {

    /** 沒有任何信用卡、計畫卻有刷卡時，模擬用的替代卡片。 */
    const val FALLBACK_CARD_ID = -1L

    fun run(input: ForecastInput): ForecastResult {
        val kinds = input.accounts.associate { it.id to it.kind }
        val balances = input.accounts.associate { it.id to it.balance }.toMutableMap()
        val eventsByPeriod = input.events.groupBy { it.period.index }
        // 每張卡目前這一期的帳單，以及結帳之後已經繳的（R-CARD-20、R-CARD-22）。
        val billed = input.openStatements.toMutableMap()
        val paid = mutableMapOf<Long, Money>()
        fun unpaidOf(card: Long?): Money = card?.let { ((billed[it] ?: 0L) - (paid[it] ?: 0L)).coerceAtLeast(0) } ?: 0L
        val results = ArrayList<PeriodResult>(input.periodCount)

        for (period in Period.range(input.start, input.periodCount)) {
            val liquidStart = sumOf(balances, kinds) { it.isLiquid }
            var liquidIn = 0L
            var liquidOut = 0L
            var income = 0L
            var expense = 0L
            var flexibleExpense = 0L
            var cardSpending = 0L
            var cardInterest = 0L
            var installmentPosted = 0L
            var cardPayments = 0L
            var borrowing = 0L
            var principal = 0L
            var payoff = 0L
            val resolved = ArrayList<ResolvedEvent>()

            fun takeFrom(accountId: Long?, amount: Money, countLiquid: Boolean) {
                val id = accountId ?: return
                val kind = kinds[id] ?: return
                if (kind.isLiquid) {
                    balances[id] = balances.getValue(id) - amount
                    if (countLiquid) liquidOut += amount
                } else {
                    balances[id] = balances.getValue(id) + amount
                }
            }

            fun putInto(accountId: Long?, amount: Money, countLiquid: Boolean) {
                val id = accountId ?: return
                val kind = kinds[id] ?: return
                if (kind.isLiquid) {
                    balances[id] = balances.getValue(id) + amount
                    if (countLiquid) liquidIn += amount
                } else {
                    balances[id] = balances.getValue(id) - amount
                }
            }

            // 依 R-ORD-01：分期入帳 → 計息 → 情境清償 → 卡片合約繳款 → 貸款 → 其他收支。
            val ordered = eventsByPeriod[period.index].orEmpty().sortedBy { priority(it) }
            for (event in ordered) {
                val statementCard = event.statementOf
                if (statementCard != null) {
                    // 結帳：這一期的帳單 = 當時的欠款；之後的繳款算這一期的。
                    billed[statementCard] = (balances[statementCard] ?: 0L).coerceAtLeast(0)
                    paid[statementCard] = 0L
                    continue
                }
                val amount = when {
                    event.interestRatePercent != null -> {
                        val debt = (event.fromAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)
                        CardRules.monthlyInterest(minOf(unpaidOf(event.fromAccountId), debt), event.interestRatePercent)
                    }

                    event.payFullBalance -> (event.toAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)

                    event.payStatement -> {
                        val debt = (event.toAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)
                        minOf(unpaidOf(event.toAccountId), debt)
                    }

                    event.capToStatement -> {
                        val debt = (event.toAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)
                        minOf(event.amount, unpaidOf(event.toAccountId), debt)
                    }

                    // 還款最多還到欠款為 0，多的錢留在原帳戶（R-PAY-02）。
                    event.kind == EventKind.TRANSFER && event.toAccountId?.let { kinds[it] }?.isLiability == true ->
                        minOf(event.amount, balances.getValue(event.toAccountId).coerceAtLeast(0))

                    else -> event.amount
                }
                if (amount <= 0L) continue

                when (event.kind) {
                    EventKind.INCOME -> {
                        putInto(event.toAccountId, amount, countLiquid = true)
                        income += amount
                    }

                    EventKind.EXPENSE -> {
                        takeFrom(event.fromAccountId, amount, countLiquid = true)
                        when {
                            event.countAsExpense -> {
                                expense += amount
                                if (event.flexible) flexibleExpense += amount
                            }

                            // 分期各期入帳：只是變成要繳的卡債。
                            event.source == EventSource.INSTALLMENT -> installmentPosted += amount
                            // 其他不算支出的支出＝在還過去消費的債（例如一次結清未到期分期）。
                            else -> payoff += amount
                        }
                        if (event.fromAccountId?.let { kinds[it] } == AccountKind.CREDIT_CARD) {
                            when {
                                event.interestRatePercent != null -> cardInterest += amount
                                !event.countAsExpense -> Unit
                                else -> cardSpending += amount
                            }
                        }
                    }

                    EventKind.TRANSFER -> {
                        val from = event.fromAccountId?.let { kinds[it] }
                        val to = event.toAccountId?.let { kinds[it] }
                        val internal = from?.isLiquid == true && to?.isLiquid == true
                        takeFrom(event.fromAccountId, amount, countLiquid = !internal)
                        putInto(event.toAccountId, amount, countLiquid = !internal)
                        if (to == AccountKind.CREDIT_CARD && event.countsTowardStatement) {
                            event.toAccountId?.let { paid[it] = (paid[it] ?: 0L) + amount }
                        }
                        when {
                            from?.isLiability == true && to?.isLiquid == true -> borrowing += amount
                            from?.isLiquid == true && to == AccountKind.CREDIT_CARD ->
                                if (event.payFullBalance) payoff += amount else cardPayments += amount

                            from?.isLiquid == true && to?.isLiability == true ->
                                if (event.payFullBalance) payoff += amount else principal += amount
                        }
                    }
                }
                resolved += ResolvedEvent(event, amount)
            }

            results += PeriodResult(
                period = period,
                liquidStart = liquidStart,
                liquidIn = liquidIn,
                liquidOut = liquidOut,
                liquidEnd = sumOf(balances, kinds) { it.isLiquid },
                cardDebtEnd = sumOf(balances, kinds) { it == AccountKind.CREDIT_CARD },
                loanDebtEnd = sumOf(balances, kinds) { it.isLiability && it != AccountKind.CREDIT_CARD },
                income = income,
                expense = expense,
                flexibleExpense = flexibleExpense,
                cardSpending = cardSpending,
                cardInterest = cardInterest,
                installmentPosted = installmentPosted,
                cardPayments = cardPayments,
                borrowing = borrowing,
                loanPrincipalRepaid = principal,
                debtPayoff = payoff,
                balances = balances.toMap(),
                events = resolved,
            )
        }
        return ForecastResult(input, results)
    }

    /**
     * 同一個月內的處理順序（R-ORD-01）：分期入帳 → 循環利息 → 情境清償 → 卡片繳款 → 貸款 → 其他收支。
     * 結帳（先計上一期沒繳清的利息，再結算帳單）一般在最後：上一期的繳款先繳，這個月的刷卡算進這一期帳單；
     * 結帳日與截止日之間沒有跨月時（例如 1 日結帳、15 日截止），結帳與計息提前：計息在清償之前、結帳在卡片繳款之前。
     */
    private fun priority(event: FlowEvent): Int = when {
        event.statementOf != null -> if (event.statementEarly) 4 else 9
        event.interestRatePercent != null -> if (event.statementEarly) 2 else 8
        event.source == EventSource.INSTALLMENT -> 0
        event.source == EventSource.CARD_SCHEDULE && event.kind == EventKind.EXPENSE -> 1
        event.payFullBalance && event.source == EventSource.SCENARIO -> 3
        event.source == EventSource.CARD_SCHEDULE -> 5
        event.source == EventSource.LOAN_SCHEDULE -> 6
        else -> 7
    }

    private inline fun sumOf(
        balances: Map<Long, Money>,
        kinds: Map<Long, AccountKind>,
        predicate: (AccountKind) -> Boolean,
    ): Money = balances.entries.sumOf { (id, value) -> if (kinds[id]?.let(predicate) == true) value else 0L }
}
