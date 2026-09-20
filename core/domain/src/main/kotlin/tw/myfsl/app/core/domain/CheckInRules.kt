package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.Deferral
import java.time.YearMonth
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ReportInput
import tw.myfsl.app.core.model.TrackingMode
import java.time.LocalDate

/** 本週檢查裡，到期還沒記下的項目（R-DUE-05）怎麼處理。 */
enum class DueCheck(val label: String) {
    PAID("已付"),
    DIFFERENT_AMOUNT("金額不同"),
    SKIP("這個月沒有"),
    ;

    fun labelFor(income: Boolean): String = if (this == PAID && income) "已入帳" else label
}

data class DueDecision(val choice: DueCheck, val amount: Money? = null)

enum class ConfirmChoice(val label: String) {
    PAID("已付"),
    DIFFERENT_AMOUNT("金額不同"),
    POSTPONE("延到下月"),
    ;

    /** 按鈕文字：收入的「已付」改稱「已入帳」。 */
    fun labelFor(type: FlowType): String = if (this == PAID && type == FlowType.INCOME) "已入帳" else label
}

/** 每週回報的一列（現金分信封或能單獨查到金額的項目）。 */
data class ReportLine(
    val item: PlanItem,
    val method: PaymentMethod?,
    val planned: Money,
    /** 本月已記帳的金額。 */
    val recorded: Money,
) {
    val line: PlanLine get() = PlanLine(item.id)
    val input: ReportInput get() = CheckInRules.inputFor(method)
    val prefill: Money get() = CheckInRules.prefill(input, planned, recorded)
}

/** 到期確認的一列：本月計畫的到期項目，或之前延期、現在到期的款項（[deferral] 不為 null）。 */
data class ConfirmLine(
    val item: PlanItem,
    val method: PaymentMethod?,
    val planned: Money,
    val recorded: Money,
    val deferral: Deferral? = null,
) {
    val line: PlanLine get() = PlanLine(item.id)

    /** 在一次檢查裡辨識這一列。 */
    val key: String get() = deferral?.key ?: "line:${item.id}:${method?.name ?: "-"}"
}

/** 對帳結果的四種情況。 */
enum class DiffKind(val label: String) {
    MATCHED("已對帳"),
    MISSED("漏記"),
    PENDING("可能還沒入帳"),
    OVER_RECORDED("可能記重複"),
}

enum class Resolution(val label: String) {
    ASSIGN_ITEM("歸到項目"),
    BALANCE_ONLY("只校正餘額"),
    SKIP("先不處理"),
}

/**
 * 帳戶對帳的一列。流動帳戶各自一列；信用卡因為有未指定卡片的刷卡，所有卡片合計為一列。
 * [computed] 已包含本次檢查前面步驟產生的補記。
 */
data class Reconcile(
    val id: Long,
    val name: String,
    val accounts: List<Account>,
    val isCard: Boolean,
    val computed: Money,
    /** 最近幾天記的刷卡，可能還沒送到銀行。 */
    val pendingRecent: Money,
    val method: PaymentMethod,
    val defaultItem: PlanItem?,
    /** 信用卡合計：每張卡各自的推算欠款（含本次檢查記下的利息、繳款與刷卡），「填入各卡推算欠款」用。 */
    val perAccount: Map<Long, Money> = emptyMap(),
) {
    /** 正數代表實際比記帳多花了（漏記）。信用卡看欠款，所以方向相反。 */
    fun missed(actual: Money): Money = if (isCard) actual - computed else computed - actual

    fun kind(actual: Money): DiffKind = CheckInRules.classify(missed(actual), pendingRecent, isCard)
}

data class ConfirmDecision(val choice: ConfirmChoice, val amount: Money? = null)

/** 對帳輸入：流動帳戶填該帳戶餘額；信用卡合計要填每一張卡的欠款。 */
data class ReconcileDecision(
    val balances: Map<Long, Money> = emptyMap(),
    val resolution: Resolution? = null,
    val itemId: Long? = null,
)

data class CheckInInput(
    val reports: Map<PlanLine, Money> = emptyMap(),
    /** 到期確認，以 [ConfirmLine.key] 對應。 */
    val confirms: Map<String, ConfirmDecision> = emptyMap(),
    val reconciles: Map<Long, ReconcileDecision> = emptyMap(),
    /** 到期還沒記下的項目，以 [DueItem.key] 對應。 */
    val dues: Map<String, DueDecision> = emptyMap(),
)

data class CheckInResult(
    val entries: List<LedgerEntry>,
    val actuals: List<ItemActual>,
    /** 要寫入的校正餘額。 */
    val balances: Map<Long, Money>,
    /** 要新增（id = 0）或更新的延期款項。 */
    val deferrals: List<Deferral> = emptyList(),
    /** 選了「這個月沒有」的到期項目識別碼。 */
    val skippedKeys: List<String> = emptyList(),
    /** 記下貸款月繳後的剩餘期數。 */
    val loanRemaining: Map<Long, Int> = emptyMap(),
) {
    /** 這次記下的到期項目。 */
    val dueEntries: List<LedgerEntry> get() = entries.filter { it.source == EntrySource.DUE }
    val missedEntries: List<LedgerEntry> get() = entries.filter { it.source == EntrySource.MISSED && it.amount > 0 }
    val missedTotal: Money get() = missedEntries.sumOf { it.amount }
    val isEmpty: Boolean
        get() = entries.isEmpty() && actuals.isEmpty() && balances.isEmpty() && deferrals.isEmpty() && skippedKeys.isEmpty()
}

object CheckInRules {

    /** 對帳時「信用卡合計」那一列的 id（不是真的帳戶）。 */
    const val CARD_ROW_ID = -1L

    fun inputFor(method: PaymentMethod?): ReportInput =
        if (method == PaymentMethod.CASH) ReportInput.REMAINING else ReportInput.SPENT_TO_DATE

    fun prefill(input: ReportInput, planned: Money, recorded: Money): Money = when (input) {
        ReportInput.REMAINING -> (planned - recorded).coerceAtLeast(0)
        ReportInput.SPENT_TO_DATE -> recorded
    }

    /** 由使用者輸入換算本月至今花費。 */
    fun spentFromInput(input: ReportInput, planned: Money, value: Money): Money = when (input) {
        ReportInput.REMAINING -> (planned - value).coerceAtLeast(0)
        ReportInput.SPENT_TO_DATE -> value.coerceAtLeast(0)
    }

    /** 回報值與記帳的差額：正數代表漏記，負數代表多記。 */
    fun difference(spent: Money, recorded: Money): Money = spent - recorded

    /**
     * 到期了（今天或之前）還沒在記帳畫面記下的項目（R-DUE-05）。
     * 在對帳之前先處理，對帳時才不會把它們當成漏記。
     */
    fun dueLines(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today, input: CheckInInput = CheckInInput()): List<DueItem> =
        DueItems.list(snapshot, through = date) { due -> dueRecord(snapshot, due, input, date)?.entries.orEmpty() }

    /**
     * 使用者對一個到期項目的選擇實際會寫入什麼；還沒選、選「這個月沒有」或金額 0 為 null。
     * 清單依這個結果往下算，所以略過利息後，全額繳款會跟著少那筆利息（F06）。畫面預覽與寫入用同一份結果。
     */
    private fun dueRecord(snapshot: FinanceSnapshot, due: DueItem, input: CheckInInput, date: LocalDate): DueRecord? {
        val decision = input.dues[due.key] ?: return null
        val default = DueItems.defaultChoice(snapshot, due).copy(date = date)
        val choice = when (decision.choice) {
            DueCheck.SKIP -> return null
            DueCheck.PAID -> default
            DueCheck.DIFFERENT_AMOUNT -> default.copy(amount = decision.amount ?: return null)
        }
        if (choice.amount <= 0) return null
        return DueItems.record(snapshot, due, choice)
    }

    /** 每週回報的列：本月有計畫或已有記帳的支出列。 */
    fun reportLines(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<ReportLine> =
        rows(snapshot, date, TrackingMode.REPORT).mapNotNull { row ->
            if (row.item.type == FlowType.EXPENSE && (row.planned > 0 || row.recorded > 0)) {
                ReportLine(row.item, row.item.method, row.planned, row.recorded)
            } else {
                null
            }
        }

    /**
     * 到期確認的列（R-CHK-02）：本月有計畫、尚未完成也未延期的列，
     * 加上之前延期、到本月（或更早）到期還沒付清的款項（R-DEF-02）。
     */
    fun confirmLines(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<ConfirmLine> {
        val planLines = rows(snapshot, date, TrackingMode.CONFIRM).mapNotNull { row ->
            val status = ActualCalculator
                .actualFor(row.item.id, date.year, date.monthValue, snapshot.actuals, snapshot.ledger).status
            if (row.planned > 0 && status != ActualStatus.DONE && status != ActualStatus.POSTPONED) {
                ConfirmLine(row.item, row.item.method, row.planned, row.recorded)
            } else {
                null
            }
        }
        val deferred = snapshot.deferrals
            .filter { !it.settled && it.isDueBy(date.year, date.monthValue) }
            .mapNotNull { d -> snapshot.item(d.itemId)?.let { ConfirmLine(it, d.method, d.amount, 0, d) } }
        return planLines + deferred
    }

    private data class Row(val item: PlanItem, val line: PlanLine, val planned: Money, val recorded: Money)

    private fun rows(snapshot: FinanceSnapshot, date: LocalDate, tracking: TrackingMode): List<Row> =
        snapshot.activeItems.filter { it.tracking == tracking }.map { item ->
            Row(
                item = item,
                line = PlanLine(item.id),
                planned = snapshot.plannedAmount(item.id, date.year, date.monthValue),
                recorded = ActualCalculator
                    .actualFor(item.id, date.year, date.monthValue, snapshot.actuals, snapshot.ledger).amount,
            )
        }

    /** 帳戶對帳的列；[pending] 是本次檢查前面步驟已經產生的補記。 */
    fun reconciles(
        snapshot: FinanceSnapshot,
        pending: List<LedgerEntry> = emptyList(),
        date: LocalDate = snapshot.today,
    ): List<Reconcile> {
        val active = snapshot.activeAccounts
        val liquid = active.filter { it.kind.isLiquid }.map { account ->
            val method = methodFor(account.kind)
            Reconcile(
                id = account.id,
                name = account.name,
                accounts = listOf(account),
                isCard = false,
                computed = account.balance + pending.sumOf { BalanceRules.effect(it, account.id, account.kind) },
                pendingRecent = 0,
                method = method,
                defaultItem = defaultItemFor(snapshot, method, date),
            )
        }
        val cards = active.filter { it.kind == AccountKind.CREDIT_CARD }
        if (cards.isEmpty()) return liquid
        // 每張卡套用本次產生的記帳（利息、繳卡費、分期各期、指定卡片的刷卡）；未指定卡片的刷卡另外加（F01）。
        val perCard = cards.associate { card ->
            card.id to card.balance + pending.sumOf { BalanceRules.effect(it, card.id, AccountKind.CREDIT_CARD) }
        }
        val pendingUnassigned = pending
            .filter { it.type == FlowType.EXPENSE && it.method == PaymentMethod.CREDIT_CARD && it.accountId == null && !it.isInstallmentPurchase }
            .sumOf { it.amount }
        val since = date.minusDays(snapshot.settings.cardPostingDays.toLong())
        // 最近幾天自己記的刷卡可能還沒送到銀行（分期消費本身不算，入帳的是各期）。
        val recent = snapshot.ledger
            .filter {
                it.type == FlowType.EXPENSE && it.method == PaymentMethod.CREDIT_CARD && it.postingKey == null &&
                    !it.isInstallmentPurchase && it.date.isAfter(since)
            }
            .sumOf { it.amount }
        return liquid + Reconcile(
            id = CARD_ROW_ID,
            name = "信用卡合計",
            accounts = cards,
            isCard = true,
            computed = perCard.values.sum() + snapshot.unassignedCardSpending + pendingUnassigned,
            pendingRecent = recent,
            method = PaymentMethod.CREDIT_CARD,
            defaultItem = defaultItemFor(snapshot, PaymentMethod.CREDIT_CARD, date),
            perAccount = perCard,
        )
    }

    /** 帳戶種類對應的支付方式，用來把差額歸到項目。 */
    fun methodFor(kind: AccountKind): PaymentMethod = when (kind) {
        AccountKind.BANK -> PaymentMethod.TRANSFER
        AccountKind.CREDIT_CARD -> PaymentMethod.CREDIT_CARD
        else -> PaymentMethod.CASH
    }

    /**
     * 差額分類。銀行顯示的卡片欠款只會比實際少（有些刷卡還沒入帳），
     * 所以欠款比推算少、且不超過最近幾天記的刷卡時，視為還沒入帳。
     */
    fun classify(missed: Money, pendingRecent: Money, isCard: Boolean): DiffKind = when {
        missed == 0L -> DiffKind.MATCHED
        missed > 0L -> DiffKind.MISSED
        isCard && -missed <= pendingRecent -> DiffKind.PENDING
        else -> DiffKind.OVER_RECORDED
    }

    fun defaultResolution(kind: DiffKind): Resolution = when (kind) {
        DiffKind.MISSED -> Resolution.ASSIGN_ITEM
        DiffKind.PENDING -> Resolution.SKIP
        DiffKind.MATCHED, DiffKind.OVER_RECORDED -> Resolution.BALANCE_ONLY
    }

    fun message(kind: DiffKind, missed: Money, isCard: Boolean, postingDays: Int): String = when (kind) {
        DiffKind.MATCHED -> "和推算一樣"

        DiffKind.MISSED ->
            if (isCard) {
                "銀行多 ${MoneyFormat.currency(missed)}，可能有刷卡沒記"
            } else {
                "少了 ${MoneyFormat.currency(missed)}，可能是漏記"
            }

        DiffKind.PENDING -> "銀行少 ${MoneyFormat.currency(-missed)}，可能是最近 $postingDays 天的刷卡還沒入帳"

        DiffKind.OVER_RECORDED ->
            if (isCard) {
                "銀行少 ${MoneyFormat.currency(-missed)}，可能是記重複或有退款"
            } else {
                "多了 ${MoneyFormat.currency(-missed)}，可能是記重複或有收入沒記"
            }
    }

    /**
     * 差額預設歸到的項目：先找支付方式相同、本月計畫金額最大的可調項目；
     * 沒有就放寬到任何支付方式，再沒有就放寬到不可調的項目。
     */
    fun defaultItemFor(snapshot: FinanceSnapshot, method: PaymentMethod, date: LocalDate = snapshot.today): PlanItem? {
        val expenses = snapshot.activeItems.filter { it.type == FlowType.EXPENSE }
        fun plan(item: PlanItem) = snapshot.plannedAmount(item.id, date.year, date.monthValue)
        fun pick(candidates: List<PlanItem>) = candidates.filter { plan(it) > 0 }.maxByOrNull { plan(it) }
        val flexible = expenses.filter { it.flexibility == Flexibility.FLEXIBLE }
        return pick(flexible.filter { it.method == method })
            ?: pick(flexible)
            ?: pick(expenses.filter { it.method == method })
            ?: pick(expenses)
            ?: expenses.firstOrNull { it.method == method }
    }

    /** 把整份輸入換算成要寫入的記帳、狀態與校正餘額。純函式。 */
    fun build(snapshot: FinanceSnapshot, input: CheckInInput, date: LocalDate = snapshot.today): CheckInResult {
        val entries = mutableListOf<LedgerEntry>()
        val actuals = mutableListOf<ItemActual>()
        val balances = mutableMapOf<Long, Money>()

        reportLines(snapshot, date).forEach { line ->
            val value = input.reports[line.line] ?: return@forEach
            val diff = difference(spentFromInput(line.input, line.planned, value), line.recorded)
            if (diff != 0L) entries += adjustment(snapshot, line.item, line.method, diff, date, EntrySource.MISSED)
        }

        val deferrals = mutableListOf<Deferral>()
        val next = YearMonth.from(date).plusMonths(1)
        confirmLines(snapshot, date).forEach { line ->
            val decision = input.confirms[line.key] ?: return@forEach
            val deferral = line.deferral
            if (deferral != null) {
                // 延期款到期（R-DEF-02）：付了就結清；再延一次就改到期月份，金額不變。
                when (decision.choice) {
                    ConfirmChoice.POSTPONE -> deferrals += deferral.copy(dueYear = next.year, dueMonth = next.monthValue)
                    else -> {
                        val amount = if (decision.choice == ConfirmChoice.PAID) deferral.amount else requireNotNull(decision.amount) { "金額不同需要輸入實際金額" }
                        if (amount != 0L) {
                            entries += adjustment(snapshot, line.item, line.method, amount, date, EntrySource.CONFIRMED)
                                .copy(postingKey = deferral.key)
                        }
                        deferrals += deferral.copy(settled = true)
                    }
                }
                return@forEach
            }
            if (decision.choice == ConfirmChoice.POSTPONE) {
                // 延到下月：本月標為延期，還沒付的金額變成一筆獨立的延期款（R-DEF-01）。
                actuals += ItemActual(line.item.id, date.year, date.monthValue, ActualStatus.POSTPONED, date)
                val unpaid = (line.planned - line.recorded).coerceAtLeast(0)
                if (unpaid > 0) {
                    deferrals += Deferral(
                        itemId = line.item.id, method = line.method,
                        fromYear = date.year, fromMonth = date.monthValue,
                        dueYear = next.year, dueMonth = next.monthValue, amount = unpaid,
                    )
                }
                return@forEach
            }
            val amount = if (decision.choice == ConfirmChoice.PAID) {
                line.planned
            } else {
                requireNotNull(decision.amount) { "金額不同需要輸入實際金額" }
            }
            val diff = amount - line.recorded
            if (diff != 0L) entries += adjustment(snapshot, line.item, line.method, diff, date, EntrySource.CONFIRMED)
            actuals += ItemActual(line.item.id, date.year, date.monthValue, ActualStatus.DONE, date)
        }

        // 到期還沒記下的項目：已付就照建議金額記下，金額不同就記實際金額，這個月沒有就略過（R-DUE-05）。
        val skipped = mutableListOf<String>()
        val loanRemaining = mutableMapOf<Long, Int>()
        dueLines(snapshot, date, input).forEach { due ->
            val decision = input.dues[due.key] ?: return@forEach
            if (decision.choice == DueCheck.DIFFERENT_AMOUNT) requireNotNull(decision.amount) { "金額不同需要輸入實際金額" }
            val record = dueRecord(snapshot, due, input, date)
            if (record == null) {
                skipped += due.key
                return@forEach
            }
            entries += record.entries
            record.loanRemaining?.let { (id, _) ->
                val current = loanRemaining[id] ?: snapshot.account(id)?.loan?.remainingMonths ?: 0
                loanRemaining[id] = (current - 1).coerceAtLeast(0)
            }
        }

        reconciles(snapshot, entries, date).forEach { row ->
            val decision = input.reconciles[row.id] ?: return@forEach
            if (row.accounts.any { it.id !in decision.balances }) return@forEach
            val actual = row.accounts.sumOf { decision.balances.getValue(it.id) }
            val missed = row.missed(actual)
            val resolution = decision.resolution ?: defaultResolution(row.kind(actual))
            if (resolution == Resolution.SKIP) return@forEach
            val item = snapshot.item(decision.itemId) ?: row.defaultItem
            if (resolution == Resolution.ASSIGN_ITEM && missed != 0L && item != null) {
                entries += adjustment(snapshot, item, row.method, missed, date, EntrySource.MISSED)
            }
            row.accounts.forEach { balances[it.id] = decision.balances.getValue(it.id) }
        }

        return CheckInResult(entries, actuals, balances, deferrals, skipped, loanRemaining)
    }

    private fun adjustment(
        snapshot: FinanceSnapshot,
        item: PlanItem,
        method: PaymentMethod?,
        amount: Money,
        date: LocalDate,
        source: EntrySource,
    ): LedgerEntry = when (item.type) {
        FlowType.EXPENSE -> {
            val resolved = method ?: PaymentMethod.CASH
            LedgerEntry(
                date = date, type = FlowType.EXPENSE, amount = amount, itemId = item.id, method = resolved,
                accountId = if (resolved == PaymentMethod.CREDIT_CARD) null else snapshot.methodAccountId(resolved),
                source = source,
            )
        }

        FlowType.INCOME -> LedgerEntry(
            date = date, type = FlowType.INCOME, amount = amount, itemId = item.id,
            accountId = item.accountId, source = source,
        )

        FlowType.TRANSFER -> LedgerEntry(
            date = date, type = FlowType.TRANSFER, amount = amount, itemId = item.id,
            accountId = item.accountId, toAccountId = item.toAccountId, source = source,
        )
    }
}
