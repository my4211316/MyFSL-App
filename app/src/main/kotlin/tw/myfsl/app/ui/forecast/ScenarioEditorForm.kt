package tw.myfsl.app.ui.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import java.time.LocalDate
import java.time.YearMonth

/** 情境編輯：名稱＋一串變動。 */
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
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editor.isNew) "新增情境" else "編輯情境", style = MaterialTheme.typography.titleLarge)
        Input("情境名稱", draft.name, errors[Field.NAME]) { v -> onDraft { it.copy(name = v) } }
        Input("備註（選填）", draft.note, null) { v -> onDraft { it.copy(note = v) } }

        Text("變動", style = MaterialTheme.typography.titleSmall)
        errors[Field.CHANGES]?.let { Error(it) }
        draft.changes.forEachIndexed { index, change ->
            ChangeCard(index, change, today, accounts, expenseItems, errors, { onChange(index, it) }, { onRemoveChange(index) })
        }

        Text("加一個變動", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChangeKind.entries.forEach { kind ->
                AssistChip(
                    onClick = { onAddChange(kind) },
                    label = { Text(kind.label) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }
        }

        if (errors.isNotEmpty()) Error("有 ${errors.size} 個欄位要修正")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("儲存") }
        }
        if (!editor.isNew) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("刪除這個情境", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "刪除這個變動") }
            }

            Label(if (c.kind == ChangeKind.ONE_OFF || c.kind == ChangeKind.PAYOFF || c.kind == ChangeKind.LOAN || c.kind == ChangeKind.CONSOLIDATE) "月份" else "從哪個月開始")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..12).forEach { offset ->
                    val ym = YearMonth.from(today).plusMonths(offset.toLong())
                    FilterChip(
                        selected = c.monthOffset == offset,
                        onClick = { update { it.copy(monthOffset = offset) } },
                        label = { Text(if (offset == 0) "本月" else "${ym.year % 100}/${ym.monthValue}") },
                    )
                }
            }

            when (c.kind) {
                ChangeKind.CUT -> {
                    Input("調整百分比（減少 10% 填 -10）", c.percent, e("percent"), number = true) { v -> update { it.copy(percent = v) } }
                    ItemPicker(items, c.itemIds, e("items"), multi = true) { id -> update { it.copy(itemIds = toggle(it.itemIds, id)) } }
                }

                ChangeKind.CONSOLIDATE, ChangeKind.LOAN -> {
                    Input("貸款名稱", c.name, null) { v -> update { it.copy(name = v) } }
                    Input("貸款金額", c.amount, e("amount"), number = true) { v -> update { it.copy(amount = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Input("年利率（%）", c.rate, e("rate"), number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(rate = v) } }
                        Input("期數（月）", c.months, e("months"), number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(months = v) } }
                    }
                    Label("攤還方式")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepaymentMethod.entries.forEach { m -> FilterChip(selected = c.repayment == m, onClick = { update { it.copy(repayment = m) } }, label = { Text(m.label) }) }
                    }
                    Label("每月繳款時間")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Half.entries.forEach { h -> FilterChip(selected = c.payHalf == h, onClick = { update { it.copy(payHalf = h) } }, label = { Text(h.label) }) }
                    }
                    AccountChips("撥款帳戶", liquid, c.depositAccountId, e("deposit")) { id -> update { it.copy(depositAccountId = id) } }
                    AccountChips("扣款帳戶", liquid, c.payAccountId, e("pay")) { id -> update { it.copy(payAccountId = id) } }
                    if (c.kind == ChangeKind.CONSOLIDATE) {
                        DebtChips(debts, c.debtAccountIds, e("debts")) { id -> update { it.copy(debtAccountIds = toggle(it.debtAccountIds, id)) } }
                        SwitchRow("一併結清未到期分期", c.includeInstallments) { v -> update { it.copy(includeInstallments = v) } }
                        SwitchRow("停止原本繳這些負債的計畫", c.stopScheduledPayments) { v -> update { it.copy(stopScheduledPayments = v) } }
                    }
                }

                ChangeKind.PAYOFF -> {
                    AccountChips("付款帳戶", liquid, c.payAccountId, e("pay")) { id -> update { it.copy(payAccountId = id) } }
                    DebtChips(debts, c.debtAccountIds, e("debts")) { id -> update { it.copy(debtAccountIds = toggle(it.debtAccountIds, id)) } }
                    SwitchRow("一併結清未到期分期", c.includeInstallments) { v -> update { it.copy(includeInstallments = v) } }
                    SwitchRow("停止原本繳這些負債的計畫", c.stopScheduledPayments) { v -> update { it.copy(stopScheduledPayments = v) } }
                }

                ChangeKind.METHOD -> {
                    Label("原本")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentMethod.entries.forEach { m -> FilterChip(selected = c.fromMethod == m, onClick = { update { it.copy(fromMethod = m) } }, label = { Text(m.label) }) }
                    }
                    Label("改成")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentMethod.entries.forEach { m -> FilterChip(selected = c.toMethod == m, onClick = { update { it.copy(toMethod = m) } }, label = { Text(m.label) }) }
                    }
                    e("method")?.let { Error(it) }
                    ItemPicker(items, c.itemIds, e("items"), multi = true) { id -> update { it.copy(itemIds = toggle(it.itemIds, id)) } }
                }

                ChangeKind.ONE_OFF -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(FlowType.EXPENSE, FlowType.INCOME).forEach { t ->
                            FilterChip(selected = c.oneOffType == t, onClick = { update { it.copy(oneOffType = t) } }, label = { Text(t.label) })
                        }
                    }
                    Input("名稱", c.name, e("name")) { v -> update { it.copy(name = v) } }
                    Input("金額", c.amount, e("amount"), number = true) { v -> update { it.copy(amount = v) } }
                    if (c.oneOffType == FlowType.INCOME) {
                        AccountChips("入帳帳戶", liquid, c.depositAccountId, e("deposit")) { id -> update { it.copy(depositAccountId = id) } }
                    } else {
                        Label("支付方式")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaymentMethod.entries.forEach { m -> FilterChip(selected = c.oneOffMethod == m, onClick = { update { it.copy(oneOffMethod = m) } }, label = { Text(m.label) }) }
                        }
                    }
                }

                ChangeKind.STOP -> ItemPicker(items, c.itemIds, e("items"), multi = false) { id -> update { it.copy(itemIds = setOf(id)) } }
            }
        }
    }
}

private fun toggle(set: Set<Long>, id: Long): Set<Long> = if (id in set) set - id else set + id

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemPicker(items: List<PlanItem>, selected: Set<Long>, error: String?, multi: Boolean, onToggle: (Long) -> Unit) {
    Label(if (multi) "項目（可複選）" else "項目")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item -> FilterChip(selected = item.id in selected, onClick = { onToggle(item.id) }, label = { Text(item.name) }) }
    }
    error?.let { Error(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(label: String, accounts: List<Account>, selected: Long?, error: String?, onSelect: (Long) -> Unit) {
    Label(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        accounts.forEach { a -> FilterChip(selected = selected == a.id, onClick = { onSelect(a.id) }, label = { Text(a.name) }) }
    }
    error?.let { Error(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebtChips(debts: List<Account>, selected: Set<Long>, error: String?, onToggle: (Long) -> Unit) {
    Label("要清償的負債")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        debts.forEach { a -> FilterChip(selected = a.id in selected, onClick = { onToggle(a.id) }, label = { Text(a.name) }) }
    }
    error?.let { Error(it) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Error(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

@Composable
private fun Input(label: String, value: String, error: String?, number: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier,
    )
}
