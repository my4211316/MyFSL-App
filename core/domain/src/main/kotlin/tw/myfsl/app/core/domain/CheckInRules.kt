package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
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
    val line: PlanLine get() = PlanLine(item.id, method)
    val input: ReportInput get() = CheckInRules.inputFor(method)
    val prefill: Money get() = CheckInRules.prefill(input, planned, recorded)
}

/** 到期確認的一列。 */
data class ConfirmLine(
    val item: PlanItem,
    val method: PaymentMethod?,
    val planned: Money,
    val recorded: Money,
) {
    val line: PlanLine get() = PlanLine(item.id, method)
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
    val confirms: Map<PlanLine, ConfirmDecision> = emptyMap(),
    val reconciles: Map<Long, ReconcileDecision> = emptyMap(),
)

data class CheckInResult(
    val entries: List<LedgerEntry>,
    val actuals: List<ItemActual>,
    /** 要寫入的校正餘額。 */
    val balances: Map<Long, Money>,
) {
    val missedEntries: List<LedgerEntry> get() = entries.filter { it.source == EntrySource.MISSED && it.amount > 0 }
    val missedTotal: Money get() = missedEntries.sumOf { it.amount }
    val isEmpty: Boolean get() = entries.isEmpty() && actuals.isEmpty() && balances.isEmpty()
}

object CheckInRules {

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

    /** 每週回報的列：本月有計畫或已有記帳的支出列。 */
    fun reportLines(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<ReportLine> =
        rows(snapshot, date, TrackingMode.REPORT).mapNotNull { row ->
            if (row.item.type == FlowType.EXPENSE && (row.planned > 0 || row.recorded > 0)) {
                ReportLine(row.item, row.line.method, row.planned, row.recorded)
            } else {
                null
            }
        }

    /** 到期確認的列：本月有計畫、尚未完成也未延期。 */
    fun confirmLines(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): List<ConfirmLine> =
        rows(snapshot, date, TrackingMode.CONFIRM).mapNotNull { row ->
            val status = ActualCalculator
                .actualFor(row.line, date.year, date.monthValue, snapshot.actuals, snapshot.ledger).status
            if (row.planned > 0 && status != ActualStatus.DONE && status != ActualStatus.POSTPONED) {
                ConfirmLine(row.item, row.line.method, row.planned, row.recorded)
            } else {
                null
            }
        }

    private data class Row(val item: PlanItem, val line: PlanLine, val planned: Money, val recorded: Money)

    private fun rows(snapshot: FinanceSnapshot, date: LocalDate, tracking: TrackingMode): List<Row> =
        snapshot.activeItems.filter { it.tracking == tracking }.flatMap { item ->
            val lines = if (item.type == FlowType.EXPENSE) {
                snapshot.linesOf(item.id).filter { it.method != null }
            } else {
                listOf(PlanLine(item.id, null))
            }
            lines.map { line ->
                Row(
                    item = item,
                    line = line,
                    planned = snapshot.planAmount(line, date.year, date.monthValue),
                    recorded = ActualCalculator
                        .actualFor(line, date.year, date.monthValue, snapshot.actuals, snapshot.ledger).amount,
                )
            }
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
        val pendingCard = pending
            .filter { it.type == FlowType.EXPENSE && it.method == PaymentMethod.CREDIT_CARD }
            .sumOf { it.amount }
        val since = date.minusDays(snapshot.settings.cardPostingDays.toLong())
        val recent = snapshot.ledger
            .filter { it.type == FlowType.EXPENSE && it.method == PaymentMethod.CREDIT_CARD && it.date.isAfter(since) }
            .sumOf { it.amount }
        return liquid + Reconcile(
            id = CashFlowEngine.CARD_POOL_ID,
            name = "信用卡合計",
            accounts = cards,
            isCard = true,
            computed = cards.sumOf { it.balance } + snapshot.unassignedCardSpending + pendingCard,
            pendingRecent = recent,
            method = PaymentMethod.CREDIT_CARD,
            defaultItem = defaultItemFor(snapshot, PaymentMethod.CREDIT_CARD, date),
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

    /** 差額預設歸到的項目：本月這個支付方式計畫金額最大的可調項目。 */
    fun defaultItemFor(snapshot: FinanceSnapshot, method: PaymentMethod, date: LocalDate = snapshot.today): PlanItem? {
        val expenses = snapshot.activeItems.filter { it.type == FlowType.EXPENSE }
        fun plan(item: PlanItem) = snapshot.planAmount(PlanLine(item.id, method), date.year, date.monthValue)
        return expenses.filter { it.flexibility == Flexibility.FLEXIBLE && plan(it) > 0 }.maxByOrNull { plan(it) }
            ?: expenses.filter { plan(it) > 0 }.maxByOrNull { plan(it) }
            ?: expenses.firstOrNull { item -> snapshot.linesOf(item.id).any { it.method == method } }
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

        confirmLines(snapshot, date).forEach { line ->
            val decision = input.confirms[line.line] ?: return@forEach
            if (decision.choice == ConfirmChoice.POSTPONE) {
                actuals += ItemActual(line.item.id, line.method, date.year, date.monthValue, ActualStatus.POSTPONED, date)
                return@forEach
            }
            val amount = if (decision.choice == ConfirmChoice.PAID) {
                line.planned
            } else {
                requireNotNull(decision.amount) { "金額不同需要輸入實際金額" }
            }
            val diff = amount - line.recorded
            if (diff != 0L) entries += adjustment(snapshot, line.item, line.method, diff, date, EntrySource.CONFIRMED)
            actuals += ItemActual(line.item.id, line.method, date.year, date.monthValue, ActualStatus.DONE, date)
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

        return CheckInResult(entries, actuals, balances)
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
