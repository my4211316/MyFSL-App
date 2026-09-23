package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.CardPaymentPlan
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
     *
     * 預設卡片的起始欠款含未指定卡片的刷卡（[FinanceSnapshot.unassignedCardSpending]），回推時要用同一條規則扣回去：
     * 結帳日之後、還沒被全部卡片對帳吸收的未指定刷卡，最多扣到那個合計（只扣真的加進起始欠款的部分，R-ACT-05）。
     */
    fun balanceAt(snapshot: FinanceSnapshot, card: Account, baseBalance: Money, ledger: List<LedgerEntry>, extra: List<LedgerEntry>, date: LocalDate): Money {
        val current = baseBalance + extra.sumOf { BalanceRules.effect(it, card.id, card.kind) }
        val later = (ledger + extra).filter { it.date.isAfter(date) }.sumOf { BalanceRules.effect(it, card.id, card.kind) }
        val unassignedLater = if (card.id == snapshot.defaultCardId) {
            val after = ledger.filter { it.date.isAfter(date) && BalanceRules.isUnassignedCardSpending(it, snapshot.fullCardReconcile) }.sumOf { it.amount }
            minOf(after, snapshot.unassignedCardSpending.coerceAtLeast(0))
        } else {
            0L
        }
        return current - later - unassignedLater
    }

    /** 在 (after, through] 之間繳進這張卡的金額。 */
    fun paidBetween(card: Account, ledger: List<LedgerEntry>, extra: List<LedgerEntry>, after: LocalDate, through: LocalDate): Money =
        (ledger + extra)
            .filter { it.type == FlowType.TRANSFER && it.toAccountId == card.id && it.date.isAfter(after) && !it.date.isAfter(through) }
            .sumOf { it.amount }

    /**
     * 某一期可以繳的金額（R-CARD-21）：全額 = 帳單還沒繳的部分；最低 = 帳單上的最低應繳（沒輸入帳單時為 null，要使用者照帳單填）。
     * 都不超過帳單還沒繳的部分，也不超過目前欠款。自由由使用者自己填。
     */
    data class PaymentOptions(val full: Money, val minimum: Money?)

    fun options(unpaid: Money, debt: Money, statementMinimum: Money?): PaymentOptions {
        val cap = minOf(unpaid, debt).coerceAtLeast(0)
        return PaymentOptions(full = cap, minimum = statementMinimum?.let { minOf(it, cap).coerceAtLeast(0) })
    }

    /**
     * 試算與建議金額用的繳款推估（R-CARD-26）：使用者不用事先設定，App 依手上的資料推估，依序看
     * 1. 最近一期實際記下的繳款：繳清帳單 → 之後也全額；只繳一部分 → 之後每期繳同樣的金額；
     * 2. 還沒有繳款紀錄、但輸入過帳單：每期繳帳單上的最低應繳；
     * 3. 都沒有：先假設全額繳清（對現金最保守）。有填循環利率又有欠款時提醒輸入最近一期帳單（[needsBill]）。
     * 信用卡不能只繳利息，所以不做「只繳利息」的假設。
     */
    data class PaymentAssumption(val source: Source, val amount: Money = 0, val needsBill: Boolean = false) {
        enum class Source { LAST_FULL, LAST_AMOUNT, STATEMENT_MINIMUM, NO_RECORD, PLANNED }

        val paysFull: Boolean get() = source == Source.LAST_FULL || source == Source.NO_RECORD

        /**
         * 繳款依據的短標籤，貼在推估出來的金額旁邊（R-CARD-26）：
         * 那些金額不是使用者編的，畫面上要看得出來它是怎麼來的。
         */
        val basis: String get() = when (source) {
            Source.LAST_FULL -> "照上一期，假設每期全額繳清"
            Source.LAST_AMOUNT -> "照上一期，假設每期繳 ${MoneyFormat.currency(amount)}"
            Source.STATEMENT_MINIMUM -> "照帳單最低 ${MoneyFormat.currency(amount)}"
            Source.NO_RECORD -> if (needsBill) "還沒有帳單，暫且假設每期全額繳清" else "還沒有繳款紀錄，假設每期全額繳清"
            Source.PLANNED -> "照計畫編的金額"
        }

        /** 給畫面顯示的說明。 */
        fun describe(cardName: String): String = when (source) {
            Source.LAST_FULL -> "$cardName：照上一期，假設每期全額繳清"
            Source.LAST_AMOUNT -> "$cardName：照上一期，假設每期繳 ${MoneyFormat.currency(amount)}，沒繳清的部分計息"
            Source.STATEMENT_MINIMUM -> "$cardName：還沒有繳款紀錄，假設每期繳帳單上的最低應繳 ${MoneyFormat.currency(amount)}"
            Source.NO_RECORD -> if (needsBill) {
                "$cardName：有欠款，請輸入最近一期帳單（含最低應繳），試算才知道每期至少要繳多少；目前先假設全額繳清"
            } else {
                "$cardName：還沒有繳款紀錄，先假設全額繳清"
            }

            Source.PLANNED -> "$cardName：照計畫編的金額繳，這一期 ${MoneyFormat.currency(amount)}，沒繳清的部分計息"
        }
    }

    /**
     * 使用者在年度計畫裡編給這張卡的繳款（R-CARD-27）：計畫中轉入這張卡的項目，那個月的合計。
     * 選「照計畫編的金額」時這就是每期繳款，所以那些轉帳項目不再另外算一次現金流。
     */
    fun plannedPayment(snapshot: FinanceSnapshot, cardId: Long, year: Int, month: Int): Money =
        snapshot.activeItems
            .filter { it.type == FlowType.TRANSFER && it.toAccountId == cardId }
            .sumOf { item -> snapshot.plannedAmount(item.id, year, month) }

    /**
     * **某一期**的繳款推估（R-CARD-27）：選「照計畫編的金額」時，金額看**截止日那個月**編了多少
     * ——錢是那個月從帳戶出去的。其他三種繳法和期別無關，直接回 [assumption]。
     *
     * [assumption] 只知道「目前這一期」，所以凡是要算某一期（本月到期的每一期、跨年逐月滾動）
     * 都必須用這個函式，不能重複用同一個 [PaymentAssumption]（V37-01、V37-02）。
     */
    fun assumptionFor(snapshot: FinanceSnapshot, card: Account, due: LocalDate): PaymentAssumption {
        val base = sourceOf(snapshot, card)
        if (base.source != PaymentAssumption.Source.PLANNED) return base
        val ym = YearMonth.from(due)
        return base.copy(amount = plannedPayment(snapshot, card.id, ym.year, ym.monthValue))
    }

    /**
     * 「**目前這一期**」的繳款推估：就是 [assumptionFor] 套在目前這一期的**截止日**上——
     * 錢是那天出去的，所以「照計畫編的金額」要看截止日那個月編多少。
     *
     * 結帳月和截止月常常不同月（20 日結帳、次月 5 日截止）。以前這裡取結帳月，
     * 於是「要留給卡費」「本月預計繳款」「試算的推估說明」都會用錯一個月（fdee72d 外部複審）。
     */
    fun assumption(snapshot: FinanceSnapshot, card: Account): PaymentAssumption =
        assumptionFor(snapshot, card, latestCycle(card, snapshot.today)?.due ?: snapshot.today)

    /** 推估的**來源**：和期別無關，只看使用者選的繳款計畫與既有紀錄；PLANNED 的金額由呼叫端按期別解析。 */
    private fun sourceOf(snapshot: FinanceSnapshot, card: Account): PaymentAssumption {
        val (s, d) = cycleDays(card) ?: return PaymentAssumption(PaymentAssumption.Source.NO_RECORD)
        // 使用者自己決定怎麼繳時就照他的，不再從紀錄推估（R-CARD-27）。
        when (card.card?.paymentPlan) {
            CardPaymentPlan.FULL -> return PaymentAssumption(PaymentAssumption.Source.LAST_FULL)
            CardPaymentPlan.MINIMUM -> {
                val bill = snapshot.cardStatements
                    .filter { it.cardId == card.id && it.minimumPayment != null }
                    .maxByOrNull { it.yearMonth }
                return if (bill != null) {
                    PaymentAssumption(PaymentAssumption.Source.STATEMENT_MINIMUM, bill.minimumPayment!!)
                } else {
                    // 選了最低卻還沒輸入帳單：不知道最低是多少，先假設全額並提醒。
                    PaymentAssumption(PaymentAssumption.Source.NO_RECORD, needsBill = true)
                }
            }
            // 金額由呼叫端按期別解析（[assumptionFor]）：這裡只決定來源。
            CardPaymentPlan.PLANNED -> return PaymentAssumption(PaymentAssumption.Source.PLANNED)
            CardPaymentPlan.AUTO, null -> Unit
        }
        val prefix = "${tw.myfsl.app.core.model.PostingKeys.CARD_PAYMENT}${card.id}:"
        val lastYm = snapshot.ledger.mapNotNull { e ->
            e.postingKey?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        }.maxOrNull()
        if (lastYm != null) {
            val cycle = cycle(lastYm, s, d)
            val next = cycle(lastYm.plusMonths(1), s, d)
            val billed = DueItems.billedAmount(snapshot, card, baseBalance(snapshot, card), cycle, emptyList())
            val paid = paidBetween(card, snapshot.ledger, emptyList(), cycle.statement, next.statement)
            return if (paid >= billed) {
                PaymentAssumption(PaymentAssumption.Source.LAST_FULL)
            } else {
                PaymentAssumption(PaymentAssumption.Source.LAST_AMOUNT, paid)
            }
        }
        val minimum = snapshot.cardStatements.filter { it.cardId == card.id && it.minimumPayment != null }.maxByOrNull { it.yearMonth }
        if (minimum != null) return PaymentAssumption(PaymentAssumption.Source.STATEMENT_MINIMUM, minimum.minimumPayment!!)
        val needsBill = card.card?.revolvingRatePercent != null && baseBalance(snapshot, card) > 0
        return PaymentAssumption(PaymentAssumption.Source.NO_RECORD, needsBill = needsBill)
    }

    /** 某一期的建議金額：依推估，不超過帳單還沒繳的部分（本月到期與試算一致）。 */
    fun suggested(assumption: PaymentAssumption, options: PaymentOptions): Money = when (assumption.source) {
        PaymentAssumption.Source.LAST_FULL, PaymentAssumption.Source.NO_RECORD -> options.full
        PaymentAssumption.Source.LAST_AMOUNT, PaymentAssumption.Source.PLANNED -> minOf(assumption.amount, options.full)
        PaymentAssumption.Source.STATEMENT_MINIMUM -> minOf(options.minimum ?: assumption.amount, options.full)
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
     * 依繳款推估（R-CARD-26）：全額繳清時不計息（新刷的下一期繳清，卡債不會累積）；
     * 只繳一部分時繳推估的金額（或 [payment]），沒繳清的部分計息。
     */
    fun outlook(balance: Money, terms: CardTerms, assumption: PaymentAssumption, monthlySpending: Money, payment: Money? = null): CardOutlook {
        val debt = balance.coerceAtLeast(0)
        // 全額繳清＝繳掉上一期的帳單（就是結轉過來的欠款）；當期新刷的要下一期才繳（R-CARD-20）。
        if (assumption.paysFull && payment == null) return CardOutlook(debt, 0, debt, monthlySpending)
        val paid = minOf(payment ?: assumption.amount, debt)
        return CardOutlook(debt, monthlyInterest(debt - paid, terms.revolvingRatePercent), paid, monthlySpending)
    }

    /** 照繳款推估、不再刷卡時，大約幾個月能還完；永遠還不完時為 null（R-CARD-06）。 */
    fun monthsToClear(balance: Money, terms: CardTerms, assumption: PaymentAssumption, monthlySpending: Money = 0, maxMonths: Int = 600): Int? {
        var remaining = balance
        if (remaining <= 0) return 0
        // 全額繳清：下一期帳單就把目前的欠款繳掉（之後每期都只是繳上一期，卡債不會累積）。
        if (assumption.paysFull) return 1
        for (month in 1..maxMonths) {
            val o = outlook(remaining, terms, assumption, monthlySpending)
            val next = remaining + o.interest + monthlySpending - o.payment
            if (next >= remaining && next > 0) return null
            remaining = next.coerceAtLeast(0)
            if (remaining == 0L) return month
        }
        return null
    }

    /**
     * 要留給卡費的現金（R-CARD-25）：已經刷了、之後一定要用現金繳掉的部分，不是真的可以自由花的錢。
     * - 沒有設定依帳單繳款、或推估為全額繳清的卡：整筆欠款（含未指定卡片的刷卡），下一兩期帳單都要繳清。
     * - 推估只繳一部分的卡（R-CARD-26）：只算本期要繳的（沒有未繳的帳單時用推估的金額）；其餘是長期卡債，不算在這裡。
     */
    fun reserve(snapshot: FinanceSnapshot): Money = snapshot.activeCards.sumOf { card ->
        val debt = baseBalance(snapshot, card).coerceAtLeast(0)
        if (!card.hasCardSchedule) return@sumOf debt
        val assumption = assumption(snapshot, card)
        if (assumption.paysFull) return@sumOf debt
        val cycle = latestCycle(card, snapshot.today)
        val bill = cycle?.takeIf { !DueItems.isRecorded(snapshot, DueItems.cardPaymentKey(card.id, it.yearMonth)) }?.let {
            suggested(assumption, DueItems.paymentOptions(snapshot, card, baseBalance(snapshot, card), it, emptyList(), debt))
        }
        minOf(bill ?: assumption.amount, debt)
    } + if (snapshot.defaultCardId == null) snapshot.unassignedCardSpending.coerceAtLeast(0) else 0L

    /** 試算畫面列出的推估說明（依帳單繳款的卡各一行）。 */
    fun assumptionNotes(snapshot: FinanceSnapshot): List<String> =
        snapshot.activeCards.filter { it.hasCardSchedule }.map { assumption(snapshot, it).describe(it.name) }

    /** 某張卡的起始欠款（和試算相同：未指定卡片的刷卡算在預設卡片上）。 */
    fun baseBalance(snapshot: FinanceSnapshot, card: Account): Money =
        card.balance + if (card.id == snapshot.defaultCardId) snapshot.unassignedCardSpending else 0L

    private fun day(ym: YearMonth, d: Int): LocalDate = ym.atDay(d.coerceIn(1, ym.lengthOfMonth()))
}
