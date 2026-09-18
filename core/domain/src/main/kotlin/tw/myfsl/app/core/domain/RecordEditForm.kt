package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import java.time.LocalDate

/** 修改一筆記帳的草稿。類型不能改（支出改收入請刪掉重記）。 */
data class RecordDraft(
    val original: LedgerEntry,
    val date: LocalDate = original.date,
    val amount: String = original.amount.toString(),
    val itemId: Long? = original.itemId,
    val method: PaymentMethod? = original.method,
    /** 付款為信用卡時選的卡；null = 不指定。 */
    val cardId: Long? = if (original.method == PaymentMethod.CREDIT_CARD) original.accountId else null,
    val note: String = original.note,
) {
    val isInstallment: Boolean get() = original.installmentId != null

    /** 對帳差額可以是負數（多記差額）；自己記的一定是正數。 */
    val allowsNegative: Boolean get() = original.source != EntrySource.MANUAL
}

object RecordEditForm {

    object Field {
        const val DATE = "date"
        const val AMOUNT = "amount"
        const val ITEM = "item"
        const val METHOD = "method"
        const val CARD = "card"
    }

    data class Result(val entry: LedgerEntry?, val errors: Map<String, String>, val warnings: List<String>) {
        val ok: Boolean get() = errors.isEmpty() && entry != null
    }

    fun validate(draft: RecordDraft, snapshot: FinanceSnapshot): Result {
        val errors = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val original = draft.original

        if (draft.date.isAfter(snapshot.today)) errors[Field.DATE] = "日期不能晚於今天"

        val amount = MoneyFormat.parse(draft.amount)
        when {
            amount == null -> errors[Field.AMOUNT] = "金額看不懂"
            amount == 0L -> errors[Field.AMOUNT] = "金額不能是 0"
            amount < 0 && !draft.allowsNegative -> errors[Field.AMOUNT] = "金額要大於 0"
        }

        val item = draft.itemId?.let { snapshot.item(it) }
        if (draft.itemId != null && item == null) errors[Field.ITEM] = "這個項目已不存在"
        if (item != null && item.type != original.type) errors[Field.ITEM] = "只能改成同樣是${original.type.label}的項目"
        if (draft.itemId == null && original.itemId != null) errors[Field.ITEM] = "請選項目"

        val method = if (original.type == FlowType.EXPENSE) draft.method else null
        if (original.type == FlowType.EXPENSE && method == null) errors[Field.METHOD] = "請選付款方式"

        if (draft.isInstallment) {
            if (amount != original.amount) errors[Field.AMOUNT] = "分期消費的金額不能改；要改請刪掉重記"
            if (method != original.method) errors[Field.METHOD] = "分期消費的付款方式不能改；要改請刪掉重記"
        }

        val cardId = draft.cardId?.takeIf { method == PaymentMethod.CREDIT_CARD }
        if (cardId != null && snapshot.activeAccounts.none { it.id == cardId && it.kind == AccountKind.CREDIT_CARD }) {
            errors[Field.CARD] = "這張卡已不存在"
        }

        if (errors.isNotEmpty()) return Result(null, errors, warnings)

        val accountId: Long? = when (original.type) {
            FlowType.EXPENSE -> when {
                method == PaymentMethod.CREDIT_CARD -> cardId
                method == original.method -> original.accountId
                else -> snapshot.methodAccountId(method!!)
            }
            FlowType.INCOME -> if (item != null && item.id != original.itemId) item.accountId else original.accountId
            FlowType.TRANSFER -> if (item != null && item.id != original.itemId) item.accountId else original.accountId
        }
        val toAccountId = if (original.type == FlowType.TRANSFER && item != null && item.id != original.itemId) item.toAccountId else original.toAccountId

        val updated = original.copy(
            date = draft.date,
            amount = amount!!,
            itemId = item?.id ?: original.itemId,
            method = method,
            accountId = accountId,
            toAccountId = toAccountId,
            note = draft.note.trim(),
        )

        // 移到帳戶上次校正之前：校正餘額已經包含那天以前的錢，這筆就不會再影響餘額。
        listOfNotNull(updated.accountId, updated.toAccountId).mapNotNull { snapshot.account(it) }.forEach { account ->
            val asOf = account.balanceAsOf ?: return@forEach
            val wasAfter = !original.date.isBefore(asOf)
            if (wasAfter && updated.date.isBefore(asOf)) {
                warnings += "移到「${account.name}」上次校正餘額（${asOf.monthValue}/${asOf.dayOfMonth}）之前，這筆不會再改變它的餘額"
            }
        }
        if (updated == original) warnings += "沒有修改任何東西"
        return Result(updated, emptyMap(), warnings)
    }
}
