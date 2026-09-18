package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardTerms
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
    /** 設定時忽略 [amount]，改以目的帳戶當時欠款計算最低應繳。 */
    val minimumPayment: MinimumPaymentRule? = null,
    /** 設定時忽略 [amount]，改以來源帳戶當時欠款計算當月循環利息（年利率）。 */
    val interestRatePercent: Double? = null,
    /** 計息時的上限金額（既有卡循）；null 表示以當時全部欠款計息。 */
    val interestBase: Money? = null,
    /**
     * false 時這筆支出不算進「支出」：用在分期每期入帳的本金，
     * 因為那筆消費在刷卡當月就已經算過預算，這裡只是變成要繳的卡債。
     */
    val countAsExpense: Boolean = true,
    val source: EventSource = EventSource.PLAN,
)

/** 最低應繳的計算方式；與 [CardTerms] 對應。 */
data class MinimumPaymentRule(
    val percent: Double,
    val floor: Money,
    val revolvingRatePercent: Double,
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
    /** 保守估計的期間最低水位：當期支出都在收入之前；新借款撥款視為期初到位。 */
    val liquidLow: Money,
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
    val lowest: PeriodResult? = periods.minByOrNull { it.liquidLow }
    val lowestLiquid: Money = lowest?.liquidLow ?: startLiquid
    val firstBelowSafety: PeriodResult? = periods.firstOrNull { it.liquidLow < input.safetyLevel }
    val firstNegative: PeriodResult? = periods.firstOrNull { it.liquidLow < 0 }
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
     * 試算期間年化缺口：(收入 − 支出 − 貸款本金還款) × 24 ÷ 期數。
     * 支出含一般消費、貸款利息、循環利息、分期手續費；不含分期本金入帳（消費當月已算）、一次清償與新借款。
     * 第一期若只剩半個半月，仍以整期計算（R-FC-10）。
     */
    val structuralGapPerYear: Money =
        if (periods.isEmpty()) 0
        else Math.round((totalIncome - totalExpense - totalPrincipalRepaid).toDouble() * 24 / periods.size)

    /** 第一次有扣款帳戶餘額變成負數的期別與帳戶；總水位夠、個別帳戶不夠時也要提醒。 */
    val firstShortfall: Pair<PeriodResult, Long>? = run {
        val liquidIds = input.accounts.filter { it.kind.isLiquid }.map { it.id }.toSet()
        periods.firstNotNullOfOrNull { p ->
            p.balances.entries.firstOrNull { it.key in liquidIds && it.value < 0 }?.let { p to it.key }
        }
    }
}

/** 以半月為單位逐期模擬所有帳戶餘額。純函式，無副作用。 */
object CashFlowEngine {

    /** 沒有任何信用卡、計畫卻有刷卡時，模擬用的替代卡片。 */
    const val FALLBACK_CARD_ID = -1L

    fun run(input: ForecastInput): ForecastResult {
        val kinds = input.accounts.associate { it.id to it.kind }
        val balances = input.accounts.associate { it.id to it.balance }.toMutableMap()
        val eventsByPeriod = input.events.groupBy { it.period.index }
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

            // 先計息，再處理清償與最低應繳，最後才是一般收支。
            val ordered = eventsByPeriod[period.index].orEmpty().sortedBy { priority(it) }
            for (event in ordered) {
                val amount = when {
                    event.interestRatePercent != null -> {
                        val debt = (event.fromAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)
                        CardRules.monthlyInterest(event.interestBase?.coerceIn(0, debt) ?: debt, event.interestRatePercent)
                    }

                    event.payFullBalance -> (event.toAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)

                    event.minimumPayment != null -> {
                        val debt = (event.toAccountId?.let { balances[it] } ?: 0L).coerceAtLeast(0)
                        CardRules.minimumPayment(
                            debt,
                            CardTerms(
                                revolvingRatePercent = event.minimumPayment.revolvingRatePercent,
                                minPaymentPercent = event.minimumPayment.percent,
                                minPaymentFloor = event.minimumPayment.floor,
                            ),
                        )
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
                liquidLow = liquidStart + borrowing - liquidOut,
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

    /** 同一半月內的處理順序：循環利息 → 全額清償 → 最低應繳 → 其他。 */
    private fun priority(event: FlowEvent): Int = when {
        event.interestRatePercent != null -> 0
        event.payFullBalance -> 1
        event.minimumPayment != null -> 2
        else -> 3
    }

    private inline fun sumOf(
        balances: Map<Long, Money>,
        kinds: Map<Long, AccountKind>,
        predicate: (AccountKind) -> Boolean,
    ): Money = balances.entries.sumOf { (id, value) -> if (kinds[id]?.let(predicate) == true) value else 0L }
}
