package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RecordMark
import tw.myfsl.app.core.model.isAfter
import java.time.LocalDate

/** 記帳對帳戶餘額的影響。 */
object BalanceRules {

    /**
     * 一筆記帳對某帳戶的影響；負債帳戶的餘額是欠款，所以方向相反。
     * 分期消費本身不直接變成卡債，卡債由各期入帳（[LedgerEntry.isInstallmentPrincipal]）累加。
     */
    fun effect(entry: LedgerEntry, accountId: Long, kind: AccountKind): Money {
        if (entry.isInstallmentPurchase && kind == AccountKind.CREDIT_CARD) return 0
        val sign = if (kind.isLiability) -1L else 1L
        var effect = 0L
        when (entry.type) {
            FlowType.INCOME -> if (entry.accountId == accountId) effect += entry.amount * sign
            FlowType.EXPENSE -> if (entry.accountId == accountId) effect -= entry.amount * sign
            FlowType.TRANSFER -> {
                if (entry.accountId == accountId) effect -= entry.amount * sign
                if (entry.toAccountId == accountId) effect += entry.amount * sign
            }
        }
        return effect
    }

    /**
     * 未指定卡片的刷卡：晚於最近一次「全部卡片一起對帳」的記帳；從沒一起對帳過時全部計入。
     * 只校正其中一張卡時不會吸收這些刷卡，因為不知道它們是不是刷那張卡。
     * 分期消費不算（由各期入帳到預設卡片）。
     */
    fun unassignedCardSpending(ledger: List<LedgerEntry>, fullCardReconcile: RecordMark?): Money =
        ledger.filter { isUnassignedCardSpending(it, fullCardReconcile) }.sumOf { it.amount }

    /** 這筆記帳是不是還沒被「全部卡片一起對帳」吸收的未指定卡片刷卡（算在預設卡片上）。 */
    fun isUnassignedCardSpending(entry: LedgerEntry, fullCardReconcile: RecordMark?): Boolean =
        entry.type == FlowType.EXPENSE &&
            entry.method == PaymentMethod.CREDIT_CARD &&
            entry.accountId == null &&
            !entry.isInstallmentPurchase &&
            (fullCardReconcile == null || entry.isAfter(fullCardReconcile))

    /**
     * 最近一次「全部卡片一起對帳」的時點：所有未封存卡片都有同一個寫入時間的校正。
     * [cardMarks] 為每張卡的所有校正（日期＋寫入時間）。
     */
    fun fullCardReconcile(cardMarks: Map<Long, List<RecordMark>>): RecordMark? {
        if (cardMarks.isEmpty()) return null
        val common = cardMarks.values.map { marks -> marks.map { it.recordedAt }.toSet() }.reduce { a, b -> a intersect b }
        val at = common.filter { it > 0 }.maxOrNull() ?: return null
        return cardMarks.values.first().first { it.recordedAt == at }
    }
}

data class CardView(
    val account: Account,
    val utilizationPercent: Int?,
    val available: Money?,
    /** 本月指定這張卡的刷卡。 */
    val monthSpending: Money,
    /** 本月預計繳這張卡的金額：依帳單繳款的卡照預設繳款方式（加額外還款），沒有則依計畫轉帳。 */
    val fixedPayment: Money,
    /** 下一期預估的循環利息（自由、最低沒繳清的部分）；全額或沒有條件時為 0。 */
    val interest: Money = 0,
    /** 帳單上的最低應繳（帳單校正時輸入的，R-CARD-23）；沒有為 null。 */
    val minimumPayment: Money? = null,
    /** 目前這一期帳單還沒繳的部分；不是依帳單繳款的卡為 null。 */
    val currentBill: Money? = null,
    /** 目前這一期的結帳日與截止日；不是依帳單繳款的卡為 null。 */
    val cycle: CardRules.Cycle? = null,
    /** 目前這一期已經輸入帳單（帳單校正）。 */
    val billEntered: Boolean = false,
    /** 試算與建議金額用的繳款推估（R-CARD-26）；不是依帳單繳款的卡為 null。 */
    val assumption: CardRules.PaymentAssumption? = null,
    /** 未入帳的分期本金：已經佔用額度，但還沒變成要繳的卡債。 */
    val pendingInstallmentPrincipal: Money = 0,
    /** 未結清的分期筆數。 */
    val installmentCount: Int = 0,
    /** 下一期分期入帳金額。 */
    val nextInstallmentAmount: Money = 0,
) {
    /** 已佔用額度 = 已入帳卡款 ＋ 未入帳的分期本金。 */
    val usedCredit: Money get() = account.balance + pendingInstallmentPrincipal

    /** 可用額度 = 額度 − 已佔用額度（R-ACV-01，與 R-CARD-09 相同）。 */
    val availableWithInstallments: Money? get() = account.creditLimit?.let { (it - usedCredit).coerceAtLeast(0) }

    /** 這張卡的總負債 = 已入帳卡款 ＋ 未入帳分期本金。 */
    val totalDebt: Money get() = account.balance + pendingInstallmentPrincipal
}

data class LoanView(
    val account: Account,
    val monthlyPayment: Money,
    val repaidPercent: Int?,
)

data class AccountsOverview(
    val liquidAccounts: List<Account>,
    val cards: List<CardView>,
    val unassignedCardSpending: Money,
    /** 未指定卡片的分期未入帳本金。 */
    val unassignedInstallmentPrincipal: Money = 0,
    val loans: List<LoanView>,
    /** 要留給卡費的現金（R-CARD-25）。 */
    val cardReserve: Money = 0,
) {
    val liquid: Money get() = liquidAccounts.sumOf { it.balance }

    /** 扣掉要留給卡費的，真正可以用的現金（R-CARD-25）。 */
    val freeCash: Money get() = liquid - cardReserve

    /** 已入帳卡款：各卡欠款＋未指定卡片的刷卡。 */
    val cardDebt: Money get() = cards.sumOf { it.account.balance } + unassignedCardSpending
    val loanDebt: Money get() = loans.filter { it.account.kind == AccountKind.LOAN }.sumOf { it.account.balance }
    val policyLoanDebt: Money get() = loans.filter { it.account.kind == AccountKind.POLICY_LOAN }.sumOf { it.account.balance }

    /** 信用卡總負債 = 已入帳卡款 ＋ 未入帳分期本金（已經消費、之後每期一定會入帳）。 */
    val cardTotalDebt: Money get() = cardDebt + pendingInstallmentPrincipal

    /** 負債合計 = 信用卡總負債 ＋ 貸款 ＋ 保單借款。 */
    val totalDebt: Money get() = cardTotalDebt + loanDebt + policyLoanDebt

    /** 各卡當期循環利息合計。 */
    val cardInterest: Money get() = cards.sumOf { it.interest }

    /** 未入帳的分期本金合計（含未指定卡片）。 */
    val pendingInstallmentPrincipal: Money get() = cards.sumOf { it.pendingInstallmentPrincipal } + unassignedInstallmentPrincipal
}

object AccountSummaryCalculator {

    fun overview(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): AccountsOverview {
        val active = snapshot.activeAccounts
        val year = date.year
        val month = date.monthValue
        val transfers = snapshot.activeItems.filter { it.type == FlowType.TRANSFER }

        // 未入帳的分期：起算日之後、還沒在記帳畫面記下的期數（R-DUE）。
        val defaultCard = snapshot.defaultCardId
        val cards = active.filter { it.kind == AccountKind.CREDIT_CARD }.map { card ->
            // 未指定卡片的分期由預設卡片入帳，所以也算在預設卡片上。
            val own = snapshot.installments.filter { (it.cardAccountId ?: defaultCard) == card.id }
            val installments = InstallmentRules.summary(snapshot, own)
            val pending = installments.pendingPrincipal
            val cycle = if (card.hasCardSchedule) CardRules.latestCycle(card, snapshot.today) else null
            val assumption = if (card.hasCardSchedule) CardRules.assumption(snapshot, card) else null
            val bill = cycle?.let {
                DueItems.paymentOptions(snapshot, card, CardRules.baseBalance(snapshot, card), it, emptyList(), card.balance)
                    .takeIf { _ -> !DueItems.isRecorded(snapshot, DueItems.cardPaymentKey(card.id, it.yearMonth)) }
            }
            CardView(
                account = card,
                utilizationPercent = card.creditLimit?.takeIf { it > 0 }?.let { limit ->
                    displayPercent(((card.balance + pending).toDouble() / limit).coerceIn(0.0, 1.0))
                },
                available = card.creditLimit?.let { (it - card.balance - pending).coerceAtLeast(0) },
                // 本月已刷：這張卡的消費（含分期消費、刷卡付的每月固定帳單），不含利息與分期各期入帳。
                monthSpending = snapshot.ledger.filter {
                    it.type == FlowType.EXPENSE && it.accountId == card.id && !it.isCardCharge &&
                        it.date.year == year && it.date.monthValue == month
                }.sumOf { it.amount },
                // 依帳單繳款：本期帳單照預設繳款方式＋標成額外還款的計畫轉帳；沒有：計畫中繳這張卡的轉帳（R-PAY-01）。
                fixedPayment = (bill?.let { CardRules.suggested(assumption!!, it) } ?: 0L) +
                    transfers.filter { it.toAccountId == card.id && (!card.hasCardSchedule || it.extraRepayment) }
                        .sumOf { snapshot.planAmount(PlanLine(it.id, null), year, month) },
                interest = if (assumption != null) CardRules.outlook(card.balance, card.card!!, assumption, monthlySpending = 0).interest else 0,
                minimumPayment = cycle?.let { snapshot.statementOf(card.id, it.yearMonth)?.minimumPayment },
                currentBill = bill?.full,
                cycle = cycle,
                billEntered = cycle?.let { snapshot.statementOf(card.id, it.yearMonth) } != null,
                assumption = assumption,
                pendingInstallmentPrincipal = pending,
                installmentCount = installments.count,
                nextInstallmentAmount = installments.nextAmount,
            )
        }

        val loans = active.filter { it.kind == AccountKind.LOAN || it.kind == AccountKind.POLICY_LOAN }.map { loan ->
            val terms = loan.loan
            LoanView(
                account = loan,
                monthlyPayment = if (terms != null) {
                    LoanAmortization.firstPayment(loan.balance, terms.annualRatePercent, terms.remainingMonths, terms.method)
                } else {
                    transfers.filter { it.toAccountId == loan.id }.sumOf { snapshot.planAmount(PlanLine(it.id, null), year, month) }
                },
                repaidPercent = loan.loanRepaidRatio?.let(::displayPercent),
            )
        }

        return AccountsOverview(
            cardReserve = CardRules.reserve(snapshot),
            liquidAccounts = active.filter { it.kind.isLiquid },
            cards = cards,
            unassignedCardSpending = snapshot.unassignedCardSpending,
            // 沒有任何卡片時，未指定卡片的分期無處入帳，單獨列出。
            unassignedInstallmentPrincipal = if (defaultCard != null) {
                0
            } else {
                InstallmentRules.summary(snapshot, snapshot.installments.filter { it.cardAccountId == null }).pendingPrincipal
            },
            loans = loans,
        )
    }
}
