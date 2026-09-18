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

    /** 一筆記帳對某帳戶的影響；負債帳戶的餘額是欠款，所以方向相反。 */
    fun effect(entry: LedgerEntry, accountId: Long, kind: AccountKind): Money {
        // 分期消費不會一次全額變成卡債，改由各期入帳計算。
        if (entry.installmentId != null && kind == AccountKind.CREDIT_CARD) return 0
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

    /** 未指定卡片的刷卡：晚於所有信用卡中最近一次校正的記帳；沒有任何校正時全部計入。 */
    fun unassignedCardSpending(ledger: List<LedgerEntry>, latestCardSnapshot: RecordMark?): Money =
        ledger.filter {
            it.type == FlowType.EXPENSE &&
                it.method == PaymentMethod.CREDIT_CARD &&
                it.accountId == null &&
                (latestCardSnapshot == null || it.isAfter(latestCardSnapshot))
        }.sumOf { it.amount }
}

data class CardView(
    val account: Account,
    val utilizationPercent: Int?,
    val available: Money?,
    /** 本月指定這張卡的刷卡。 */
    val monthSpending: Money,
    /** 本月計畫中繳這張卡的金額。 */
    val fixedPayment: Money,
    /** 當期循環利息；沒有設定循環條件時為 0。 */
    val interest: Money = 0,
    /** 最低應繳；沒有設定循環條件時為 null。 */
    val minimumPayment: Money? = null,
    /** 未入帳的分期本金：已經佔用額度，但還沒變成要繳的卡債。 */
    val pendingInstallmentPrincipal: Money = 0,
    /** 未結清的分期筆數。 */
    val installmentCount: Int = 0,
    /** 下一期分期入帳金額。 */
    val nextInstallmentAmount: Money = 0,
) {
    /** 已佔用額度 = 目前欠款 ＋ 未入帳的分期本金。 */
    val usedCredit: Money get() = account.balance + pendingInstallmentPrincipal

    /** 依已佔用額度算的可用額度。 */
    val availableWithInstallments: Money? get() = account.creditLimit?.let { (it - usedCredit).coerceAtLeast(0) }
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
) {
    val liquid: Money get() = liquidAccounts.sumOf { it.balance }
    val cardDebt: Money get() = cards.sumOf { it.account.balance } + unassignedCardSpending
    val loanDebt: Money get() = loans.filter { it.account.kind == AccountKind.LOAN }.sumOf { it.account.balance }
    val policyLoanDebt: Money get() = loans.filter { it.account.kind == AccountKind.POLICY_LOAN }.sumOf { it.account.balance }
    val totalDebt: Money get() = cardDebt + loanDebt + policyLoanDebt

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

        val period = Period.of(date)
        val cards = active.filter { it.kind == AccountKind.CREDIT_CARD }.map { card ->
            val installments = InstallmentRules.summary(snapshot.installments, period, card.id)
            CardView(
                account = card,
                utilizationPercent = card.utilization?.let(::displayPercent),
                available = card.availableCredit,
                monthSpending = snapshot.ledger.filter {
                    it.type == FlowType.EXPENSE && it.accountId == card.id && it.date.year == year && it.date.monthValue == month
                }.sumOf { it.amount },
                fixedPayment = transfers.filter { it.toAccountId == card.id }
                    .sumOf { snapshot.planAmount(PlanLine(it.id, null), year, month) },
                interest = card.card?.let { CardRules.monthlyInterest(CardRules.interestBase(card.balance, it), it.revolvingRatePercent) } ?: 0,
                minimumPayment = card.card?.let { CardRules.minimumPayment(card.balance, it) },
                pendingInstallmentPrincipal = installments.pendingPrincipal,
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
            liquidAccounts = active.filter { it.kind.isLiquid },
            cards = cards,
            unassignedCardSpending = snapshot.unassignedCardSpending,
            unassignedInstallmentPrincipal = InstallmentRules.summary(
                snapshot.installments.filter { it.cardAccountId == null },
                period,
            ).pendingPrincipal,
            loans = loans,
        )
    }
}
