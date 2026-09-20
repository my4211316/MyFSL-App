package tw.myfsl.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.FormScaffold
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.components.methodIcon
import tw.myfsl.app.ui.theme.Spacing

/** 計畫項目的新增／編輯（設計稿：支付結構改版 v1）。一個項目一組 12 個月金額，支付方式是項目的屬性（R-MIX-01）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanItemEditorForm(
    editor: ItemEditor,
    year: Int,
    groups: List<PlanGroup>,
    accounts: List<Account>,
    onChange: ((PlanItemDraft) -> PlanItemDraft) -> Unit,
    onType: (FlowType) -> Unit,
    onMonth: (Int, String) -> Unit,
    onQuickFill: (QuickFill, String) -> Unit,
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

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("時點")
            ChoiceChips(Timing.entries, { draft.timing == it }, { it.label }, { t -> onChange { it.copy(timing = t) } })
        }
        TextInput(
            "每月幾號（選填）",
            draft.dueDay,
            { v -> onChange { it.copy(dueDay = v.filter(Char::isDigit).take(2)) } },
            error = errors[Field.DUE_DAY],
            supporting = "填了就以這天為準：每月固定的項目這天出現在「本月到期」，試算也放在這天。短月份取月底。",
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

        SectionHeader("每月金額")
        MonthAmounts(draft = draft, errors = errors, onMonth = onMonth, onQuickFill = onQuickFill)

        TextInput("備註（選填）", draft.note, { v -> onChange { it.copy(note = v) } })

        editor.warnings.forEach { WarningText(it) }
        if (errors.isNotEmpty()) ErrorText("有 ${errors.size} 個欄位要修正")
        if (!editor.isNew) DangerButton("封存這個項目", onClick = onArchive, icon = Icons.Rounded.Archive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthAmounts(
    draft: PlanItemDraft,
    errors: Map<String, String>,
    onMonth: (Int, String) -> Unit,
    onQuickFill: (QuickFill, String) -> Unit,
) {
    SectionCard {
        Text(
            "全年 " + MoneyFormat.currency(draft.total),
            style = MaterialTheme.typography.titleSmall,
        )
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
                        draft.months[m],
                        { onMonth(m + 1, it) },
                        modifier = Modifier.weight(1f),
                        error = errors[Field.month(m + 1)]?.let { "" },
                        number = true,
                    )
                }
            }
        }
        errors.filterKeys { it.startsWith("month") }.values.forEach { ErrorText(it) }
    }
}

@Composable
private fun AccountChoice(label: String, selected: Long?, accounts: List<Account>, error: String?, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel(label)
        if (accounts.isEmpty()) HintText("還沒有帳戶，先到「帳戶」新增")
        ChoiceChips(accounts, { selected == it.id }, { it.name }, { onSelect(it.id) })
        error?.let { ErrorText(it) }
    }
}
