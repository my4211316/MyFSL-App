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
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode

/**
 * 計畫項目編輯畫面的草稿。一個項目只有一組 12 個月金額（R-MIX-01）；
 * 支付方式是項目的屬性，不切分金額。
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
    /** 支出的支付方式；收入與轉帳為 null。 */
    val method: PaymentMethod? = PaymentMethod.CASH,
    /** 試算是否依實際刷卡比例推估（R-MIX-03）。 */
    val useActualMix: Boolean = true,
    /** 12 個月金額，索引 0 = 1 月；空白視為 0。 */
    val months: List<String> = List(12) { "" },
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    /** 每月幾號（選填，1–31）。 */
    val dueDay: String = "",
    val extraRepayment: Boolean = false,
    val archivedFrom: Int? = null,
) {
    val total: Money get() = months.sumOf { MoneyFormat.parse(it.ifBlank { "0" }) ?: 0L }
}

object PlanItemForm {

    object Field {
        const val NAME = "name"
        const val GROUP = "group"
        const val ACCOUNT = "account"
        const val TO_ACCOUNT = "toAccount"
        const val METHOD = "method"
        const val TYPE = "type"
        const val DUE_DAY = "dueDay"
        fun month(month: Int) = "month$month"
    }

    data class Result(
        val item: PlanItem?,
        /** 需要先建立的新群組名稱；null 表示用既有群組。 */
        val newGroupName: String?,
        /** 12 個月金額，索引 0 = 1 月。 */
        val amounts: List<Money>,
        val errors: Map<String, String>,
        val warnings: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty() && item != null
    }

    /** 新項目：支出預設現金，收入與轉帳沒有支付方式。 */
    fun newDraft(type: FlowType, groupId: Long?, snapshot: FinanceSnapshot): PlanItemDraft = PlanItemDraft(
        groupId = groupId,
        type = type,
        accountId = if (type == FlowType.EXPENSE) null else snapshot.activeAccounts.firstOrNull { it.kind.isLiquid }?.id,
        method = if (type == FlowType.EXPENSE) PaymentMethod.CASH else null,
    )

    fun fromItem(item: PlanItem, snapshot: FinanceSnapshot, year: Int): PlanItemDraft {
        val months = snapshot.planForYear(year)[PlanLine(item.id)] ?: List(12) { 0L }
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
            months = months.map { if (it == 0L) "" else it.toString() },
            sortOrder = item.sortOrder,
            archived = item.archived,
            dueDay = item.dueDay?.toString().orEmpty(),
            extraRepayment = item.extraRepayment,
            archivedFrom = item.archivedFrom,
        )
    }

    /** 這個項目已經有記帳時，類型不能改（R-EDT-11）：舊記帳的類型會對不上。 */
    fun typeLocked(draft: PlanItemDraft, snapshot: FinanceSnapshot): Boolean =
        draft.id != 0L && snapshot.ledger.any { it.itemId == draft.id }

    /** 切換類型：支出要有支付方式，收入與轉帳沒有。 */
    fun changeType(draft: PlanItemDraft, type: FlowType): PlanItemDraft {
        if (type == draft.type) return draft
        return draft.copy(
            type = type,
            method = if (type == FlowType.EXPENSE) draft.method ?: PaymentMethod.CASH else null,
            toAccountId = if (type == FlowType.TRANSFER) draft.toAccountId else null,
        )
    }

    /** 改支付方式（只有支出可以）。 */
    fun setMethod(draft: PlanItemDraft, method: PaymentMethod): PlanItemDraft =
        if (draft.type != FlowType.EXPENSE) draft else draft.copy(method = method)

    fun setMonth(draft: PlanItemDraft, month: Int, value: String): PlanItemDraft =
        draft.copy(months = draft.months.mapIndexed { i, v -> if (i == month - 1) value else v })

    /** 套用金額快捷（R-EDT-01）。 */
    fun fill(draft: PlanItemDraft, months: List<Money>): PlanItemDraft =
        draft.copy(months = months.map { m -> if (m == 0L) "" else m.toString() })

    fun parsedMonths(draft: PlanItemDraft): List<Money> = draft.months.map { MoneyFormat.parse(it.ifBlank { "0" }) ?: 0L }

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

        val original = snapshot.item(draft.id)
        if (original != null && original.type != draft.type && typeLocked(draft, snapshot)) {
            errors[Field.TYPE] = "「${original.name}」已經有記帳，不能改成${draft.type.label}；請新增一個項目"
        }
        val dueDay = draft.dueDay.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..31 }.also { if (it == null) errors[Field.DUE_DAY] = "日期要是 1 到 31" }
        }
        if (draft.type == FlowType.EXPENSE && draft.method == null) errors[Field.METHOD] = "請選支付方式"

        val amounts = draft.months.mapIndexed { m, text ->
            val value = MoneyFormat.parse(text.ifBlank { "0" })
            when {
                value == null -> { errors[Field.month(m + 1)] = "${m + 1} 月金額看不懂"; 0L }
                value < 0 -> { errors[Field.month(m + 1)] = "${m + 1} 月金額不能是負數"; 0L }
                else -> value
            }
        }
        if (errors.isEmpty() && amounts.all { it == 0L }) {
            warnings += "12 個月都是 0，這個項目不會出現在試算裡"
        }
        if (draft.flexibility == Flexibility.FLEXIBLE && draft.tracking == TrackingMode.AUTO) {
            warnings += "可調項目建議改成「依記帳」，才能控管進度"
        }
        if (draft.tracking == TrackingMode.AUTO && draft.dueDay.isBlank()) {
            warnings += "每月固定沒填日期：上半月當作 1 號、下半月當作 16 號到期"
        }
        if (draft.type == FlowType.TRANSFER && snapshot.isAutoManagedDebt(draft.toAccountId) && !draft.extraRepayment) {
            warnings += "轉入的帳戶已依合約自動繳款，這個項目不會計入；若是額外還款請勾選「額外還款」"
        }

        if (errors.isNotEmpty()) return Result(null, null, emptyList(), errors, warnings)

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
            dueDay = dueDay,
            extraRepayment = draft.type == FlowType.TRANSFER && draft.extraRepayment,
            archivedFrom = draft.archivedFrom,
        )
        val createGroup = if (group == null && existingGroupForNewName == null) newGroupName else null
        return Result(item, createGroup, amounts, emptyMap(), warnings)
    }

}
