package tw.myfsl.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.PlanItemDraft
import tw.myfsl.app.core.domain.PlanItemForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import tw.myfsl.app.ui.theme.MethodColors
import tw.myfsl.app.ui.theme.StatusColors

fun PaymentMethod?.color(): Color = when (this) {
    PaymentMethod.CREDIT_CARD -> MethodColors.card
    PaymentMethod.CASH -> MethodColors.cash
    PaymentMethod.TRANSFER -> MethodColors.transfer
    null -> Color.Transparent
}

@Composable
fun MethodDot(method: PaymentMethod?, modifier: Modifier = Modifier) {
    if (method == null) return
    Box(modifier.size(10.dp).background(method.color(), CircleShape))
}

/** 計畫項目的新增／編輯。每一列支付方式由使用者自己選。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanItemEditorForm(
    editor: ItemEditor,
    year: Int,
    groups: List<PlanGroup>,
    accounts: List<Account>,
    onChange: ((PlanItemDraft) -> PlanItemDraft) -> Unit,
    onType: (FlowType) -> Unit,
    onAddLine: (PaymentMethod) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onMethod: (Int, PaymentMethod) -> Unit,
    onMonth: (Int, Int, String) -> Unit,
    onToggleLine: (Int) -> Unit,
    onQuickFill: (Int, QuickFill, String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = editor.draft
    val errors = editor.errors
    var addingGroup by remember(editor.isNew) { mutableStateOf(draft.groupId == null && draft.newGroupName.isNotEmpty()) }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editor.isNew) "新增項目" else "編輯項目", style = MaterialTheme.typography.titleLarge)
        Text("金額存到 $year 年", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Label("類型")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowType.entries.forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    onClick = { onType(type) },
                    enabled = !editor.typeLocked || draft.type == type,
                    label = { Text(type.label) },
                )
            }
        }
        if (editor.typeLocked) {
            Text("已經有記帳，類型不能改；要改請新增一個項目。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        errors[Field.TYPE]?.let { ErrorText(it) }

        Input("項目名稱", draft.name, errors[Field.NAME]) { v -> onChange { it.copy(name = v) } }

        Label("群組")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                FilterChip(
                    selected = !addingGroup && draft.groupId == group.id,
                    onClick = { addingGroup = false; onChange { it.copy(groupId = group.id, newGroupName = "") } },
                    label = { Text(group.name) },
                )
            }
            FilterChip(
                selected = addingGroup,
                onClick = { addingGroup = true; onChange { it.copy(groupId = null) } },
                label = { Text("＋ 新群組") },
            )
        }
        if (addingGroup) {
            Input("新群組名稱", draft.newGroupName, null) { v -> onChange { it.copy(newGroupName = v, groupId = null) } }
        }
        errors[Field.GROUP]?.let { ErrorText(it) }

        when (draft.type) {
            FlowType.INCOME -> AccountPicker("入帳帳戶", draft.accountId, accounts.filter { it.kind.isLiquid }, errors[Field.ACCOUNT]) { id ->
                onChange { it.copy(accountId = id) }
            }

            FlowType.TRANSFER -> {
                AccountPicker("轉出帳戶", draft.accountId, accounts, errors[Field.ACCOUNT]) { id -> onChange { it.copy(accountId = id) } }
                AccountPicker("轉入帳戶（例如繳卡費時選信用卡）", draft.toAccountId, accounts, errors[Field.TO_ACCOUNT]) { id ->
                    onChange { it.copy(toAccountId = id) }
                }
                val target = accounts.firstOrNull { it.id == draft.toAccountId }
                if (target != null && (target.card != null || target.loan != null)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("額外還款", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "「${target.name}」已依合約自動繳款。一般月繳不用再列；只有多還的部分才打開這個開關。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = draft.extraRepayment, onCheckedChange = { v -> onChange { it.copy(extraRepayment = v) } })
                    }
                }
            }

            FlowType.EXPENSE -> Unit
        }

        Label("時點")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Timing.entries.forEach { timing ->
                FilterChip(selected = draft.timing == timing, onClick = { onChange { it.copy(timing = timing) } }, label = { Text(timing.label) })
            }
        }
        OutlinedTextField(
            value = draft.dueDay,
            onValueChange = { v -> onChange { it.copy(dueDay = v.filter(Char::isDigit).take(2)) } },
            label = { Text("每月幾號（選填）") },
            supportingText = {
                Text(errors[Field.DUE_DAY] ?: "填了就以這天為準：每月固定的項目這天出現在「本月到期」，試算也放在這天。短月份取月底。")
            },
            isError = errors[Field.DUE_DAY] != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Label("固定或可調")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flexibility.entries.forEach { f ->
                FilterChip(selected = draft.flexibility == f, onClick = { onChange { it.copy(flexibility = f) } }, label = { Text(f.label) })
            }
        }
        Label("追蹤方式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackingMode.entries.forEach { mode ->
                FilterChip(selected = draft.tracking == mode, onClick = { onChange { it.copy(tracking = mode) } }, label = { Text(mode.label) })
            }
        }
        Text(draft.tracking.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- 金額列 ----
        Text(
            if (draft.type == FlowType.EXPENSE) "每月金額（依支付方式分列）" else "每月金額",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (draft.type == FlowType.EXPENSE) {
            Text(
                "同一個項目可以同時有現金列和信用卡列；某幾個月刷卡、其他月付現，就在各列填該月的金額。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errors[Field.LINES]?.let { ErrorText(it) }

        draft.lines.forEachIndexed { index, line ->
            LineCard(
                index = index,
                draft = draft,
                expanded = editor.expandedLine == index,
                errors = errors,
                onToggle = { onToggleLine(index) },
                onMethod = { onMethod(index, it) },
                onRemove = { onRemoveLine(index) },
                onMonth = { month, value -> onMonth(index, month, value) },
                onQuickFill = { kind, amount -> onQuickFill(index, kind, amount) },
            )
        }
        if (draft.type == FlowType.EXPENSE && draft.unusedMethods.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.unusedMethods.forEach { method ->
                    AssistChip(
                        onClick = { onAddLine(method) },
                        label = { Text("加${method.label}列") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        Input("備註（選填）", draft.note, null) { v -> onChange { it.copy(note = v) } }

        editor.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warningText) }
        if (errors.isNotEmpty()) ErrorText("有 ${errors.size} 個欄位要修正")

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("儲存") }
        }
        if (!editor.isNew) {
            TextButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                Text("封存這個項目", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineCard(
    index: Int,
    draft: PlanItemDraft,
    expanded: Boolean,
    errors: Map<String, String>,
    onToggle: () -> Unit,
    onMethod: (PaymentMethod) -> Unit,
    onRemove: () -> Unit,
    onMonth: (Int, String) -> Unit,
    onQuickFill: (QuickFill, String) -> Unit,
) {
    val line = draft.lines[index]
    val hasError = errors.keys.any { it.startsWith("line$index-") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodDot(line.method, Modifier.padding(end = 8.dp))
                Text(
                    line.method?.label ?: draft.type.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasError) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                Text(
                    "  全年 " + MoneyFormat.currency(draft.lineTotal(index)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (draft.lines.size > 1) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "刪除這一列") }
                }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "收起" else "展開")
                }
            }

            if (draft.type == FlowType.EXPENSE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = line.method == method,
                            onClick = { onMethod(method) },
                            label = { Text(method.label) },
                            leadingIcon = { MethodDot(method) },
                        )
                    }
                }
                errors[Field.method(index)]?.let { ErrorText(it) }
            }

            if (expanded) {
                var quick by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quick,
                        onValueChange = { quick = it },
                        label = { Text("快速填入金額") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickFill.entries.forEach { kind ->
                        AssistChip(onClick = { onQuickFill(kind, quick) }, label = { Text(kind.label) })
                    }
                }
                (0 until 12).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            val key = Field.month(index, m + 1)
                            OutlinedTextField(
                                value = line.months[m],
                                onValueChange = { onMonth(m + 1, it) },
                                label = { Text("${m + 1} 月") },
                                isError = errors[key] != null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                errors.filterKeys { it.startsWith("line$index-month") }.values.forEach { ErrorText(it) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountPicker(label: String, selected: Long?, accounts: List<Account>, error: String?, onSelect: (Long) -> Unit) {
    Label(label)
    if (accounts.isEmpty()) {
        Text("還沒有帳戶，先到「帳戶」新增", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account ->
            FilterChip(selected = selected == account.id, onClick = { onSelect(account.id) }, label = { Text(account.name) })
        }
    }
    error?.let { ErrorText(it) }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun ErrorText(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

@Composable
private fun Input(label: String, value: String, error: String?, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
