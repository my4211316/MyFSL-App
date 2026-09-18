package tw.myfsl.app.ui.checkin

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.ConfirmChoice
import tw.myfsl.app.core.domain.DiffKind
import tw.myfsl.app.core.domain.Resolution
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ReportInput
import tw.myfsl.app.ui.theme.StatusColors

/** 本週檢查：到期確認 → 帳戶對帳 → 會寫入什麼。 */
@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onClose: () -> Unit,
    onStep: (CheckInStep) -> Unit,
    onNext: () -> Unit,
    onChoose: (PlanLine, ConfirmChoice) -> Unit,
    onConfirmAmount: (PlanLine, String) -> Unit,
    onReport: (PlanLine, String) -> Unit,
    onBalance: (Long, String) -> Unit,
    onMatch: (Long) -> Unit,
    onResolution: (Long, Resolution) -> Unit,
    onItem: (Long, Long) -> Unit,
    onFinish: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "關閉") }
            Text("本週檢查", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        if (state.done) {
            DoneCard(state.doneLines, onClose, onAgain)
            return
        }

        val index = state.steps.indexOf(state.step)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.steps.forEachIndexed { i, step ->
                FilterChip(
                    selected = step == state.step,
                    onClick = { onStep(step) },
                    label = { Text("${i + 1}/${state.steps.size} ${step.title}") },
                )
            }
        }
        LinearProgressIndicator(
            progress = { (index + 1f) / state.steps.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                CheckInStep.CONFIRM -> ConfirmStep(state, onChoose, onConfirmAmount, onReport)
                CheckInStep.RECONCILE -> ReconcileStep(state, onBalance, onMatch, onResolution, onItem)
                CheckInStep.REVIEW -> ReviewStep(state)
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.step != CheckInStep.REVIEW) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("先不做") }
                Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("下一步") }
            } else {
                val empty = state.result?.isEmpty != false
                Button(onClick = onFinish, enabled = !empty && !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (empty) "沒有要寫入的東西" else if (state.saving) "寫入中…" else "完成檢查")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmStep(
    state: CheckInUiState,
    onChoose: (PlanLine, ConfirmChoice) -> Unit,
    onAmount: (PlanLine, String) -> Unit,
    onReport: (PlanLine, String) -> Unit,
) {
    if (state.confirms.isEmpty()) {
        Hint("本月沒有要確認的到期項目。")
    }
    state.confirms.forEach { row ->
        RowCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("計畫 " + MoneyFormat.currency(row.line.planned), style = MaterialTheme.typography.bodyMedium)
            }
            if (row.line.recorded > 0) Hint("本月已記 ${MoneyFormat.currency(row.line.recorded)}，只會補差額")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfirmChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = row.choice == choice,
                        onClick = { onChoose(row.line.line, choice) },
                        label = { Text(choice.labelFor(row.line.item.type)) },
                    )
                }
            }
            if (row.choice == ConfirmChoice.DIFFERENT_AMOUNT) {
                MoneyField("實際金額", row.amountText, if (row.amountError) "請輸入實際金額" else null) { onAmount(row.line.line, it) }
            }
        }
    }

    if (state.reports.isNotEmpty()) {
        Text("每週回報", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        state.reports.forEach { row ->
            RowCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("計畫 " + MoneyFormat.currency(row.line.planned), style = MaterialTheme.typography.bodyMedium)
                }
                val label = if (row.line.input == ReportInput.REMAINING) "還剩多少" else "本月累計花費"
                MoneyField(label, row.text, null, placeholder = row.line.prefill.toString()) { onReport(row.line.line, it) }
                Hint("已記帳 ${MoneyFormat.currency(row.line.recorded)}；沒填就不回報")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReconcileStep(
    state: CheckInUiState,
    onBalance: (Long, String) -> Unit,
    onMatch: (Long) -> Unit,
    onResolution: (Long, Resolution) -> Unit,
    onItem: (Long, Long) -> Unit,
) {
    Hint("數一下錢包、看銀行 App 的餘額和各張卡的欠款，填進來。沒填的帳戶會跳過。")
    if (state.reconciles.isEmpty()) Hint("還沒有帳戶，先到「帳戶」新增。")
    state.reconciles.forEach { rec ->
        val row = rec.row
        RowCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text((if (row.isCard) "推算欠款 " else "推算 ") + MoneyFormat.currency(row.computed), style = MaterialTheme.typography.bodyMedium)
            }
            row.accounts.forEach { account ->
                MoneyField(if (row.isCard) "${account.name} 欠款" else "實際餘額", rec.inputs[account.id].orEmpty(), null) { onBalance(account.id, it) }
            }
            if (row.isCard && row.accounts.size > 1) Hint("每一張卡都要填，少一張就整列跳過")
            AssistChip(onClick = { onMatch(row.id) }, label = { Text("和推算相符") })

            if (rec.kind != null && rec.message != null) {
                val warn = rec.kind == DiffKind.MISSED || rec.kind == DiffKind.OVER_RECORDED
                Text(
                    rec.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) StatusColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rec.kind != DiffKind.MATCHED) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Resolution.entries.forEach { resolution ->
                            FilterChip(
                                selected = rec.resolution == resolution,
                                onClick = { onResolution(row.id, resolution) },
                                label = { Text(resolution.label) },
                            )
                        }
                    }
                    if (rec.resolution == Resolution.ASSIGN_ITEM) {
                        ItemChips(state.itemChoices, rec.item) { onItem(row.id, it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemChips(items: List<PlanItem>, selected: PlanItem?, onSelect: (Long) -> Unit) {
    Text("差額歸到", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = selected?.id == item.id, onClick = { onSelect(item.id) }, label = { Text(item.name) })
        }
    }
}

@Composable
private fun ReviewStep(state: CheckInUiState) {
    if (state.summaryLines.isEmpty()) {
        Hint("目前沒有任何要寫入的東西。回到前面填帳戶金額或選到期項目。")
        return
    }
    Text("完成後會寫入：", style = MaterialTheme.typography.titleSmall)
    RowCard {
        state.summaryLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    Hint("記帳與校正餘額用同一個時間寫入，餘額不會重複扣。")
}

@Composable
private fun DoneCard(lines: List<String>, onClose: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("本週檢查完成", style = MaterialTheme.typography.titleMedium)
        }
        RowCard { lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("再檢查一次") }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("完成") }
        }
    }
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun MoneyField(label: String, value: String, error: String?, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
