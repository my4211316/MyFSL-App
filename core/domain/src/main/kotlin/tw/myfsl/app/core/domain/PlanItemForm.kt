package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode

/** 項目的一個計畫列草稿：支出為一個支付方式，收入與轉帳 [method] 為 null。 */
data class PlanLineDraft(
    val method: PaymentMethod? = null,
    /** 12 個月金額，索引 0 = 1 月；空白視為 0。 */
    val months: List<String> = List(12) { "" },
)

/**
 * 計畫項目編輯畫面的草稿。支出可以有多個支付方式列（例如生活費的現金列與信用卡列），
 * 支付方式由使用者自己選。
 */
data class PlanItemDraft(
    val id: Long = 0,
    val name: String = "",
    val groupId: Long? = null,
    /** 選「新增群組」時輸入的名稱。 */
    val newGroupName: String = "",
    val type: FlowType = FlowType.EXPENSE,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val timing: Timing = Timing.SPLIT,
    val flexibility: Flexibility = Flexibility.FIXED,
    val tracking: TrackingMode = TrackingMode.AUTO,
    val note: String = "",
    val lines: List<PlanLineDraft> = listOf(PlanLineDraft(PaymentMethod.CASH)),
    val sortOrder: Int = 0,
    val archived: Boolean = false,
) {
    /** 還沒用到的支付方式，給「新增支付方式列」用。 */
    val unusedMethods: List<PaymentMethod>
        get() = PaymentMethod.entries.filter { method -> lines.none { it.method == method } }

    fun lineTotal(index: Int): Money = lines.getOrNull(index)?.months?.sumOf { MoneyFormat.parse(it.ifBlank { "0" }) ?: 0L } ?: 0L
}

object PlanItemForm {

    object Field {
        const val NAME = "name"
        const val GROUP = "group"
        const val ACCOUNT = "account"
        const val TO_ACCOUNT = "toAccount"
        const val LINES = "lines"
        fun month(line: Int, month: Int) = "line$line-month$month"
        fun method(line: Int) = "line$line-method"
    }

    data class Result(
        val item: PlanItem?,
        /** 需要先建立的新群組名稱；null 表示用既有群組。 */
        val newGroupName: String?,
        val amounts: Map<PaymentMethod?, List<Money>>,
        val errors: Map<String, String>,
        val warnings: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty() && item != null
    }

    /** 新項目：支出預設一列現金，收入與轉帳一列不分支付方式。 */
    fun newDraft(type: FlowType, groupId: Long?, snapshot: FinanceSnapshot): PlanItemDraft = PlanItemDraft(
        groupId = groupId,
        type = type,
        accountId = if (type == FlowType.EXPENSE) null else snapshot.activeAccounts.firstOrNull { it.kind.isLiquid }?.id,
        lines = listOf(PlanLineDraft(if (type == FlowType.EXPENSE) PaymentMethod.CASH else null)),
    )

    fun fromItem(item: PlanItem, snapshot: FinanceSnapshot, year: Int): PlanItemDraft {
        val amounts = snapshot.planForYear(year).filterKeys { it.itemId == item.id }
        val lines = amounts.entries
            .sortedBy { it.key.method?.ordinal ?: -1 }
            .map { (line, months) -> PlanLineDraft(line.method, months.map { if (it == 0L) "" else it.toString() }) }
            .ifEmpty { listOf(PlanLineDraft(if (item.type == FlowType.EXPENSE) PaymentMethod.CASH else null)) }
        return PlanItemDraft(
            id = item.id,
            name = item.name,
            groupId = item.groupId,
            type = item.type,
            accountId = item.accountId,
            toAccountId = item.toAccountId,
            timing = item.timing,
            flexibility = item.flexibility,
            tracking = item.tracking,
            note = item.note,
            lines = lines,
            sortOrder = item.sortOrder,
            archived = item.archived,
        )
    }

    /** 切換類型時整理計畫列：支出要有支付方式，收入與轉帳合併成一列。 */
    fun changeType(draft: PlanItemDraft, type: FlowType): PlanItemDraft {
        if (type == draft.type) return draft
        val lines = if (type == FlowType.EXPENSE) {
            listOf(PlanLineDraft(PaymentMethod.CASH, merged(draft.lines)))
        } else {
            listOf(PlanLineDraft(null, merged(draft.lines)))
        }
        return draft.copy(type = type, lines = lines, toAccountId = if (type == FlowType.TRANSFER) draft.toAccountId else null)
    }

    /** 新增一個支付方式列（只有支出可以）。 */
    fun addLine(draft: PlanItemDraft, method: PaymentMethod): PlanItemDraft {
        if (draft.type != FlowType.EXPENSE || draft.lines.any { it.method == method }) return draft
        return draft.copy(lines = draft.lines + PlanLineDraft(method))
    }

    /** 刪掉一列；至少保留一列。 */
    fun removeLine(draft: PlanItemDraft, index: Int): PlanItemDraft {
        if (draft.lines.size <= 1 || index !in draft.lines.indices) return draft
        return draft.copy(lines = draft.lines.filterIndexed { i, _ -> i != index })
    }

    /** 改某一列的支付方式；如果另一列已經是這個支付方式，兩列交換。 */
    fun setMethod(draft: PlanItemDraft, index: Int, method: PaymentMethod): PlanItemDraft {
        if (index !in draft.lines.indices) return draft
        val current = draft.lines[index].method
        return draft.copy(
            lines = draft.lines.mapIndexed { i, line ->
                when {
                    i == index -> line.copy(method = method)
                    line.method == method -> line.copy(method = current)
                    else -> line
                }
            },
        )
    }

    fun setMonth(draft: PlanItemDraft, index: Int, month: Int, value: String): PlanItemDraft =
        updateLine(draft, index) { line -> line.copy(months = line.months.mapIndexed { i, v -> if (i == month - 1) value else v }) }

    /** 套用金額快捷（R-EDT-01）。 */
    fun fill(draft: PlanItemDraft, index: Int, months: List<Money>): PlanItemDraft =
        updateLine(draft, index) { it.copy(months = months.map { m -> if (m == 0L) "" else m.toString() }) }

    fun parsedMonths(line: PlanLineDraft): List<Money> = line.months.map { MoneyFormat.parse(it.ifBlank { "0" }) ?: 0L }

    fun validate(draft: PlanItemDraft, snapshot: FinanceSnapshot): Result {
        val errors = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val name = draft.name.trim()

        val newGroupName = if (draft.groupId == null) draft.newGroupName.trim() else null
        val group: PlanGroup? = draft.groupId?.let { id -> snapshot.groups.firstOrNull { it.id == id } }
        when {
            draft.groupId != null && group == null -> errors[Field.GROUP] = "請選擇群組"
            draft.groupId == null && newGroupName.isNullOrEmpty() -> errors[Field.GROUP] = "請選擇群組或輸入新群組名稱"
        }
        val existingGroupForNewName = newGroupName?.takeIf { it.isNotEmpty() }?.let { n ->
            snapshot.groups.firstOrNull { PlanImport.normalize(it.name) == PlanImport.normalize(n) }
        }

        if (name.isEmpty()) {
            errors[Field.NAME] = "請輸入項目名稱"
        } else {
            val targetGroupId = group?.id ?: existingGroupForNewName?.id
            if (targetGroupId != null && snapshot.items.any {
                    it.id != draft.id && !it.archived && it.groupId == targetGroupId && PlanImport.normalize(it.name) == PlanImport.normalize(name)
                }
            ) {
                errors[Field.NAME] = "這個群組已經有同名的項目"
            }
        }

        val liquid: List<Account> = snapshot.activeAccounts.filter { it.kind.isLiquid }
        when (draft.type) {
            FlowType.INCOME -> if (draft.accountId == null || liquid.none { it.id == draft.accountId }) {
                errors[Field.ACCOUNT] = "收入要選入帳帳戶"
            }

            FlowType.TRANSFER -> {
                if (draft.accountId == null || snapshot.activeAccounts.none { it.id == draft.accountId }) errors[Field.ACCOUNT] = "轉帳要選轉出帳戶"
                if (draft.toAccountId == null || snapshot.activeAccounts.none { it.id == draft.toAccountId }) {
                    errors[Field.TO_ACCOUNT] = "轉帳要選轉入帳戶"
                } else if (draft.toAccountId == draft.accountId) {
                    errors[Field.TO_ACCOUNT] = "轉出與轉入不能是同一個帳戶"
                }
            }

            FlowType.EXPENSE -> Unit
        }

        if (draft.lines.isEmpty()) errors[Field.LINES] = "至少要有一列金額"
        if (draft.type == FlowType.EXPENSE) {
            val seen = mutableSetOf<PaymentMethod>()
            draft.lines.forEachIndexed { index, line ->
                when {
                    line.method == null -> errors[Field.method(index)] = "請選支付方式"
                    !seen.add(line.method) -> errors[Field.method(index)] = "「${line.method.label}」重複了，同一個支付方式只能一列"
                }
            }
        } else if (draft.lines.size > 1) {
            errors[Field.LINES] = "${draft.type.label}只能有一列金額"
        }

        val amounts = linkedMapOf<PaymentMethod?, List<Money>>()
        draft.lines.forEachIndexed { index, line ->
            val parsed = line.months.mapIndexed { m, text ->
                val value = MoneyFormat.parse(text.ifBlank { "0" })
                when {
                    value == null -> { errors[Field.month(index, m + 1)] = "${m + 1} 月金額看不懂"; 0L }
                    value < 0 -> { errors[Field.month(index, m + 1)] = "${m + 1} 月金額不能是負數"; 0L }
                    else -> value
                }
            }
            val method = if (draft.type == FlowType.EXPENSE) line.method else null
            amounts[method] = parsed
        }
        if (errors.isEmpty() && amounts.values.all { months -> months.all { it == 0L } }) {
            warnings += "12 個月都是 0，這個項目不會出現在試算裡"
        }
        if (draft.flexibility == Flexibility.FLEXIBLE && draft.tracking == TrackingMode.AUTO) {
            warnings += "可調項目建議改成「依記帳」，才能控管進度"
        }

        if (errors.isNotEmpty()) return Result(null, null, emptyMap(), errors, warnings)

        val item = PlanItem(
            id = draft.id,
            name = name,
            groupId = group?.id ?: existingGroupForNewName?.id ?: 0L,
            type = draft.type,
            accountId = if (draft.type == FlowType.EXPENSE) null else draft.accountId,
            toAccountId = if (draft.type == FlowType.TRANSFER) draft.toAccountId else null,
            timing = draft.timing,
            flexibility = draft.flexibility,
            tracking = draft.tracking,
            note = draft.note.trim(),
            archived = draft.archived,
            sortOrder = draft.sortOrder,
        )
        val createGroup = if (group == null && existingGroupForNewName == null) newGroupName else null
        return Result(item, createGroup, amounts, emptyMap(), warnings)
    }

    private fun merged(lines: List<PlanLineDraft>): List<String> = List(12) { m ->
        val total = lines.sumOf { MoneyFormat.parse(it.months.getOrElse(m) { "" }.ifBlank { "0" }) ?: 0L }
        if (total == 0L) "" else total.toString()
    }

    private fun updateLine(draft: PlanItemDraft, index: Int, transform: (PlanLineDraft) -> PlanLineDraft): PlanItemDraft {
        if (index !in draft.lines.indices) return draft
        return draft.copy(lines = draft.lines.mapIndexed { i, line -> if (i == index) transform(line) else line })
    }
}
