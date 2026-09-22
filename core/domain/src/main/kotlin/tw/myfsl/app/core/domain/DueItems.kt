package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.PostingKeys
import tw.myfsl.app.core.model.TrackingMode
import java.time.LocalDate
import java.time.YearMonth

/** 到期項目的種類。 */
enum class DueKind(val label: String) {
    PLAN("每月固定"),
    LOAN("貸款月繳"),
    CARD_INTEREST("循環利息"),
    CARD_PAYMENT("繳卡費"),
    INSTALLMENT("分期入帳"),
}

/**
 * 一個到期項目（R-DUE）：本月（或之前沒記下的）到期、還沒記下的款項。
 * [entries] 是依計畫與合約算好的建議記帳；使用者點下去時可以改金額與支付方式（[DueItems.record]）。
 */
data class DueItem(
    val key: String,
    val kind: DueKind,
    val date: LocalDate,
    val title: String,
    val entries: List<LedgerEntry>,
    val item: PlanItem? = null,
    /** 計畫列的支付方式（支出）；其他為 null。 */
    val method: PaymentMethod? = null,
    /** 相關的卡片或貸款帳戶。 */
    val relatedAccountId: Long? = null,
    /** 繳卡費：這一期可以繳的金額（全額、帳單上的最低），由使用者選方式（R-CARD-21）。 */
    val payOptions: CardRules.PaymentOptions? = null,
    /** 繳卡費：[entries] 的建議金額是怎麼推估的（R-CARD-26）。 */
    val assumption: CardRules.PaymentAssumption? = null,
    /** 繳卡費：這一期的結帳日（帳單校正用）。 */
    val statementDate: LocalDate? = null,
    /**
     * 有沒有填付款日（R-PER-02）。沒填的項目 [date] 取當月最後一天（只為了排序與範圍），
     * 整個月都可以點一下付掉，也不會提醒。
     */
    val dated: Boolean = true,
) {
    val amount: Money get() = entries.sumOf { it.amount }
    val isIncome: Boolean get() = item?.type == FlowType.INCOME
    /** 到期了：有日期的看日期，沒日期的整個月都算（R-PER-02）。 */
    fun isDue(today: LocalDate): Boolean = !dated || !date.isAfter(today)
    fun isOverdue(today: LocalDate): Boolean = YearMonth.from(date).isBefore(YearMonth.from(today))

/**
     * 快到期（R-DUE-07）：[DueItems.SOON_DAYS] 天內到期，或已經過了還沒記下；記帳畫面只提示這些。
     * 沒填付款日的不提醒（R-PER-02）——沒有哪一天可以提醒。
     */
    fun isSoon(today: LocalDate): Boolean = dated && !date.isAfter(today.plusDays(DueItems.SOON_DAYS))

    /** 支出可以選支付方式（現金／信用卡／轉帳）。 */
    val choosesMethod: Boolean get() = kind == DueKind.PLAN && item?.type == FlowType.EXPENSE

    /** 收入選入帳帳戶；轉帳、貸款、繳卡費選扣款帳戶。 */
    val choosesAccount: Boolean
        get() = kind == DueKind.LOAN || kind == DueKind.CARD_PAYMENT || (kind == DueKind.PLAN && item?.type != FlowType.EXPENSE)

    /** 分期各期的金額是消費時就定好的，不能改。 */
    val amountEditable: Boolean get() = kind != DueKind.INSTALLMENT

    /** 預設的帳戶（[choosesAccount] 時）。 */
    val defaultAccountId: Long?
        get() = when {
            isIncome -> entries.firstOrNull()?.accountId
            else -> entries.firstOrNull { it.type == FlowType.TRANSFER }?.accountId ?: entries.firstOrNull()?.accountId
        }
}

/** 使用者點下到期項目時的選擇。 */
data class DueChoice(
    val amount: Money,
    val method: PaymentMethod? = null,
    /** 收入的入帳帳戶，或轉帳、貸款、繳卡費的扣款帳戶。 */
    val accountId: Long? = null,
    /** 支付方式為信用卡時刷哪張卡；null 為不指定。 */
    val cardId: Long? = null,
    /** 實際付款日；null 為今天（R-DUE-03）。已經在到期日付過、只是現在才記時選到期日。 */
    val date: LocalDate? = null,
    /** 繳卡費這一期選的繳款方式（R-CARD-21）；不預選，由使用者選。 */
    val payMode: CardPayMode? = null,
)

/** 記下一個到期項目要寫入的東西。 */
data class DueRecord(
    val entries: List<LedgerEntry>,
    /** 貸款記下後剩下的期數。 */
    val loanRemaining: Pair<Long, Int>? = null,
)

/**
 * 本月到期的項目（R-DUE）。不會自動寫入任何記帳：列出來，使用者點一下、選支付方式才記下。
 *
 * 範圍：到期日晚於起算日（[FinanceSnapshot.trackingFrom]）、不晚於本月底，且還沒記下、也沒選「這個月沒有」。
 * 起算日（含）以前到期的視為已經包含在餘額裡。
 */
object DueItems {

    /** 記帳畫面提示「幾天內到期」（R-DUE-07）。 */
    const val SOON_DAYS = 3L

    /**
     * @param applied 前面的項目實際會寫入什麼（本週檢查依使用者的選擇：略過為空、金額不同為實際金額）；
     *   null 表示假設都照建議記下。後面項目的建議金額依這個結果算（F06）。
     */
    fun list(
        snapshot: FinanceSnapshot,
        through: LocalDate = YearMonth.from(snapshot.today).atEndOfMonth(),
        applied: ((DueItem) -> List<LedgerEntry>)? = null,
    ): List<DueItem> {
        val from = snapshot.trackingFrom
        if (!through.isAfter(from)) return emptyList()
        val months = monthsBetween(YearMonth.from(from), YearMonth.from(through))
        fun inWindow(date: LocalDate) = date.isAfter(from) && !date.isAfter(through)

        val tasks = mutableListOf<Task>()
        planTasks(snapshot, months, ::inWindow, tasks)
        cardTasks(snapshot, through, ::inWindow, tasks)
        loanTasks(snapshot, months, ::inWindow, tasks)
        installmentTasks(snapshot, ::inWindow, tasks)

        // 依日期與順序（R-ORD-01）模擬：前面的項目記下後，後面的建議金額（例如最低應繳）跟著算。
        val state = State(snapshot)
        val result = mutableListOf<DueItem>()
        tasks.sortedWith(compareBy({ it.date }, { it.priority }, { it.key })).forEach { task ->
            if (isRecorded(snapshot, task.key)) return@forEach
            val due = task.build(state) ?: return@forEach
            if (due.amount <= 0) return@forEach
            val entries = applied?.invoke(due) ?: due.entries
            entries.forEach(state::add)
            if (entries.isEmpty()) {
                // 沒有記下：貸款期數不扣。
                val id = due.relatedAccountId
                if (due.kind == DueKind.LOAN && id != null) state.loanRemaining[id] = (state.loanRemaining[id] ?: 0) + 1
            }
            result += due
        }
        return result
    }

    /** 點下到期項目時的預設選擇：建議金額、計畫的支付方式、預設帳戶與卡片。 */
    fun defaultChoice(snapshot: FinanceSnapshot, due: DueItem): DueChoice = DueChoice(
        amount = due.amount,
        method = due.method,
        accountId = if (due.choosesAccount) due.defaultAccountId else null,
        cardId = if (due.method == PaymentMethod.CREDIT_CARD) due.entries.firstOrNull()?.accountId else null,
    )

    /** 已經處理過：同一組識別碼任何一個已經記下或選了「這個月沒有」（只繳利息的貸款只有利息那一筆，F03）。 */
    fun isRecorded(snapshot: FinanceSnapshot, key: String): Boolean = groupKeys(key).any { it in snapshot.recordedKeys }

    /** 檢查選擇；沒問題時回傳 null。 */
    fun validate(snapshot: FinanceSnapshot, due: DueItem, choice: DueChoice): String? {
        if (choice.amount <= 0) return "金額要大於 0"
        if (choice.date?.isAfter(snapshot.today) == true) return "付款日不能晚於今天"
        if (due.choosesMethod && choice.method == null) return "請選支付方式"
        if (due.kind == DueKind.CARD_PAYMENT && choice.payMode == null) return "請選這一期的繳款方式"
        if (due.choosesAccount) {
            val account = snapshot.activeAccounts.firstOrNull { it.id == choice.accountId }
            if (account == null || !account.kind.isLiquid) return if (due.isIncome) "請選入帳帳戶" else "請選扣款帳戶"
        }
        return null
    }

    /**
     * 不擋存檔的提醒：繳卡費少於帳單上的最低應繳時（R-CARD-24）。
     */
    fun warning(snapshot: FinanceSnapshot, due: DueItem, choice: DueChoice): String? {
        if (due.kind != DueKind.CARD_PAYMENT) return null
        val minimum = due.payOptions?.minimum ?: return null
        return if (choice.amount in 1 until minimum) {
            "少於帳單上的最低應繳 ${MoneyFormat.currency(minimum)}，可能被收違約金並影響信用紀錄"
        } else {
            null
        }
    }

    /**
     * 依使用者的選擇產生要寫入的記帳。日期是實際付款日：預設今天（F02），
     * 這樣校正餘額之後才付的錢一定會算進餘額；已經在到期日付過、現在才補記時選到期日。
     * 屬於哪個月的預算看識別碼，不看付款日。
     */
    fun record(snapshot: FinanceSnapshot, due: DueItem, choice: DueChoice): DueRecord {
        val date = choice.date ?: snapshot.today
        val entries = when (due.kind) {
            DueKind.PLAN -> listOf(planEntry(snapshot, due, choice))

            DueKind.LOAN -> {
                val interest = due.entries.firstOrNull { it.type == FlowType.EXPENSE }?.amount ?: 0L
                val paidInterest = minOf(interest, choice.amount)
                val from = choice.accountId
                due.entries.mapNotNull { entry ->
                    val amount = if (entry.type == FlowType.EXPENSE) paidInterest else choice.amount - paidInterest
                    entry.copy(amount = amount, accountId = from ?: entry.accountId).takeIf { amount > 0 }
                }
            }

            DueKind.CARD_PAYMENT -> {
                val mode = choice.payMode
                val card = snapshot.account(due.relatedAccountId)?.name.orEmpty()
                due.entries.map {
                    it.copy(
                        amount = choice.amount,
                        accountId = choice.accountId ?: it.accountId,
                        note = if (mode != null) "繳 $card（${mode.label}）" else it.note,
                    )
                }
            }
            DueKind.CARD_INTEREST -> due.entries.map { it.copy(amount = choice.amount) }
            DueKind.INSTALLMENT -> due.entries
        }.map { it.copy(date = date) }

        val loan = if (due.kind == DueKind.LOAN) {
            snapshot.account(due.relatedAccountId)?.let { account ->
                account.loan?.let { account.id to (it.remainingMonths - 1).coerceAtLeast(0) }
            }
        } else {
            null
        }
        return DueRecord(entries, loan)
    }

    private fun planEntry(snapshot: FinanceSnapshot, due: DueItem, choice: DueChoice): LedgerEntry {
        val item = requireNotNull(due.item)
        val base = due.entries.first()
        return when (item.type) {
            FlowType.EXPENSE -> {
                val method = choice.method ?: due.method ?: PaymentMethod.CASH
                val account = if (method == PaymentMethod.CREDIT_CARD) {
                    choice.cardId?.takeIf { id -> snapshot.activeCards.any { it.id == id } }
                } else {
                    snapshot.methodAccountId(method)
                }
                base.copy(amount = choice.amount, method = method, accountId = account)
            }

            FlowType.INCOME -> base.copy(amount = choice.amount, accountId = choice.accountId ?: base.accountId)
            FlowType.TRANSFER -> base.copy(amount = choice.amount, accountId = choice.accountId ?: base.accountId)
        }
    }

    // ---------------------------------------------------------------- 內部

    private class Task(val date: LocalDate, val priority: Int, val key: String, val build: (State) -> DueItem?)

    /** 模擬前面的項目都記下之後的餘額，讓後面的建議金額正確。 */
    private class State(val snapshot: FinanceSnapshot) {
        val entries = mutableListOf<LedgerEntry>()
        val loanRemaining = mutableMapOf<Long, Int>()
        // 和試算相同的起始欠款：未指定卡片的刷卡算在預設卡片上（F04）。
        private val balances = snapshot.accounts.associate { it.id to it.balance }.toMutableMap().also { map ->
            snapshot.defaultCardId?.let { id -> map[id] = (map[id] ?: 0L) + snapshot.unassignedCardSpending }
        }
        private val kinds = snapshot.accounts.associate { it.id to it.kind }

        fun balance(id: Long): Money = balances[id] ?: 0L

        fun isLiability(id: Long?): Boolean = id?.let { kinds[it] }?.isLiability == true

        fun add(entry: LedgerEntry) {
            entries += entry
            listOfNotNull(entry.accountId, entry.toAccountId).distinct().forEach { id ->
                val kind = kinds[id] ?: return@forEach
                balances[id] = balance(id) + BalanceRules.effect(entry, id, kind)
            }
        }

        /**
         * 某項目某月已經有的實際金額（含前面已經列出的份額）。
         * 不分支付方式（R-MIX-01）：計畫刷卡、實際付現金也一樣抵掉，不會重複列出。
         * 延期款的付款不算。月份看預算月份。
         */
        fun covered(item: PlanItem, ym: YearMonth): Money =
            (snapshot.ledger + entries).filter {
                it.itemId == item.id && it.budgetMonth == ym && it.countsForBudget &&
                    it.postingKey?.startsWith(PostingKeys.DEFERRAL) != true
            }.sumOf { it.amount }
    }

    private fun monthsBetween(from: YearMonth, to: YearMonth): List<YearMonth> =
        generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun day(ym: YearMonth, d: Int): LocalDate = ym.atDay(d.coerceIn(1, ym.lengthOfMonth()))

    /** 分期那一期入帳的日子：那張卡的結帳日，沒設就當月初。 */
    private fun postingDay(snapshot: FinanceSnapshot, cardId: Long, ym: YearMonth): LocalDate =
        day(ym, snapshot.account(cardId)?.statementDay ?: 1)

    fun ymKey(ym: YearMonth) = "%04d-%02d".format(ym.year, ym.monthValue)

    /** 一個項目一個月只會到期一次（R-PER-01），所以識別碼只到月份：`plan:<項目>:<年月>`。 */
    fun planKey(item: PlanItem, ym: YearMonth) = "${PostingKeys.PLAN}${item.id}:${ymKey(ym)}"

    fun loanKey(loanId: Long, ym: YearMonth) = "${PostingKeys.LOAN}$loanId:${ymKey(ym)}"
    fun cardInterestKey(cardId: Long, ym: YearMonth) = "${PostingKeys.CARD_INTEREST}$cardId:${ymKey(ym)}"
    fun cardPaymentKey(cardId: Long, ym: YearMonth) = "${PostingKeys.CARD_PAYMENT}$cardId:${ymKey(ym)}"
    fun installmentKey(installmentId: Long, number: Int) = "${PostingKeys.INSTALLMENT_PRINCIPAL}$installmentId:$number"
    fun installmentFeeKey(installmentId: Long, number: Int) = "${PostingKeys.INSTALLMENT_FEE}$installmentId:$number"

    /** 每月固定的計畫項目。 */
    private fun planTasks(snapshot: FinanceSnapshot, months: List<YearMonth>, inWindow: (LocalDate) -> Boolean, tasks: MutableList<Task>) {
        for (ym in months) {
            for (item in snapshot.items) {
                if (item.tracking != TrackingMode.AUTO || !snapshot.isItemActiveIn(item, ym.year, ym.monthValue)) continue
                if (!snapshot.countsAsOwnTransfer(item)) continue
                val monthAmount = snapshot.plannedAmount(item.id, ym.year, ym.monthValue)
                if (monthAmount <= 0) continue
                val done = snapshot.actuals.any {
                    it.itemId == item.id && it.year == ym.year && it.month == ym.monthValue &&
                        (it.status == ActualStatus.DONE || it.status == ActualStatus.POSTPONED)
                }
                if (done) continue
                val method = if (item.type == FlowType.EXPENSE) EntryRules.defaultMethod(snapshot, item) else null
                // 一個月一次（R-PER-01）：有填付款日就在那一天，沒填就是「這個月」（R-PER-02）。
                val due = item.dueDateIn(ym.year, ym.monthValue)
                val date = due ?: ym.atEndOfMonth()
                if (!inWindow(date)) continue
                val key = planKey(item, ym)
                tasks += Task(date, 4, key) { state ->
                    // 已經自己記了多少，就少列多少；整月最多到計畫金額。
                    var post = monthAmount - state.covered(item, ym)
                    // 還款最多還到欠款為 0（R-PAY-02）。
                    if (item.type == FlowType.TRANSFER && state.isLiability(item.toAccountId)) {
                        post = minOf(post, state.balance(item.toAccountId!!).coerceAtLeast(0))
                    }
                    if (post <= 0) return@Task null
                    val entry = planEntry(snapshot, item, method, date, post, key) ?: return@Task null
                    DueItem(key, DueKind.PLAN, date, item.name, dated = due != null, entries = listOf(entry), item = item, method = method)
                }
            }
        }
    }

    private fun planEntry(snapshot: FinanceSnapshot, item: PlanItem, method: PaymentMethod?, date: LocalDate, amount: Money, key: String): LedgerEntry? =
        when (item.type) {
            FlowType.EXPENSE -> {
                val m = method ?: return null
                val account = if (m == PaymentMethod.CREDIT_CARD) snapshot.defaultCardId else snapshot.methodAccountId(m)
                LedgerEntry(date = date, type = FlowType.EXPENSE, amount = amount, itemId = item.id, method = m, accountId = account, source = EntrySource.DUE, postingKey = key)
            }

            FlowType.INCOME -> item.accountId?.let {
                LedgerEntry(date = date, type = FlowType.INCOME, amount = amount, itemId = item.id, accountId = it, source = EntrySource.DUE, postingKey = key)
            }

            FlowType.TRANSFER -> if (item.accountId != null && item.toAccountId != null) {
                LedgerEntry(
                    date = date, type = FlowType.TRANSFER, amount = amount, itemId = item.id,
                    accountId = item.accountId, toAccountId = item.toAccountId, source = EntrySource.DUE, postingKey = key,
                )
            } else {
                null
            }
        }

    /**
     * 有繳款條件的卡（R-CARD-20–22）：
     * - 結帳日：上一期帳單沒繳清的部分計循環利息。上一期的截止日在起算日（含）以前時，視為已經繳清（餘額是那天輸入的）。
     *   帳單校正時已經把這一期利息算進帳單的，識別碼已處理，不再列出（R-CARD-23）。
     * - 截止日：繳這一期的帳單，建議金額依預設繳款方式，三種方式的金額都帶著（R-CARD-21）。
     */
    private fun cardTasks(snapshot: FinanceSnapshot, through: LocalDate, inWindow: (LocalDate) -> Boolean, tasks: MutableList<Task>) {
        snapshot.activeCards.forEach { card ->
            val terms = card.card ?: return@forEach
            val payAccount = terms.payAccountId?.takeIf { id -> snapshot.activeAccounts.any { it.id == id && it.kind.isLiquid } }
                ?: snapshot.methodAccountId(PaymentMethod.TRANSFER)
            val base = CardRules.baseBalance(snapshot, card)
            CardRules.cycles(card, snapshot.trackingFrom, through).forEach { cycle ->
                val ym = cycle.yearMonth
                if (inWindow(cycle.statement)) {
                    val interestKey = cardInterestKey(card.id, ym)
                    tasks += Task(cycle.statement, 1, interestKey) { state ->
                        val interest = interestFor(snapshot, card, base, cycle, state.entries)
                        if (interest <= 0) return@Task null
                        val entry = LedgerEntry(
                            date = cycle.statement, type = FlowType.EXPENSE, amount = interest, accountId = card.id,
                            note = "${card.name} 循環利息", source = EntrySource.DUE, postingKey = interestKey,
                        )
                        DueItem(interestKey, DueKind.CARD_INTEREST, cycle.statement, "${card.name} 循環利息", listOf(entry), relatedAccountId = card.id)
                    }
                }
                if (inWindow(cycle.due)) {
                    val payKey = cardPaymentKey(card.id, ym)
                    // 推估要逐期算：「照計畫編的金額」看截止日那個月編多少（R-CARD-27、V37-01）
                    val assumption = CardRules.assumptionFor(snapshot, card, cycle.due)
                    tasks += Task(cycle.due, 2, payKey) { state ->
                        val from = payAccount ?: return@Task null
                        val options = paymentOptions(snapshot, card, base, cycle, state.entries, state.balance(card.id))
                        // 建議金額依推估（R-CARD-26），和試算一致；帳單上的最低應繳是 0 而推估照最低時，這一期不用繳、不列（V3-04）。
                        val amount = CardRules.suggested(assumption, options)
                        if (options.full <= 0 || amount <= 0) return@Task null
                        val title = "繳 ${card.name}"
                        val entry = LedgerEntry(
                            date = cycle.due, type = FlowType.TRANSFER, amount = amount, accountId = from, toAccountId = card.id,
                            note = title, source = EntrySource.DUE, postingKey = payKey,
                        )
                        DueItem(
                            payKey, DueKind.CARD_PAYMENT, cycle.due, title, listOf(entry), relatedAccountId = card.id,
                            payOptions = options, assumption = assumption, statementDate = cycle.statement,
                        )
                    }
                }
            }
        }
    }

    /**
     * 某一期結帳日的循環利息：上一期帳單在這一期結帳前沒繳清的部分 × 年利率 ÷ 12（R-CARD-22）。
     * 上一期的截止日在起算日（含）以前：那天輸入的餘額已經反映，視為繳清。
     */
    fun interestFor(snapshot: FinanceSnapshot, card: Account, base: Money, cycle: CardRules.Cycle, extra: List<LedgerEntry>): Money {
        val terms = card.card ?: return 0
        val previous = CardRules.previous(card, cycle) ?: return 0
        if (!previous.due.isAfter(snapshot.trackingFrom)) return 0
        val billed = billedAmount(snapshot, card, base, previous, extra)
        val paid = CardRules.paidBetween(card, snapshot.ledger, extra, previous.statement, cycle.statement)
        return CardRules.monthlyInterest(billed - paid, terms.revolvingRatePercent)
    }

    /**
     * 某一期的帳單金額：使用者輸入過帳單就以帳單為準（R-CARD-23）；沒有時用結帳日（含）的欠款估計。
     * 帳單差額的記帳在比它新的餘額校正之後不影響目前欠款，所以這裡直接讀帳單，不從目前欠款回推（V3-03）。
     */
    fun billedAmount(snapshot: FinanceSnapshot, card: Account, base: Money, cycle: CardRules.Cycle, extra: List<LedgerEntry>): Money =
        snapshot.statementOf(card.id, cycle.yearMonth)?.amount
            ?: CardRules.balanceAt(snapshot, card, base, snapshot.ledger, extra, cycle.statement)

    /** 某一期的繳款選項：帳單金額扣掉結帳後已經繳的，再依繳款方式（R-CARD-20）。 */
    fun paymentOptions(
        snapshot: FinanceSnapshot,
        card: Account,
        base: Money,
        cycle: CardRules.Cycle,
        extra: List<LedgerEntry>,
        debt: Money,
    ): CardRules.PaymentOptions {
        val billed = billedAmount(snapshot, card, base, cycle, extra)
        val (s, d) = requireNotNull(CardRules.cycleDays(card))
        val next = CardRules.cycle(cycle.yearMonth.plusMonths(1), s, d)
        val paid = CardRules.paidBetween(card, snapshot.ledger, extra, cycle.statement, next.statement)
        return CardRules.options(billed - paid, debt, snapshot.statementOf(card.id, cycle.yearMonth)?.minimumPayment)
    }

    /** 有攤還條件的貸款：本金轉入貸款帳戶、利息算支出。 */
    private fun loanTasks(snapshot: FinanceSnapshot, months: List<YearMonth>, inWindow: (LocalDate) -> Boolean, tasks: MutableList<Task>) {
        snapshot.activeAccounts.filter { it.loan != null }.forEach { loan ->
            val terms = loan.loan!!
            for (ym in months) {
                val date = day(ym, terms.payDay)
                if (!inWindow(date)) continue
                val key = loanKey(loan.id, ym)
                tasks += Task(date, 3, key) { state ->
                    val remaining = state.loanRemaining[loan.id] ?: terms.remainingMonths
                    val balance = state.balance(loan.id)
                    if (remaining <= 0 || balance <= 0) return@Task null
                    val installment = LoanAmortization.schedule(balance, terms.annualRatePercent, remaining, terms.method).first()
                    state.loanRemaining[loan.id] = remaining - 1
                    val entries = listOfNotNull(
                        installment.principal.takeIf { it > 0 }?.let {
                            LedgerEntry(
                                date = date, type = FlowType.TRANSFER, amount = it, accountId = terms.payAccountId, toAccountId = loan.id,
                                note = "${loan.name} 本金", source = EntrySource.DUE, postingKey = key,
                            )
                        },
                        installment.interest.takeIf { it > 0 }?.let {
                            LedgerEntry(
                                date = date, type = FlowType.EXPENSE, amount = it, accountId = terms.payAccountId,
                                note = "${loan.name} 利息", source = EntrySource.DUE, postingKey = "$key:interest",
                            )
                        },
                    )
                    DueItem(key, DueKind.LOAN, date, "${loan.name} 月繳", entries, relatedAccountId = loan.id)
                }
            }
        }
    }

    /**
     * 分期：每期在那個月入帳本金（變成卡債，不重算預算）與手續費（算支出）。
     * 一期就是一個月（R-PER-01）；入帳日看那張卡的結帳日，沒設結帳日的就當月初。
     */
    private fun installmentTasks(snapshot: FinanceSnapshot, inWindow: (LocalDate) -> Boolean, tasks: MutableList<Task>) {
        snapshot.installments.filter { !it.settled }.forEach { installment ->
            val card = installment.cardAccountId ?: snapshot.defaultCardId ?: return@forEach
            val name = snapshot.item(installment.itemId)?.name ?: "分期"
            InstallmentRules.schedule(installment).forEach { period ->
                val date = postingDay(snapshot, card, Period.fromIndex(period.periodIndex).yearMonth)
                if (!inWindow(date)) return@forEach
                val key = installmentKey(installment.id, period.number)
                tasks += Task(date, 0, key) {
                    val entries = listOfNotNull(
                        period.principal.takeIf { it > 0 }?.let {
                            LedgerEntry(
                                date = date, type = FlowType.EXPENSE, amount = it, itemId = installment.itemId,
                                method = PaymentMethod.CREDIT_CARD, accountId = card, installmentId = installment.id,
                                note = "分期 ${period.number}/${installment.months}", source = EntrySource.DUE, postingKey = key,
                            )
                        },
                        period.fee.takeIf { it > 0 }?.let {
                            LedgerEntry(
                                date = date, type = FlowType.EXPENSE, amount = it, itemId = installment.itemId,
                                method = PaymentMethod.CREDIT_CARD, accountId = card, installmentId = installment.id,
                                note = "分期手續費 ${period.number}/${installment.months}", source = EntrySource.DUE,
                                postingKey = installmentFeeKey(installment.id, period.number),
                            )
                        },
                    )
                    DueItem(key, DueKind.INSTALLMENT, date, "$name 分期 ${period.number}/${installment.months}", entries, relatedAccountId = card)
                }
            }
        }
    }

    /** 同一個到期項目的所有識別碼：貸款的本金與利息、分期同一期的本金與手續費。刪除時一起刪。 */
    fun groupKeys(key: String): List<String> = when {
        key.startsWith(PostingKeys.LOAN) -> key.removeSuffix(":interest").let { listOf(it, "$it:interest") }
        key.startsWith(PostingKeys.INSTALLMENT_FEE) -> key.removePrefix(PostingKeys.INSTALLMENT_FEE)
            .let { listOf(PostingKeys.INSTALLMENT_PRINCIPAL + it, PostingKeys.INSTALLMENT_FEE + it) }
        key.startsWith(PostingKeys.INSTALLMENT_PRINCIPAL) -> key.removePrefix(PostingKeys.INSTALLMENT_PRINCIPAL)
            .let { listOf(PostingKeys.INSTALLMENT_PRINCIPAL + it, PostingKeys.INSTALLMENT_FEE + it) }
        else -> listOf(key)
    }

    /** 循環利息識別碼（`cardint:3:2026-09`）裡的卡片 id。 */
    fun cardIdOf(key: String): Long? =
        if (key.startsWith(PostingKeys.CARD_INTEREST)) key.removePrefix(PostingKeys.CARD_INTEREST).substringBefore(':').toLongOrNull() else null

    /** 貸款月繳識別碼（`loan:5:2026-09`）裡的貸款帳戶 id。 */
    fun loanIdOf(key: String): Long? =
        if (key.startsWith(PostingKeys.LOAN)) key.removePrefix(PostingKeys.LOAN).substringBefore(':').toLongOrNull() else null

    /** 分期的某一期還沒入帳：到期日在起算日之後，且還沒記下。 */
    fun isInstallmentPeriodPending(snapshot: FinanceSnapshot, installmentId: Long, number: Int, periodIndex: Int): Boolean =
        Period.fromIndex(periodIndex).startDate.isAfter(snapshot.trackingFrom) &&
            !isRecorded(snapshot, installmentKey(installmentId, number))
}
