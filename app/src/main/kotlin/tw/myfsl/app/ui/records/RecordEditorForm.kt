package tw.myfsl.app.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.RecordDraft
import tw.myfsl.app.core.domain.RecordEditForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.ui.plan.MethodDot
import tw.myfsl.app.ui.theme.StatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 修改一筆記帳：日期、金額、項目、付款方式、卡片、備註。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier,
) {
    val draft = editor.draft
    val errors = editor.errors
    val original = draft.original
    var picking by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("修改${original.type.label}", style = MaterialTheme.typography.titleLarge)
        when {
            original.source == EntrySource.MISSED -> Hint("這筆是本週檢查的對帳差額，負數代表多記。")
            original.source == EntrySource.CONFIRMED -> Hint("這筆是到期確認的補記。")
            draft.isInstallment -> Hint("這筆是分期消費：只能改日期、項目和備註。要改金額或期數請刪掉重記。")
        }

        Label("日期")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L to "今天", 1L to "昨天", 2L to "前天").forEach { (days, label) ->
                val date = today.minusDays(days)
                FilterChip(selected = draft.date == date, onClick = { onChange { it.copy(date = date) } }, label = { Text(label) })
            }
            val other = draft.date !in (0L..2L).map { today.minusDays(it) }
            FilterChip(
                selected = other,
                onClick = { picking = true },
                label = { Text(if (other) "${draft.date.monthValue}/${draft.date.dayOfMonth}" else "其他日期") },
            )
        }
        errors[Field.DATE]?.let { Error(it) }

        OutlinedTextField(
            value = draft.amount,
            onValueChange = { v -> onChange { it.copy(amount = v.filter { c -> c.isDigit() || c == '-' || c == ',' }) } },
            label = { Text("金額") },
            isError = errors[Field.AMOUNT] != null,
            supportingText = errors[Field.AMOUNT]?.let { { Text(it) } },
            enabled = !draft.isInstallment,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (draft.allowsNegative) KeyboardType.Text else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Label("項目")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.filter { it.type == original.type }.forEach { item ->
                FilterChip(selected = draft.itemId == item.id, onClick = { onChange { it.copy(itemId = item.id) } }, label = { Text(item.name) })
            }
        }
        errors[Field.ITEM]?.let { Error(it) }

        if (original.type == FlowType.EXPENSE && original.method != null) {
            Label("付款方式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.entries.forEach { method ->
                    FilterChip(
                        selected = draft.method == method,
                        onClick = { onChange { it.copy(method = method) } },
                        enabled = !draft.isInstallment,
                        label = { Text(method.label) },
                        leadingIcon = { MethodDot(method) },
                    )
                }
            }
            errors[Field.METHOD]?.let { Error(it) }

            if (draft.method == PaymentMethod.CREDIT_CARD && pickCard && cards.isNotEmpty()) {
                Label("卡片")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.cardId == null, onClick = { onChange { it.copy(cardId = null) } }, label = { Text("不指定") })
                    cards.forEach { card ->
                        FilterChip(selected = draft.cardId == card.id, onClick = { onChange { it.copy(cardId = card.id) } }, label = { Text(card.name) })
                    }
                }
                errors[Field.CARD]?.let { Error(it) }
            }
        }

        OutlinedTextField(
            value = draft.note,
            onValueChange = { v -> onChange { it.copy(note = v) } },
            label = { Text("備註") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        editor.warnings.filter { it != "沒有修改任何東西" }.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warningText)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("儲存") }
        }
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

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Error(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
