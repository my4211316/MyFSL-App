package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.TrackingMode

data class GroupTotal(val group: PlanGroup, val monthly: List<Money>) {
    val total: Money get() = monthly.sum()
}

/** 年度計畫表的三層彙總：明細 → 信用卡帳 / 非刷卡帳 → 月現金流。 */
data class PlanSummary(
    val year: Int,
    val income: List<Money>,
    val expense: List<Money>,
    /** 支付方式為信用卡的消費。 */
    val cardSpending: List<Money>,
    /** 支付方式為現金或轉帳的消費。 */
    val nonCardSpending: List<Money>,
    /** 繳卡費（轉帳到信用卡）。 */
    val cardPayments: List<Money>,
    /** 繳貸款、保單借款：計畫中的轉帳，加上依攤還條件自動產生的每月應繳。 */
    val loanPayments: List<Money>,
    /** 以目前卡債與循環利率估算的每月循環利息。 */
    val cardInterest: List<Money>,
    val groups: List<GroupTotal>,
) {
    val totalIncome: Money get() = income.sum()
    val totalExpense: Money get() = expense.sum()
    val totalCardSpending: Money get() = cardSpending.sum()
    val totalNonCardSpending: Money get() = nonCardSpending.sum()
    val totalCardPayments: Money get() = cardPayments.sum()
    val totalLoanPayments: Money get() = loanPayments.sum()
    val totalCardInterest: Money get() = cardInterest.sum()

    /** 每月繳卡費＋貸款。 */
    val debtPayments: List<Money> get() = List(12) { cardPayments[it] + loanPayments[it] }

    /** 月現金流：收入 − 非刷卡支出 − 繳卡費 − 貸款繳款。 */
    val monthlyCashFlow: List<Money> get() = List(12) { income[it] - nonCardSpending[it] - cardPayments[it] - loanPayments[it] }

    /** 卡債全年淨增加（正數 = 刷的、加上利息，比繳的多）。 */
    val cardDebtIncrease: Money get() = totalCardSpending + totalCardInterest - totalCardPayments

    /** 某月卡債預計變化：刷卡 ＋ 循環利息 − 繳卡費。 */
    fun cardDebtChange(month: Int): Money = cardSpending[month - 1] + cardInterest[month - 1] - cardPayments[month - 1]

    /** 年度結構缺口：收入 − 全部消費 − 貸款繳款 − 循環利息。 */
    val structuralGap: Money get() = totalIncome - totalExpense - totalLoanPayments - totalCardInterest
}

enum class Severity { INFO, WARNING, ERROR }

data class PlanIssue(val severity: Severity, val message: String, val itemId: Long? = null)

object PlanSummaryCalculator {

    fun summarize(snapshot: FinanceSnapshot, year: Int): PlanSummary =
        summarize(year, snapshot.groups, snapshot.items, snapshot.planForYear(year), snapshot.accounts)

    fun summarize(
        year: Int,
        groups: List<PlanGroup>,
        items: List<PlanItem>,
        amounts: MonthlyAmounts,
        accounts: List<Account>,
    ): PlanSummary {
        val kinds = accounts.associate { it.id to it.kind }
        val income = LongArray(12)
        val expense = LongArray(12)
        val card = LongArray(12)
        val nonCard = LongArray(12)
        val cardPay = LongArray(12)
        val loanPay = LongArray(12)
        val byGroup = mutableMapOf<Long, LongArray>()

        for (item in items.filter { !it.archived }) {
            val lines = amounts.filterKeys { it.itemId == item.id }
            for ((line, monthly) in lines) {
                for (m in 0 until 12) {
                    val amount = monthly.getOrElse(m) { 0L }
                    if (amount == 0L) continue
                    when (item.type) {
                        FlowType.INCOME -> if (line.method == null) income[m] += amount
                        FlowType.EXPENSE -> if (line.method != null) {
                            expense[m] += amount
                            if (line.method == PaymentMethod.CREDIT_CARD) card[m] += amount else nonCard[m] += amount
                            byGroup.getOrPut(item.groupId) { LongArray(12) }[m] += amount
                        }

                        FlowType.TRANSFER -> if (line.method == null) {
                            when (kinds[item.toAccountId]) {
                                AccountKind.CREDIT_CARD -> cardPay[m] += amount
                                AccountKind.LOAN, AccountKind.POLICY_LOAN -> loanPay[m] += amount
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        // 有循環條件的信用卡：以目前卡債估算每月利息，並在沒有計畫繳卡費時補上自動繳款。
        val cards = accounts.filter { !it.archived && it.kind == AccountKind.CREDIT_CARD }
        val cardTerms = CardRules.pooled(cards)
        val cardDebt = cards.sumOf { it.balance }
        val interest = LongArray(12)
        if (cardTerms != null) {
            val monthly = CardRules.monthlyInterest(cardDebt, cardTerms.revolvingRatePercent)
            for (m in 0 until 12) interest[m] += monthly
            if (cardPay.all { it == 0L }) {
                val payment = CardRules.outlook(cardDebt, cardTerms, monthlySpending = 0).payment
                for (m in 0 until 12) cardPay[m] += payment
            }
        }

        // 依攤還條件自動繳款的貸款：以目前每月應繳估算全年。
        accounts.filter { !it.archived && it.loan != null && it.balance > 0 }.forEach { loan ->
            val terms = loan.loan!!
            val payment = LoanAmortization.firstPayment(loan.balance, terms.annualRatePercent, terms.remainingMonths, terms.method)
            for (m in 0 until 12) loanPay[m] += payment
        }

        return PlanSummary(
            year = year,
            income = income.toList(),
            expense = expense.toList(),
            cardSpending = card.toList(),
            nonCardSpending = nonCard.toList(),
            cardPayments = cardPay.toList(),
            loanPayments = loanPay.toList(),
            cardInterest = interest.toList(),
            groups = groups.sortedBy { it.sortOrder }.mapNotNull { g -> byGroup[g.id]?.let { GroupTotal(g, it.toList()) } },
        )
    }
}

/** 自動檢查計畫表的常見錯誤，取代人工核對。 */
object PlanValidator {

    fun validate(snapshot: FinanceSnapshot, year: Int): List<PlanIssue> =
        validate(
            PlanSummaryCalculator.summarize(snapshot, year),
            snapshot.items,
            snapshot.accounts,
            snapshot.planForYear(year),
            snapshot.settings,
        )

    fun validate(
        summary: PlanSummary,
        items: List<PlanItem>,
        accounts: List<Account>,
        amounts: MonthlyAmounts,
        settings: AppSettings,
    ): List<PlanIssue> {
        val byId = accounts.associateBy { it.id }
        val liquid = accounts.filter { !it.archived && it.kind.isLiquid }
        val issues = mutableListOf<PlanIssue>()
        val usedMethods = mutableSetOf<PaymentMethod>()

        for (item in items.filter { !it.archived }) {
            val lines = amounts.filter { (line, months) -> line.itemId == item.id && months.any { it != 0L } }.keys
            when (item.type) {
                FlowType.INCOME -> {
                    val account = byId[item.accountId]
                    if (account == null || account.archived) {
                        issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定入帳帳戶", item.id)
                    }
                }

                FlowType.TRANSFER -> {
                    val from = byId[item.accountId]
                    val to = byId[item.toAccountId]
                    when {
                        from == null || from.archived -> issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定轉出帳戶", item.id)
                        to == null || to.archived -> issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定轉入帳戶", item.id)
                        from.id == to.id -> issues += PlanIssue(Severity.ERROR, "「${item.name}」轉出與轉入是同一個帳戶", item.id)
                    }
                    val autoCard = to?.card?.let { it.payMode != CardPayMode.FIXED || (it.fixedPayment ?: 0L) > 0L } == true
                    if (autoCard) {
                        issues += PlanIssue(
                            Severity.WARNING,
                            "「${to.name}」已設定循環繳款會自動產生繳卡費，「${item.name}」可能重複計算",
                            item.id,
                        )
                    }
                    if (to?.loan != null) {
                        issues += PlanIssue(
                            Severity.WARNING,
                            "「${to.name}」已設定攤還條件會自動產生繳款，「${item.name}」可能重複計算",
                            item.id,
                        )
                    }
                }

                FlowType.EXPENSE -> {
                    if (lines.isEmpty()) {
                        issues += PlanIssue(Severity.INFO, "「${item.name}」今年沒有任何計畫金額", item.id)
                    }
                    usedMethods += lines.mapNotNull(PlanLine::method)
                    if (item.flexibility == Flexibility.FLEXIBLE && item.tracking == TrackingMode.AUTO) {
                        issues += PlanIssue(
                            Severity.INFO,
                            "「${item.name}」是可調項目，建議改成依記帳，才能控管進度",
                            item.id,
                        )
                    }
                }
            }
        }

        if (PaymentMethod.CASH in usedMethods && resolve(settings.cashAccountId, liquid, AccountKindHint.CASH) == null) {
            issues += PlanIssue(Severity.ERROR, "支付方式「現金」沒有對應的帳戶，請先新增現金帳戶或在設定指定")
        }
        if (PaymentMethod.TRANSFER in usedMethods && resolve(settings.transferAccountId, liquid, AccountKindHint.BANK) == null) {
            issues += PlanIssue(Severity.ERROR, "支付方式「轉帳」沒有對應的帳戶，請先新增銀行帳戶或在設定指定")
        }
        val cards = accounts.filter { !it.archived && it.kind == AccountKind.CREDIT_CARD }
        CardRules.pooled(cards)?.let { terms ->
            val debt = cards.sumOf { it.balance }
            val monthlySpending = Math.round(summary.totalCardSpending / 12.0)
            val payment = Math.round(summary.totalCardPayments / 12.0)
            val outlook = CardRules.outlook(debt, terms, monthlySpending, payment.takeIf { it > 0 })
            CardRules.warning(outlook)?.let { issues += PlanIssue(Severity.WARNING, it) }
        }
        if (summary.cardDebtIncrease > 0) {
            issues += PlanIssue(
                Severity.WARNING,
                "全年刷卡比繳卡費多 ${MoneyFormat.currency(summary.cardDebtIncrease)}，差額會累積成卡債",
            )
        }
        if (summary.totalIncome == 0L && items.any { !it.archived }) {
            issues += PlanIssue(Severity.WARNING, "計畫中沒有任何收入")
        }
        return issues.sortedByDescending { it.severity.ordinal }
    }

    private enum class AccountKindHint { CASH, BANK }

    private fun resolve(settingId: Long?, liquid: List<Account>, hint: AccountKindHint): Long? =
        settingId?.takeIf { id -> liquid.any { it.id == id } }
            ?: liquid.firstOrNull { it.kind == if (hint == AccountKindHint.CASH) AccountKind.CASH else AccountKind.BANK }?.id
            ?: liquid.firstOrNull()?.id
}
