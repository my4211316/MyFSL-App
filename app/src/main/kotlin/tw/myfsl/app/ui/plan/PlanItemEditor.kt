package tw.myfsl.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.PlanItemDraft
import tw.myfsl.app.core.domain.PlanItemForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.TrackingMode
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.FormScaffold
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.MethodIcon
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.components.methodIcon
import tw.myfsl.app.ui.theme.Spacing

/** 計畫項目的新增／編輯（設計稿：全畫面改版 v1）。每一列支付方式由使用者自己選。 */
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

    FormScaffold(
        title = if (editor.isNew) "新增項目" else "編輯項目",
        subtitle = "金額存到 $year 年",
        onClose = onCancel,
        onSave = onSave,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("類型")
            if (editor.typeLocked) {
                Text(draft.type.label, style = MaterialTheme.typography.titleSmall)
                HintText("已經有記帳，類型不能改；要改請新增一個項目。")
            } else {
                SegmentedChoice(FlowType.entries, draft.type, { it.label }, onType)
            }
            errors[Field.TYPE]?.let { ErrorText(it) }
        }

        TextInput("項目名稱", draft.name, { v -> onChange { it.copy(name = v) } }, error = errors[Field.NAME])

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("群組")
            val options: List<PlanGroup?> = groups + null
            ChoiceChips(
                options,
                { g -> if (g == null) addingGroup else !addingGroup && draft.groupId == g.id },
                { it?.name ?: "＋ 新群組" },
                { g ->
                    if (g == null) {
                        addingGroup = true; onChange { it.copy(groupId = null) }
                    } else {
                        addingGroup = false; onChange { it.copy(groupId = g.id, newGroupName = "") }
                    }
                },
            )
            if (addingGroup) TextInput("新群組名稱", draft.newGroupName, { v -> onChange { it.copy(newGroupName = v, groupId = null) } })
            errors[Field.GROUP]?.let { ErrorText(it) }
        }

        when (draft.type) {
            FlowType.INCOME -> AccountChoice("入帳帳戶", draft.accountId, accounts.filter { it.kind.isLiquid }, errors[Field.ACCOUNT]) { id -> onChange { it.copy(accountId = id) } }
            FlowType.TRANSFER -> {
                AccountChoice("轉出帳戶", draft.accountId, accounts, errors[Field.ACCOUNT]) { id -> onChange { it.copy(accountId = id) } }
                AccountChoice("轉入帳戶（例如繳卡費時選信用卡）", draft.toAccountId, accounts, errors[Field.TO_ACCOUNT]) { id -> onChange { it.copy(toAccountId = id) } }
                val target = accounts.firstOrNull { it.id == draft.toAccountId }
                if (target != null && (target.card != null || target.loan != null)) {
                    SwitchRow(
                        "額外還款",
                        draft.extraRepayment,
                        { v -> onChange { it.copy(extraRepayment = v) } },
                        detail = "「${target.name}」已依合約自動繳款。一般月繳不用再列；只有多還的部分才打開。",
                    )
                }
            }
            FlowType.EXPENSE -> Unit
        }

        TextInput(
            "每月幾號（選填）",
            draft.dueDay,
            { v -> onChange { it.copy(dueDay = v.filter(Char::isDigit).take(2)) } },
            error = errors[Field.DUE_DAY],
            supporting = "填了才提醒：這天出現在「本月到期」，到期前 7／3 天提醒，短月份取月底。" +
                "不填就是沒有固定哪一天：整個月都列在「本月到期」，可以點一下付掉，或用記帳一點一點花。",
            number = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("固定或可調")
            SegmentedChoice(Flexibility.entries, draft.flexibility, { it.label }, { f -> onChange { it.copy(flexibility = f) } })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("追蹤方式")
            ChoiceChips(TrackingMode.entries, { draft.tracking == it }, { it.label }, { m -> onChange { it.copy(tracking = m) } })
            HintText(draft.tracking.hint)
        }

        SectionHeader(if (draft.type == FlowType.EXPENSE) "每月金額（依支付方式分列）" else "每月金額")
        if (draft.type == FlowType.EXPENSE) {
            HintText("同一個項目可以同時有現金列和信用卡列；某幾個月刷卡、其他月付現，就在各列填該月的金額。")
        }
        errors[Field.LINES]?.let { ErrorText(it) }

        draft.lines.forEachIndexed { index, _ ->
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                draft.unusedMethods.forEach { method ->
                    AssistChip(
                        onClick = { onAddLine(method) },
                        label = { Text("加${method.label}列") },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        TextInput("備註（選填）", draft.note, { v -> onChange { it.copy(note = v) } })

        editor.warnings.forEach { WarningText(it) }
        if (errors.isNotEmpty()) ErrorText("有 ${errors.size} 個欄位要修正")
        if (!editor.isNew) DangerButton("封存這個項目", onClick = onArchive, icon = Icons.Rounded.Archive)
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
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MethodIcon(line.method)
            Column(Modifier.weight(1f)) {
                Text(
                    (line.method?.label ?: draft.type.label) + "列",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasError) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                Text("全年 " + MoneyFormat.currency(draft.lineTotal(index)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (draft.lines.size > 1) {
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = "刪除這一列") }
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "收起每月金額" else "展開每月金額")
            }
        }

        if (draft.type == FlowType.EXPENSE) {
            SegmentedChoice(PaymentMethod.entries, line.method, { it.label }, onMethod, icon = ::methodIcon)
            errors[Field.method(index)]?.let { ErrorText(it) }
        }

        if (expanded) {
            var quick by remember { mutableStateOf("") }
            TextInput("快速填入金額", quick, { quick = it }, number = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                QuickFill.entries.forEach { kind -> AssistChip(onClick = { onQuickFill(kind, quick) }, label = { Text(kind.label) }) }
            }
            (0 until 12).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    row.forEach { m ->
                        TextInput(
                            "${m + 1} 月",
                            line.months[m],
                            { onMonth(m + 1, it) },
                            modifier = Modifier.weight(1f),
                            error = errors[Field.month(index, m + 1)]?.let { "" },
                            number = true,
                        )
                    }
                }
            }
            errors.filterKeys { it.startsWith("line$index-month") }.values.forEach { ErrorText(it) }
        }
    }
}

/** 選一個帳戶（晶片），沒有帳戶時提示先新增。 */
@Composable
private fun AccountChoice(label: String, selected: Long?, accounts: List<Account>, error: String?, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel(label)
        if (accounts.isEmpty()) HintText("還沒有帳戶，先到「帳戶」新增")
        ChoiceChips(accounts, { selected == it.id }, { it.name }, { onSelect(it.id) })
        error?.let { ErrorText(it) }
    }
}
