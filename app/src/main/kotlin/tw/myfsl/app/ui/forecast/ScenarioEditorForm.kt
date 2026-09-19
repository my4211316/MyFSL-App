package tw.myfsl.app.ui.forecast

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.ChangeDraft
import tw.myfsl.app.core.domain.ChangeKind
import tw.myfsl.app.core.domain.ScenarioDraft
import tw.myfsl.app.core.domain.ScenarioForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.FormScaffold
import tw.myfsl.app.ui.components.MoneyField
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.components.methodIcon
import tw.myfsl.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.YearMonth

/** 情境編輯（設計稿：全畫面改版 v1）：名稱＋一串變動，每個變動一張卡片。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenarioEditorForm(
    editor: ScenarioEditor,
    today: LocalDate,
    accounts: List<Account>,
    expenseItems: List<PlanItem>,
    onDraft: ((ScenarioDraft) -> ScenarioDraft) -> Unit,
    onAddChange: (ChangeKind) -> Unit,
    onChange: (Int, (ChangeDraft) -> ChangeDraft) -> Unit,
    onRemoveChange: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = editor.draft
    val errors = editor.errors
    FormScaffold(title = if (editor.isNew) "新增情境" else "編輯情境", onClose = onCancel, onSave = onSave, modifier = modifier) {
        TextInput("情境名稱", draft.name, { v -> onDraft { it.copy(name = v) } }, error = errors[Field.NAME])
        TextInput("備註（選填）", draft.note, { v -> onDraft { it.copy(note = v) } })

        SectionHeader("變動")
        errors[Field.CHANGES]?.let { ErrorText(it) }
        draft.changes.forEachIndexed { index, change ->
            ChangeCard(index, change, today, accounts, expenseItems, errors, { onChange(index, it) }, { onRemoveChange(index) })
        }

        FieldLabel("加一個變動")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ChangeKind.entries.forEach { kind ->
                AssistChip(
                    onClick = { onAddChange(kind) },
                    label = { Text(kind.label) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }

        if (errors.isNotEmpty()) ErrorText("有 ${errors.size} 個欄位要修正")
        if (!editor.isNew) DangerButton("刪除這個情境", onClick = onDelete, icon = Icons.Rounded.Delete)
    }
}

@Composable
private fun ChangeCard(
    index: Int,
    c: ChangeDraft,
    today: LocalDate,
    accounts: List<Account>,
    items: List<PlanItem>,
    errors: Map<String, String>,
    update: ((ChangeDraft) -> ChangeDraft) -> Unit,
    onRemove: () -> Unit,
) {
    fun e(field: String) = errors[Field.of(index, field)]
    val liquid = accounts.filter { it.kind.isLiquid }
    val debts = accounts.filter { it.kind.isLiability }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = "刪除這個變動") }
        }

        val single = c.kind == ChangeKind.ONE_OFF || c.kind == ChangeKind.PAYOFF || c.kind == ChangeKind.LOAN || c.kind == ChangeKind.CONSOLIDATE
        FieldLabel(if (single) "月份" else "從哪個月開始")
        MonthRow(today, c.monthOffset) { offset -> update { it.copy(monthOffset = offset) } }

        when (c.kind) {
            ChangeKind.CUT -> {
                TextInput("調整百分比（減少 10% 填 -10）", c.percent, { v -> update { it.copy(percent = v) } }, error = e("percent"), number = true)
                ItemPicker(items, c.itemIds, e("items"), multi = true) { id -> update { it.copy(itemIds = toggle(it.itemIds, id)) } }
            }

            ChangeKind.CONSOLIDATE, ChangeKind.LOAN -> {
                TextInput("貸款名稱", c.name, { v -> update { it.copy(name = v) } })
                MoneyField("貸款金額", c.amount, { v -> update { it.copy(amount = v) } }, error = e("amount"))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextInput("年利率（%）", c.rate, { v -> update { it.copy(rate = v) } }, Modifier.weight(1f), error = e("rate"), number = true)
                    TextInput("期數（月）", c.months, { v -> update { it.copy(months = v) } }, Modifier.weight(1f), error = e("months"), number = true)
                }
                FieldLabel("攤還方式")
                SegmentedChoice(RepaymentMethod.entries, c.repayment, { it.label }, { m -> update { it.copy(repayment = m) } })
                FieldLabel("每月繳款時間")
                SegmentedChoice(Half.entries, c.payHalf, { it.label }, { h -> update { it.copy(payHalf = h) } })
                AccountChips("撥款帳戶", liquid, c.depositAccountId, e("deposit")) { id -> update { it.copy(depositAccountId = id) } }
                AccountChips("扣款帳戶", liquid, c.payAccountId, e("pay")) { id -> update { it.copy(payAccountId = id) } }
                if (c.kind == ChangeKind.CONSOLIDATE) {
                    DebtChips(debts, c.debtAccountIds, e("debts")) { id -> update { it.copy(debtAccountIds = toggle(it.debtAccountIds, id)) } }
                    SwitchRow("一併結清未到期分期", c.includeInstallments, { v -> update { it.copy(includeInstallments = v) } })
                    SwitchRow("停止原本繳這些負債的計畫", c.stopScheduledPayments, { v -> update { it.copy(stopScheduledPayments = v) } })
                }
            }

            ChangeKind.PAYOFF -> {
                AccountChips("付款帳戶", liquid, c.payAccountId, e("pay")) { id -> update { it.copy(payAccountId = id) } }
                DebtChips(debts, c.debtAccountIds, e("debts")) { id -> update { it.copy(debtAccountIds = toggle(it.debtAccountIds, id)) } }
                SwitchRow("一併結清未到期分期", c.includeInstallments, { v -> update { it.copy(includeInstallments = v) } })
                SwitchRow("停止原本繳這些負債的計畫", c.stopScheduledPayments, { v -> update { it.copy(stopScheduledPayments = v) } })
            }

            ChangeKind.METHOD -> {
                FieldLabel("原本")
                SegmentedChoice(PaymentMethod.entries, c.fromMethod, { it.label }, { m -> update { it.copy(fromMethod = m) } }, icon = ::methodIcon)
                FieldLabel("改成")
                SegmentedChoice(PaymentMethod.entries, c.toMethod, { it.label }, { m -> update { it.copy(toMethod = m) } }, icon = ::methodIcon)
                e("method")?.let { ErrorText(it) }
                ItemPicker(items, c.itemIds, e("items"), multi = true) { id -> update { it.copy(itemIds = toggle(it.itemIds, id)) } }
            }

            ChangeKind.ONE_OFF -> {
                SegmentedChoice(listOf(FlowType.EXPENSE, FlowType.INCOME), c.oneOffType, { it.label }, { t -> update { it.copy(oneOffType = t) } })
                TextInput("名稱", c.name, { v -> update { it.copy(name = v) } }, error = e("name"))
                MoneyField("金額", c.amount, { v -> update { it.copy(amount = v) } }, error = e("amount"))
                if (c.oneOffType == FlowType.INCOME) {
                    AccountChips("入帳帳戶", liquid, c.depositAccountId, e("deposit")) { id -> update { it.copy(depositAccountId = id) } }
                } else {
                    FieldLabel("支付方式")
                    SegmentedChoice(PaymentMethod.entries, c.oneOffMethod, { it.label }, { m -> update { it.copy(oneOffMethod = m) } }, icon = ::methodIcon)
                }
            }

            ChangeKind.STOP -> ItemPicker(items, c.itemIds, e("items"), multi = false) { id -> update { it.copy(itemIds = setOf(id)) } }
        }
    }
}

/** 月份：一列可以左右滑的晶片（本月、26/10、26/11…）。 */
@Composable
private fun MonthRow(today: LocalDate, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        (0..12).forEach { offset ->
            val ym = YearMonth.from(today).plusMonths(offset.toLong())
            val on = selected == offset
            FilterChip(
                selected = on,
                onClick = { onSelect(offset) },
                label = { Text(if (offset == 0) "本月" else "${ym.year % 100}/${ym.monthValue}") },
                leadingIcon = if (on) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

private fun toggle(set: Set<Long>, id: Long): Set<Long> = if (id in set) set - id else set + id

@Composable
private fun ItemPicker(items: List<PlanItem>, selected: Set<Long>, error: String?, multi: Boolean, onToggle: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel(if (multi) "項目（可複選）" else "項目")
        ChoiceChips(items, { it.id in selected }, { it.name }, { onToggle(it.id) })
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun AccountChips(label: String, accounts: List<Account>, selected: Long?, error: String?, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel(label)
        ChoiceChips(accounts, { selected == it.id }, { it.name }, { onSelect(it.id) })
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun DebtChips(debts: List<Account>, selected: Set<Long>, error: String?, onToggle: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel("要清償的負債")
        ChoiceChips(debts, { it.id in selected }, { it.name }, { onToggle(it.id) })
        error?.let { ErrorText(it) }
    }
}
