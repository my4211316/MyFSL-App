package tw.myfsl.app.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import tw.myfsl.app.core.domain.RecordDraft
import tw.myfsl.app.core.domain.RecordEditForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.FormScaffold
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.MoneyField
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.components.methodIcon
import tw.myfsl.app.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 修改一筆記帳：日期、金額、項目、付款方式、卡片、備註；最後是刪除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditorForm(
    editor: RecordEditor,
    today: LocalDate,
    items: List<PlanItem>,
    cards: List<Account>,
    pickCard: Boolean,
    onChange: ((RecordDraft) -> RecordDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = editor.draft
    val errors = editor.errors
    val original = draft.original
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    FormScaffold(title = "修改${original.type.label}", onClose = onCancel, onSave = onSave, modifier = modifier) {
        when {
            original.source == EntrySource.MISSED -> HintText("這筆是本週檢查的對帳差額，負數代表多記。")
            original.source == EntrySource.CONFIRMED -> HintText("這筆是到期確認的補記。")
            draft.isInstallment -> HintText("這筆是分期消費：只能改日期、項目和備註。要改金額或期數請刪掉重記。")
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("日期")
            val quick = listOf(0L to "今天", 1L to "昨天", 2L to "前天")
            val other = draft.date !in quick.map { today.minusDays(it.first) }
            val options = quick.map { it.second } + (if (other) "${draft.date.monthValue}/${draft.date.dayOfMonth}" else "其他日期")
            ChoiceChips(
                options = options,
                isSelected = { label -> quick.firstOrNull { it.second == label }?.let { draft.date == today.minusDays(it.first) } ?: other },
                label = { it },
                onSelect = { label ->
                    val days = quick.firstOrNull { it.second == label }?.first
                    if (days != null) onChange { it.copy(date = today.minusDays(days)) } else picking = true
                },
            )
            errors[Field.DATE]?.let { ErrorText(it) }
        }

        MoneyField(
            label = "金額",
            value = draft.amount,
            onValueChange = { v -> onChange { it.copy(amount = v.filter { c -> c.isDigit() || c == '-' || c == ',' }) } },
            error = errors[Field.AMOUNT],
            enabled = !draft.isInstallment,
            keyboardType = if (draft.allowsNegative) KeyboardType.Text else KeyboardType.Number,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("項目")
            ChoiceChips(items.filter { it.type == original.type }, { draft.itemId == it.id }, { it.name }, { item -> onChange { it.copy(itemId = item.id) } })
            errors[Field.ITEM]?.let { ErrorText(it) }
        }

        if (original.type == FlowType.EXPENSE && original.method != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FieldLabel("付款方式")
                if (draft.isInstallment) {
                    Text(draft.method?.label.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    SegmentedChoice(PaymentMethod.entries, draft.method, { it.label }, { m -> onChange { it.copy(method = m) } }, icon = ::methodIcon)
                }
                errors[Field.METHOD]?.let { ErrorText(it) }
            }
            if (draft.method == PaymentMethod.CREDIT_CARD && pickCard && cards.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FieldLabel("卡片")
                    ChoiceChips(listOf<Account?>(null) + cards, { draft.cardId == it?.id }, { it?.name ?: "不指定" }, { card -> onChange { it.copy(cardId = card?.id) } })
                    errors[Field.CARD]?.let { ErrorText(it) }
                }
            }
        }

        TextInput("備註", draft.note, { v -> onChange { it.copy(note = v) } })

        editor.warnings.filter { it != "沒有修改任何東西" }.forEach { WarningText(it) }

        DangerButton("刪除這筆", onClick = { confirmDelete = true }, icon = Icons.Rounded.Delete)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除這筆？") },
            text = { Text(deleteExplanation(editor.sourceLabel)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (picking) {
        val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange { it.copy(date = date) }
                    }
                    picking = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}
