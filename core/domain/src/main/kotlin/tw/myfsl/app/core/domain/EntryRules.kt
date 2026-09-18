package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import java.time.LocalDate

/** 記帳金額鍵盤的輸入規則。 */
object AmountInput {
    const val MAX_DIGITS = 7

    /** [key] 為 "0"–"9" 或 "00"。會去掉前導 0；超過 7 位數時忽略這次按鍵。 */
    fun press(current: String, key: String): String {
        require(key.isNotEmpty() && key.all { it.isDigit() }) { "invalid key: $key" }
        val next = (current + key).trimStart('0')
        return if (next.length <= MAX_DIGITS) next else current
    }

    fun backspace(current: String): String = current.dropLast(1)

    fun value(current: String): Money = current.toLongOrNull() ?: 0L

    /** 顯示用：沒有輸入時顯示 0。 */
    fun display(current: String): String = MoneyFormat.plain(value(current))
}

/** 記帳時顯示在金額下方的預算提示。 */
sealed interface BudgetHint {
    /** 這個支付方式有計畫金額。 */
    data class Planned(
        val itemName: String,
        val method: PaymentMethod,
        val left: Money,
        val amount: Money,
    ) : BudgetHint {
        val after: Money get() = left - amount
    }

    /** 項目有計畫，但沒有規劃這個支付方式：金額仍算進項目總額。 */
    data class UnplannedMethod(
        val itemName: String,
        val method: PaymentMethod,
        val itemLeftAfter: Money,
    ) : BudgetHint

    /** 項目本月沒有任何計畫金額。 */
    data object NotInPlan : BudgetHint

    /** 收入與轉帳不影響支出進度。 */
    data object NotExpense : BudgetHint
}

object EntryRules {

    /** 預設支付方式：這個項目上次用的；沒用過就用本月計畫金額最大的；再沒有就用第一個計畫列；都沒有則為現金。 */
    fun defaultMethod(snapshot: FinanceSnapshot, item: PlanItem, date: LocalDate = snapshot.today): PaymentMethod? {
        if (item.type != FlowType.EXPENSE) return null
        snapshot.ledger
            .filter { it.source == EntrySource.MANUAL && it.itemId == item.id && it.method != null }
            .maxWithOrNull(compareBy({ it.date }, { it.id }))
            ?.method
            ?.let { return it }
        val lines = snapshot.linesOf(item.id).filter { it.method != null }
        lines.map { it to snapshot.planAmount(it, date.year, date.monthValue) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first?.method
            ?.let { return it }
        return lines.firstOrNull()?.method ?: PaymentMethod.CASH
    }

    /** 預設卡片：「指定信用卡」開啟時，用這個項目上次刷的卡；否則為不指定（null）。 */
    fun defaultCard(snapshot: FinanceSnapshot, item: PlanItem): Long? {
        if (!snapshot.settings.pickCard) return null
        val cards = snapshot.activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD }.map { it.id }.toSet()
        return snapshot.ledger
            .filter { it.source == EntrySource.MANUAL && it.itemId == item.id && it.method == PaymentMethod.CREDIT_CARD }
            .maxWithOrNull(compareBy({ it.date }, { it.id }))
            ?.accountId
            ?.takeIf { it in cards }
    }

    fun budgetHint(
        snapshot: FinanceSnapshot,
        item: PlanItem,
        method: PaymentMethod?,
        amount: Money,
        date: LocalDate = snapshot.today,
    ): BudgetHint {
        if (item.type != FlowType.EXPENSE || method == null) return BudgetHint.NotExpense
        val year = date.year
        val month = date.monthValue
        val line = PlanLine(item.id, method)
        val planned = snapshot.planAmount(line, year, month)
        if (planned > 0) {
            val spent = ActualCalculator.actualFor(line, year, month, snapshot.actuals, snapshot.ledger).amount
            return BudgetHint.Planned(item.name, method, planned - spent, amount)
        }
        val plannedMethods = snapshot.plannedMethods(item.id, year, month)
        if (plannedMethods.isEmpty()) return BudgetHint.NotInPlan
        val totalPlanned = plannedMethods.sumOf { snapshot.planAmount(PlanLine(item.id, it), year, month) }
        val totalSpent = PaymentMethod.entries.sumOf {
            ActualCalculator.actualFor(PlanLine(item.id, it), year, month, snapshot.actuals, snapshot.ledger).amount
        }
        return BudgetHint.UnplannedMethod(item.name, method, totalPlanned - totalSpent - amount)
    }

    fun hintText(hint: BudgetHint): String = when (hint) {
        is BudgetHint.Planned -> {
            val name = "${hint.itemName}・${hint.method.label}"
            when {
                hint.amount == 0L && hint.left >= 0 -> "$name 本月剩 ${MoneyFormat.currency(hint.left)}"
                hint.amount == 0L -> "$name 本月已超出 ${MoneyFormat.currency(-hint.left)}"
                hint.after >= 0 -> "記下後 $name 剩 ${MoneyFormat.currency(hint.after)}"
                else -> "記下後超出 $name 計畫 ${MoneyFormat.currency(-hint.after)}"
            }
        }

        is BudgetHint.UnplannedMethod ->
            "${hint.itemName}沒有規劃用${hint.method.label}，會算進${hint.itemName}總額（剩 ${MoneyFormat.currency(hint.itemLeftAfter)}）"

        BudgetHint.NotInPlan -> "不在本月計畫內，會列入計畫外支出"
        BudgetHint.NotExpense -> "不影響支出進度"
    }

    /** 提示是否要用警示色。 */
    fun isWarning(hint: BudgetHint): Boolean = when (hint) {
        is BudgetHint.Planned -> if (hint.amount == 0L) hint.left < 0 else hint.after < 0
        is BudgetHint.UnplannedMethod -> true
        else -> false
    }

    /** 支出的扣款帳戶：現金、轉帳用對應帳戶；刷卡在「指定信用卡」開啟時用所選卡片，否則為 null。 */
    fun resolveAccount(snapshot: FinanceSnapshot, method: PaymentMethod, cardId: Long?): Long? = when (method) {
        PaymentMethod.CASH, PaymentMethod.TRANSFER -> snapshot.methodAccountId(method)
        PaymentMethod.CREDIT_CARD -> if (snapshot.settings.pickCard) cardId else null
    }

    /**
     * 建立一筆記帳；金額為 0 時回傳 null（「記下」按鈕不可按）。
     * [refund] 為 true 時存成負數（R-ENT-13）：退款、退貨，會減少這個項目的花費並退回原付款帳戶或卡片。
     */
    fun buildEntry(
        snapshot: FinanceSnapshot,
        item: PlanItem,
        method: PaymentMethod?,
        cardId: Long?,
        amountInput: String,
        note: String,
        date: LocalDate = snapshot.today,
        refund: Boolean = false,
    ): LedgerEntry? {
        val value = AmountInput.value(amountInput)
        if (value <= 0) return null
        val amount = if (refund && item.type == FlowType.EXPENSE) -value else value
        return when (item.type) {
            FlowType.EXPENSE -> {
                val resolvedMethod = method ?: PaymentMethod.CASH
                LedgerEntry(
                    date = date, type = FlowType.EXPENSE, amount = amount, itemId = item.id,
                    method = resolvedMethod, accountId = resolveAccount(snapshot, resolvedMethod, cardId), note = note.trim(),
                )
            }

            FlowType.INCOME -> LedgerEntry(
                date = date, type = FlowType.INCOME, amount = amount, itemId = item.id,
                accountId = item.accountId, note = note.trim(),
            )

            FlowType.TRANSFER -> LedgerEntry(
                date = date, type = FlowType.TRANSFER, amount = amount, itemId = item.id,
                accountId = item.accountId, toAccountId = item.toAccountId, note = note.trim(),
            )
        }
    }

    /** 記下後的提示訊息（以記下前的資料計算）。 */
    fun savedMessage(snapshot: FinanceSnapshot, item: PlanItem, method: PaymentMethod?, amount: Money, note: String): String {
        val name = note.trim().ifEmpty { item.name }
        val base = "已記下 $name ${MoneyFormat.currency(amount)}"
        val hint = budgetHint(snapshot, item, method, amount)
        return if (hint is BudgetHint.Planned) "$base · ${hint.itemName}・${hint.method.label}剩 ${MoneyFormat.currency(hint.after)}" else base
    }

    /** 記帳畫面上方提示列，只計算今天的支出。 */
    fun todayStrip(snapshot: FinanceSnapshot, date: LocalDate = snapshot.today): String {
        val entries = snapshot.ledger.filter {
            it.date == date && it.type == FlowType.EXPENSE && it.source == EntrySource.MANUAL
        }
        return "${date.monthValue}/${date.dayOfMonth} 今天已記 ${entries.size} 筆 · ${MoneyFormat.currency(entries.sumOf { it.amount })}"
    }
}
