package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.ScenarioChange
import java.time.LocalDate
import java.time.YearMonth

/** 情境編輯裡可以加的變動種類。「貸款整合」會存成新增貸款＋清償負債兩個變動。 */
enum class ChangeKind(val label: String) {
    CUT("調整支出"),
    CONSOLIDATE("貸款整合卡債"),
    LOAN("新增貸款"),
    PAYOFF("清償負債"),
    METHOD("改支付方式"),
    ONE_OFF("一次性收支"),
    STOP("停止項目"),
}

/** 一個變動的草稿；欄位依 [kind] 使用，數字都是字串方便綁輸入框。 */
data class ChangeDraft(
    val kind: ChangeKind,
    /** 從幾個月後開始：0 = 本月。 */
    val monthOffset: Int = 1,
    val itemIds: Set<Long> = emptySet(),
    val percent: String = "-10",
    val name: String = "",
    val amount: String = "",
    val rate: String = "",
    val months: String = "60",
    val repayment: RepaymentMethod = RepaymentMethod.EQUAL_PAYMENT,
    val payHalf: Half = Half.FIRST,
    val depositAccountId: Long? = null,
    val payAccountId: Long? = null,
    val debtAccountIds: Set<Long> = emptySet(),
    val includeInstallments: Boolean = true,
    val stopScheduledPayments: Boolean = true,
    val fromMethod: PaymentMethod = PaymentMethod.CREDIT_CARD,
    val toMethod: PaymentMethod = PaymentMethod.CASH,
    val oneOffType: FlowType = FlowType.EXPENSE,
    val oneOffMethod: PaymentMethod = PaymentMethod.CASH,
)

data class ScenarioDraft(
    val id: Long = 0,
    val name: String = "",
    val note: String = "",
    val changes: List<ChangeDraft> = emptyList(),
    val createdOn: LocalDate? = null,
)

object ScenarioForm {

    object Field {
        const val NAME = "name"
        const val CHANGES = "changes"
        fun of(index: Int, field: String) = "c$index-$field"
    }

    data class Result(val scenario: Scenario?, val errors: Map<String, String>) {
        val ok: Boolean get() = errors.isEmpty() && scenario != null
    }

    /** 新變動的預設值：帶入第一個銀行帳戶、所有信用卡、所有可調支出。 */
    fun newChange(kind: ChangeKind, snapshot: FinanceSnapshot): ChangeDraft {
        val liquid = snapshot.activeAccounts.filter { it.kind.isLiquid }
        val bank = liquid.firstOrNull { it.kind == AccountKind.BANK }?.id ?: liquid.firstOrNull()?.id
        val cards = snapshot.activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD }
        val flexible = snapshot.activeItems.filter { it.type == FlowType.EXPENSE && it.flexibility == Flexibility.FLEXIBLE }.map { it.id }.toSet()
        return when (kind) {
            ChangeKind.CUT -> ChangeDraft(kind, itemIds = flexible)
            ChangeKind.CONSOLIDATE -> ChangeDraft(
                kind, name = "整合貸款", amount = cards.sumOf { it.balance }.takeIf { it > 0 }?.toString().orEmpty(),
                depositAccountId = bank, payAccountId = bank, debtAccountIds = cards.map { it.id }.toSet(),
            )
            ChangeKind.LOAN -> ChangeDraft(kind, name = "新貸款", depositAccountId = bank, payAccountId = bank)
            ChangeKind.PAYOFF -> ChangeDraft(kind, payAccountId = bank, debtAccountIds = cards.map { it.id }.toSet())
            ChangeKind.METHOD -> ChangeDraft(kind, itemIds = flexible)
            ChangeKind.ONE_OFF -> ChangeDraft(kind, name = "", depositAccountId = bank)
            ChangeKind.STOP -> ChangeDraft(kind)
        }
    }

    /** 月份偏移轉成期別：該月上半月，但不早於今天所在的期別。 */
    fun indexFor(today: LocalDate, monthOffset: Int): Int {
        val ym = YearMonth.from(today).plusMonths(monthOffset.toLong())
        return maxOf(Period(ym.year, ym.monthValue, Half.FIRST).index, Period.of(today).index)
    }

    fun offsetFor(today: LocalDate, index: Int): Int =
        ForecastSummary.monthsUntil(Period.of(today), Period.fromIndex(index)).coerceAtLeast(0)

    fun validate(draft: ScenarioDraft, snapshot: FinanceSnapshot): Result {
        val errors = linkedMapOf<String, String>()
        val today = snapshot.today
        if (draft.name.isBlank()) errors[Field.NAME] = "請輸入情境名稱"
        if (draft.changes.isEmpty()) errors[Field.CHANGES] = "至少加一個變動"

        val liquidIds = snapshot.activeAccounts.filter { it.kind.isLiquid }.map { it.id }.toSet()
        val debtIds = snapshot.activeAccounts.filter { it.kind.isLiability }.map { it.id }.toSet()
        val itemIds = snapshot.activeItems.map { it.id }.toSet()

        val changes = mutableListOf<ScenarioChange>()
        draft.changes.forEachIndexed { i, c ->
            fun err(field: String, message: String) { errors[Field.of(i, field)] = message }
            val at = indexFor(today, c.monthOffset)

            fun money(field: String, text: String, label: String): Money? {
                val value = MoneyFormat.parse(text)
                if (value == null || value <= 0) err(field, "請輸入$label")
                return value?.takeIf { it > 0 }
            }

            fun rate(): Double? {
                val value = c.rate.trim().removeSuffix("%").toDoubleOrNull()
                if (value == null || value < 0 || value > 100) err("rate", "年利率要在 0 到 100 之間")
                return value?.takeIf { it in 0.0..100.0 }
            }

            fun loanMonths(): Int? {
                val value = c.months.trim().toIntOrNull()
                if (value == null || value <= 0 || value > 480) err("months", "期數要在 1 到 480 之間")
                return value?.takeIf { it in 1..480 }
            }

            fun liquid(field: String, id: Long?, label: String): Long? {
                if (id == null || id !in liquidIds) err(field, "請選$label")
                return id?.takeIf { it in liquidIds }
            }

            when (c.kind) {
                ChangeKind.CUT -> {
                    val percent = c.percent.trim().removeSuffix("%").toDoubleOrNull()
                    if (percent == null || percent < -100 || percent > 1000) err("percent", "百分比看不懂（減少 10% 請填 -10）")
                    if (c.itemIds.none { it in itemIds }) err("items", "至少選一個項目")
                    if (percent != null && percent in -100.0..1000.0 && c.itemIds.any { it in itemIds }) {
                        changes += ScenarioChange.AdjustItems(c.itemIds.filter { it in itemIds }.sorted(), percent, at)
                    }
                }

                ChangeKind.CONSOLIDATE, ChangeKind.LOAN -> {
                    val principal = money("amount", c.amount, "貸款金額")
                    val r = rate()
                    val n = loanMonths()
                    val deposit = liquid("deposit", c.depositAccountId, "撥款帳戶")
                    val pay = liquid("pay", c.payAccountId, "扣款帳戶")
                    val debts = if (c.kind == ChangeKind.CONSOLIDATE) {
                        c.debtAccountIds.filter { it in debtIds }.also { if (it.isEmpty()) err("debts", "至少選一個要清償的負債") }
                    } else {
                        emptyList()
                    }
                    if (principal != null && r != null && n != null && deposit != null && pay != null &&
                        (c.kind == ChangeKind.LOAN || debts.isNotEmpty())
                    ) {
                        val name = c.name.trim().ifEmpty { if (c.kind == ChangeKind.CONSOLIDATE) "整合貸款" else "新貸款" }
                        changes += ScenarioChange.AddLoan(name, principal, r, n, c.repayment, at, deposit, pay, c.payHalf)
                        if (c.kind == ChangeKind.CONSOLIDATE) {
                            changes += ScenarioChange.PayOffDebts(debts.sorted(), deposit, at, c.stopScheduledPayments, c.includeInstallments)
                        }
                    }
                }

                ChangeKind.PAYOFF -> {
                    val from = liquid("pay", c.payAccountId, "付款帳戶")
                    val debts = c.debtAccountIds.filter { it in debtIds }
                    if (debts.isEmpty()) err("debts", "至少選一個要清償的負債")
                    if (from != null && debts.isNotEmpty()) {
                        changes += ScenarioChange.PayOffDebts(debts.sorted(), from, at, c.stopScheduledPayments, c.includeInstallments)
                    }
                }

                ChangeKind.METHOD -> {
                    if (c.fromMethod == c.toMethod) err("method", "原本與改成的支付方式不能一樣")
                    if (c.itemIds.none { it in itemIds }) err("items", "至少選一個項目")
                    if (c.fromMethod != c.toMethod && c.itemIds.any { it in itemIds }) {
                        changes += ScenarioChange.ChangeMethod(c.itemIds.filter { it in itemIds }.sorted(), c.fromMethod, c.toMethod, at)
                    }
                }

                ChangeKind.ONE_OFF -> {
                    if (c.name.isBlank()) err("name", "請輸入名稱")
                    val amount = money("amount", c.amount, "金額")
                    val isIncome = c.oneOffType == FlowType.INCOME
                    val account = if (isIncome) liquid("deposit", c.depositAccountId, "入帳帳戶") else null
                    if (c.name.isNotBlank() && amount != null && (!isIncome || account != null)) {
                        changes += if (isIncome) {
                            ScenarioChange.OneOff(c.name.trim(), at, FlowType.INCOME, amount, accountId = account)
                        } else {
                            ScenarioChange.OneOff(c.name.trim(), at, FlowType.EXPENSE, amount, method = c.oneOffMethod)
                        }
                    }
                }

                ChangeKind.STOP -> {
                    val id = c.itemIds.firstOrNull { it in itemIds }
                    if (id == null) err("items", "請選要停止的項目") else changes += ScenarioChange.StopItem(id, at)
                }
            }
        }

        if (errors.isNotEmpty()) return Result(null, errors)
        return Result(
            Scenario(draft.id, draft.name.trim(), draft.createdOn ?: today, changes, draft.note.trim()),
            emptyMap(),
        )
    }

    /** 已存的情境轉回草稿。新增貸款後緊接同期的清償，合併回「貸款整合卡債」。 */
    fun fromScenario(scenario: Scenario, today: LocalDate): ScenarioDraft {
        val drafts = mutableListOf<ChangeDraft>()
        val list = scenario.changes
        var i = 0
        while (i < list.size) {
            val change = list[i]
            val next = list.getOrNull(i + 1)
            when (change) {
                is ScenarioChange.AddLoan -> {
                    val base = ChangeDraft(
                        kind = ChangeKind.LOAN, monthOffset = offsetFor(today, change.startIndex), name = change.name,
                        amount = change.principal.toString(), rate = trim(change.annualRatePercent), months = change.months.toString(),
                        repayment = change.method, payHalf = change.payHalf, depositAccountId = change.depositAccountId, payAccountId = change.payAccountId,
                    )
                    if (next is ScenarioChange.PayOffDebts && next.atIndex == change.startIndex && next.fromAccountId == change.depositAccountId) {
                        drafts += base.copy(
                            kind = ChangeKind.CONSOLIDATE, debtAccountIds = next.accountIds.toSet(),
                            includeInstallments = next.includeInstallments, stopScheduledPayments = next.stopScheduledPayments,
                        )
                        i++
                    } else {
                        drafts += base
                    }
                }

                is ScenarioChange.AdjustItems -> drafts += ChangeDraft(
                    ChangeKind.CUT, offsetFor(today, change.fromIndex), itemIds = change.itemIds.toSet(), percent = trim(change.percent),
                )

                is ScenarioChange.PayOffDebts -> drafts += ChangeDraft(
                    ChangeKind.PAYOFF, offsetFor(today, change.atIndex), payAccountId = change.fromAccountId,
                    debtAccountIds = change.accountIds.toSet(), includeInstallments = change.includeInstallments,
                    stopScheduledPayments = change.stopScheduledPayments,
                )

                is ScenarioChange.ChangeMethod -> drafts += ChangeDraft(
                    ChangeKind.METHOD, offsetFor(today, change.fromIndex), itemIds = change.itemIds.toSet(),
                    fromMethod = change.fromMethod, toMethod = change.toMethod,
                )

                is ScenarioChange.OneOff -> drafts += ChangeDraft(
                    ChangeKind.ONE_OFF, offsetFor(today, change.atIndex), name = change.name, amount = change.amount.toString(),
                    oneOffType = change.type, oneOffMethod = change.method ?: PaymentMethod.CASH, depositAccountId = change.accountId,
                )

                is ScenarioChange.StopItem -> drafts += ChangeDraft(ChangeKind.STOP, offsetFor(today, change.fromIndex), itemIds = setOf(change.itemId))
            }
            i++
        }
        return ScenarioDraft(scenario.id, scenario.name, scenario.note, drafts, scenario.createdOn)
    }

    /** 情境 chips 用的一句話摘要。 */
    fun describe(change: ScenarioChange, snapshot: FinanceSnapshot): String {
        fun month(index: Int) = Period.fromIndex(index).let { "${it.year}/${it.month}" }
        fun items(ids: List<Long>) = ids.mapNotNull { snapshot.item(it)?.name }.let { if (it.size > 2) "${it.take(2).joinToString("、")} 等 ${it.size} 項" else it.joinToString("、") }
        fun accounts(ids: List<Long>) = ids.mapNotNull { snapshot.account(it)?.name }.joinToString("、")
        return when (change) {
            is ScenarioChange.AdjustItems -> "${items(change.itemIds)} ${if (change.percent < 0) "減" else "增"} ${trim(kotlin.math.abs(change.percent))}%（${month(change.fromIndex)} 起）"
            is ScenarioChange.AddLoan -> "${change.name} ${MoneyFormat.compact(change.principal)} · ${trim(change.annualRatePercent)}% · ${change.months} 期（${month(change.startIndex)}）"
            is ScenarioChange.PayOffDebts -> "清償 ${accounts(change.accountIds)}（${month(change.atIndex)}）"
            is ScenarioChange.ChangeMethod -> "${items(change.itemIds)} ${change.fromMethod.label}改${change.toMethod.label}（${month(change.fromIndex)} 起）"
            is ScenarioChange.OneOff -> "${change.name} ${if (change.type == FlowType.INCOME) "+" else "−"}${MoneyFormat.compact(change.amount)}（${month(change.atIndex)}）"
            is ScenarioChange.StopItem -> "停止 ${snapshot.item(change.itemId)?.name.orEmpty()}（${month(change.fromIndex)} 起）"
        }
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
