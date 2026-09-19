package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import java.time.LocalDate
import java.time.YearMonth

/**
 * 信用卡的繳款與循環信用規則（R-CARD-20–24）。
 *
 * - 每一期：結帳日結算出帳單，繳款截止日繳款。
 * - 全額：繳帳單金額；截止日前繳清就不計息。
 * - 自由、最低：繳使用者輸入的金額；沒繳清的帳單在下一個結帳日計循環利息（R-CARD-22）。
 * - 最低應繳由使用者照帳單輸入，App 不計算。
 */
object CardRules {

    /** 循環利息（每期計一次，四捨五入到元）：未繳清的帳單 × 年利率 ÷ 12。 */
    fun monthlyInterest(unpaid: Money, annualRatePercent: Double?): Money =
        if (unpaid <= 0 || annualRatePercent == null || annualRatePercent <= 0) 0 else Math.round(unpaid * annualRatePercent / 100.0 / 12.0)

    /** 某一期：結帳日與繳款截止日。截止日是結帳日之後第一個 [Account.paymentDueDay]。 */
    data class Cycle(val statement: LocalDate, val due: LocalDate) {
        /** 這一期的年月（結帳日所在的月份），用在識別碼。 */
        val yearMonth: YearMonth get() = YearMonth.from(statement)
    }

    fun cycle(ym: YearMonth, statementDay: Int, dueDay: Int): Cycle {
        val statement = day(ym, statementDay)
        val sameMonth = day(ym, dueDay)
        val due = if (sameMonth.isAfter(statement)) sameMonth else day(ym.plusMonths(1), dueDay)
        return Cycle(statement, due)
    }

    /** 有繳款條件、結帳日與截止日都有填的卡才依期別繳款；回傳（結帳日, 截止日）。 */
    fun cycleDays(card: Account): Pair<Int, Int>? {
        if (card.kind != AccountKind.CREDIT_CARD || card.card == null) return null
        val statement = card.statementDay ?: return null
        val due = card.paymentDueDay ?: return null
        return statement to due
    }

    /** 從 [from] 前一個月起的連續期別，到結帳日不晚於 [through] 的那一期為止。 */
    fun cycles(card: Account, from: LocalDate, through: LocalDate): List<Cycle> {
        val (s, d) = cycleDays(card) ?: return emptyList()
        return generateSequence(YearMonth.from(from).minusMonths(1)) { it.plusMonths(1) }
            .map { cycle(it, s, d) }
            .takeWhile { !it.statement.isAfter(through) }
            .toList()
    }

    /** [date] 以前（含）最近一次結帳的那一期；沒有條件時為 null。 */
    fun latestCycle(card: Account, date: LocalDate): Cycle? {
        val (s, d) = cycleDays(card) ?: return null
        val thisMonth = cycle(YearMonth.from(date), s, d)
        return if (thisMonth.statement.isAfter(date)) cycle(YearMonth.from(date).minusMonths(1), s, d) else thisMonth
    }

    /** 某一期的前一期。 */
    fun previous(card: Account, cycle: Cycle): Cycle? {
        val (s, d) = cycleDays(card) ?: return null
        return cycle(cycle.yearMonth.minusMonths(1), s, d)
    }

    /**
     * 結帳日當天（含）的欠款 = 目前欠款 − 結帳日之後的記帳對這張卡的影響。
     * [extra] 是還沒寫入、但要當成已經記下的記帳（本月到期依序模擬用）；[baseBalance] 是目前欠款（見 [baseBalance]）。
     */
    fun balanceAt(card: Account, baseBalance: Money, ledger: List<LedgerEntry>, extra: List<LedgerEntry>, date: LocalDate): Money {
        val current = baseBalance + extra.sumOf { BalanceRules.effect(it, card.id, card.kind) }
        return current - (ledger + extra).filter { it.date.isAfter(date) }.sumOf { BalanceRules.effect(it, card.id, card.kind) }
    }

    /** 在 (after, through] 之間繳進這張卡的金額。 */
    fun paidBetween(card: Account, ledger: List<LedgerEntry>, extra: List<LedgerEntry>, after: LocalDate, through: LocalDate): Money =
        (ledger + extra)
            .filter { it.type == FlowType.TRANSFER && it.toAccountId == card.id && it.date.isAfter(after) && !it.date.isAfter(through) }
            .sumOf { it.amount }

    /** 某一期三種繳款方式各自的建議金額（R-CARD-20）。 */
    data class PaymentOptions(val full: Money, val free: Money, val minimum: Money) {
        fun of(mode: CardPayMode): Money = when (mode) {
            CardPayMode.FULL -> full
            CardPayMode.FREE -> free
            CardPayMode.MINIMUM -> minimum
        }
    }

    /**
     * 全額 = 帳單還沒繳的部分；自由 = 預估每月繳款；最低 = 帳單上的最低應繳（沒輸入時用預估）。
     * 都不超過帳單還沒繳的部分 [unpaid]，也不超過目前欠款 [debt]。
     */
    fun options(terms: CardTerms, unpaid: Money, debt: Money, statementMinimum: Money?): PaymentOptions {
        val cap = minOf(unpaid, debt).coerceAtLeast(0)
        val estimate = terms.estimatedPayment ?: 0L
        return PaymentOptions(
            full = cap,
            free = minOf(estimate, cap),
            minimum = minOf(statementMinimum ?: estimate, cap),
        )
    }

    /** 帳戶頁與計畫檢查用的每月概況：照預設繳款方式，把目前欠款當成一整期帳單估計。 */
    data class CardOutlook(
        val balance: Money,
        val interest: Money,
        val payment: Money,
        /** 當月預計新刷金額。 */
        val spending: Money,
    ) {
        /** 本月卡債變化：新刷 ＋ 利息 − 繳款。正數代表卡債變多。 */
        val change: Money get() = spending + interest - payment
        val growing: Boolean get() = change > 0

        /** 每月還要多繳多少，卡債才**不再增加**（多繳這個數字是持平；要下降須再多一些）。 */
        val extraToStop: Money get() = if (change > 0) change else 0
    }

    /**
     * 全額：每期繳清，不計息（新刷的下一期繳清，卡債不會累積）。
     * 自由、最低：繳預估金額（或 [payment]），沒繳清的部分計息。
     */
    fun outlook(balance: Money, terms: CardTerms, monthlySpending: Money, payment: Money? = null): CardOutlook {
        val debt = balance.coerceAtLeast(0)
        return when (terms.payMode) {
            CardPayMode.FULL -> CardOutlook(debt, 0, payment ?: (debt + monthlySpending), monthlySpending)
            CardPayMode.FREE, CardPayMode.MINIMUM -> {
                val paid = minOf(payment ?: terms.estimatedPayment ?: 0L, debt)
                CardOutlook(debt, monthlyInterest(debt - paid, terms.revolvingRatePercent), paid, monthlySpending)
            }
        }
    }

    /** 卡債會變多時的提醒文字；會下降時為 null。 */
    fun warning(outlook: CardOutlook): String? {
        if (!outlook.growing) return null
        val extra = MoneyFormat.currency(outlook.extraToStop)
        return if (outlook.payment <= outlook.interest) {
            "繳的錢還不夠付循環利息 ${MoneyFormat.currency(outlook.interest)}，卡債只會變多；每月至少要多繳 $extra 卡債才不會再增加"
        } else {
            "每月刷 ${MoneyFormat.currency(outlook.spending)}、利息 ${MoneyFormat.currency(outlook.interest)}，" +
                "繳 ${MoneyFormat.currency(outlook.payment)} 不夠；每月至少要多繳 $extra 卡債才不會再增加"
        }
    }

    /** 照預設繳款方式、不再刷卡時，大約幾個月能還完；永遠還不完時為 null（R-CARD-06）。 */
    fun monthsToClear(balance: Money, terms: CardTerms, monthlySpending: Money = 0, maxMonths: Int = 600): Int? {
        var remaining = balance
        if (remaining <= 0) return 0
        for (month in 1..maxMonths) {
            val o = outlook(remaining, terms, monthlySpending)
            val next = remaining + o.interest + monthlySpending - o.payment
            if (next >= remaining && next > 0) return null
            remaining = next.coerceAtLeast(0)
            if (remaining == 0L) return month
        }
        return null
    }

    /**
     * 要留給卡費的現金（R-CARD-25）：已經刷了、之後一定要用現金繳掉的部分，不是真的可以自由花的錢。
     * - 全額或沒有設定依帳單繳款的卡：整筆欠款（含未指定卡片的刷卡），下一兩期帳單都要繳清。
     * - 自由、最低的卡：只算本期要繳的（照預設方式，沒有本期帳單時用預估每月繳款）；其餘是長期卡債，不算在這裡。
     */
    fun reserve(snapshot: FinanceSnapshot): Money = snapshot.activeCards.sumOf { card ->
        val debt = baseBalance(snapshot, card).coerceAtLeast(0)
        val terms = card.card
        if (terms == null || terms.payMode == CardPayMode.FULL || !card.hasCardSchedule) return@sumOf debt
        val cycle = latestCycle(card, snapshot.today)
        val bill = cycle?.takeIf { !DueItems.isRecorded(snapshot, DueItems.cardPaymentKey(card.id, it.yearMonth)) }?.let {
            DueItems.paymentOptions(snapshot, card, baseBalance(snapshot, card), it, emptyList(), debt).of(terms.payMode)
        }
        minOf(bill ?: terms.estimatedPayment ?: 0L, debt)
    } + if (snapshot.defaultCardId == null) snapshot.unassignedCardSpending.coerceAtLeast(0) else 0L

    /** 某張卡的起始欠款（和試算相同：未指定卡片的刷卡算在預設卡片上）。 */
    fun baseBalance(snapshot: FinanceSnapshot, card: Account): Money =
        card.balance + if (card.id == snapshot.defaultCardId) snapshot.unassignedCardSpending else 0L

    private fun day(ym: YearMonth, d: Int): LocalDate = ym.atDay(d.coerceIn(1, ym.lengthOfMonth()))
}
