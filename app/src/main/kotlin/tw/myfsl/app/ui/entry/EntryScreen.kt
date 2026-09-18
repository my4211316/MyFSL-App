package tw.myfsl.app.ui.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.ui.theme.StatusColors

/** 記帳：開啟 App 的第一個畫面。先選項目，再選支付方式，最後輸入金額。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(
    state: EntryUiState,
    onSelectType: (FlowType) -> Unit,
    onSelectItem: (Long) -> Unit,
    onSelectMethod: (PaymentMethod) -> Unit,
    onSelectCard: (Long?) -> Unit,
    onToggleNote: (String) -> Unit,
    onPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onToggleShowAll: () -> Unit,
    onToggleInstallment: () -> Unit,
    onInstallmentMonths: (Int) -> Unit,
    onInstallmentFee: (InstallmentFee) -> Unit,
    onInstallmentFeeValue: (String) -> Unit,
    onOpenRecords: () -> Unit,
    onLoadSample: () -> Unit,
    onToggleRefund: () -> Unit,
    onAnswerMissed: (Boolean) -> Unit,
    onCancelMissed: () -> Unit,
    onOpenDue: (String) -> Unit,
    onCloseDue: () -> Unit,
    onDueAmount: (String) -> Unit,
    onDueMethod: (PaymentMethod) -> Unit,
    onDueCard: (Long?) -> Unit,
    onDueAccount: (Long) -> Unit,
    onDueUseDueDate: (Boolean) -> Unit,
    onRecordDue: () -> Unit,
    onSkipDue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.dueDialog?.let { dialog ->
        DueRecordDialog(dialog, onCloseDue, onDueAmount, onDueMethod, onDueCard, onDueAccount, onDueUseDueDate, onRecordDue, onSkipDue)
    }
    state.missedPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onCancelMissed,
            title = { Text("是之前漏記的那筆嗎？") },
            text = { Text(prompt + "\n\n選「是」會用這筆明細取代漏記差額，項目花費和帳戶餘額都不會重複算。") },
            confirmButton = { TextButton(onClick = { onAnswerMissed(true) }) { Text("是，換成這筆") } },
            dismissButton = { TextButton(onClick = { onAnswerMissed(false) }) { Text("不是，另外一筆") } },
        )
    }
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.empty) {
        EmptyState(onLoadSample, modifier)
        return
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("記一筆", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val types = listOf(FlowType.EXPENSE, FlowType.INCOME, FlowType.TRANSFER)
            SingleChoiceSegmentedButtonRow {
                types.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { onSelectType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, types.size),
                        label = { Text(type.label, style = MaterialTheme.typography.labelLarge) },
                        icon = {},
                    )
                }
            }
            IconButton(onClick = onOpenRecords) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "看紀錄")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.todayStrip, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                state.groupAllowance?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.dues.isNotEmpty()) {
            DueStrip(state.dues, onOpenDue)
        }

        Spacer(Modifier.height(8.dp))
        Text(state.selectedLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                state.amountText,
                fontSize = 44.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.amount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            state.hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.hintWarning) StatusColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        LabeledRow("項目") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.items.forEach { item ->
                    FilterChip(
                        selected = item.id == state.selectedItemId,
                        onClick = { onSelectItem(item.id) },
                        label = { Text(item.name) },
                    )
                }
                if (state.moreCount > 0 || state.showAll) {
                    FilterChip(
                        selected = false,
                        onClick = onToggleShowAll,
                        label = { Text(if (state.showAll) "收起" else "更多 " + state.moreCount) },
                    )
                }
            }
        }

        if (state.showMethods) {
            LabeledRow("付款") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        val planned = method in state.plannedMethods
                        FilterChip(
                            selected = state.method == method,
                            onClick = { onSelectMethod(method) },
                            label = {
                                Text(
                                    method.label,
                                    color = if (planned || state.method == method) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                )
                            },
                        )
                    }
                    if (state.showRefund) {
                        FilterChip(
                            selected = state.refund,
                            onClick = onToggleRefund,
                            label = { Text("退款") },
                        )
                    }
                }
            }
        }

        if (state.showCards) {
            LabeledRow("卡片") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.cardId == null,
                            onClick = { onSelectCard(null) },
                            label = { Text("不指定") },
                        )
                    }
                    items(state.cards.size) { index ->
                        val card = state.cards[index]
                        FilterChip(
                            selected = state.cardId == card.id,
                            onClick = { onSelectCard(card.id) },
                            label = { Text(card.name) },
                        )
                    }
                }
            }
        }

        if (state.showInstallment) {
            LabeledRow("分期") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = state.installmentOn,
                                onClick = onToggleInstallment,
                                label = { Text(if (state.installmentOn) "分期" else "一次付清") },
                            )
                        }
                        if (state.installmentOn) {
                            items(INSTALLMENT_MONTHS.size) { index ->
                                val months = INSTALLMENT_MONTHS[index]
                                FilterChip(
                                    selected = state.installmentMonths == months,
                                    onClick = { onInstallmentMonths(months) },
                                    label = { Text("$months 期") },
                                )
                            }
                        }
                    }
                    if (state.installmentOn) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(InstallmentFee.entries.size) { index ->
                                val fee = InstallmentFee.entries[index]
                                FilterChip(
                                    selected = state.installmentFee == fee,
                                    onClick = { onInstallmentFee(fee) },
                                    label = { Text(fee.label) },
                                )
                            }
                        }
                        if (state.installmentFee != InstallmentFee.NONE) {
                            OutlinedTextField(
                                value = state.installmentFeeValue,
                                onValueChange = onInstallmentFeeValue,
                                label = { Text(if (state.installmentFee == InstallmentFee.PER_PERIOD) "每期手續費（元）" else "費率（%）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        state.installmentDescription?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (state.noteSuggestions.isNotEmpty()) {
            LabeledRow("備註") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.noteSuggestions.size) { index ->
                        val note = state.noteSuggestions[index]
                        FilterChip(
                            selected = state.note == note,
                            onClick = { onToggleNote(note) },
                            label = { Text(note) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Keypad(onPress = onPress, onBackspace = onBackspace)

        Button(
            onClick = onSave,
            enabled = state.saveEnabled,
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
        ) {
            Text(state.saveLabel, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val INSTALLMENT_MONTHS = listOf(3, 6, 12, 18, 24, 30)

/** 本月到期（R-DUE）：還沒記下的每月固定帳單、貸款、卡費、分期；點一下記下。 */
@Composable
private fun DueStrip(dues: List<DueRow>, onOpen: (String) -> Unit) {
    val reached = dues.count { it.reached }
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("本月到期", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            if (reached > 0) "$reached 筆已到期，點一下記下" else "點一下記下",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(dues.size) { index ->
            val due = dues[index]
            Surface(
                onClick = { onOpen(due.key) },
                shape = MaterialTheme.shapes.small,
                color = if (due.reached) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (due.overdue) BorderStroke(1.dp, StatusColors.warningText) else null,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(due.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(
                        "${due.dateLabel} · ${due.amountText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (due.overdue) StatusColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 記下到期項目：金額帶好，可以改；支出選支付方式（刷卡再選卡），轉帳、貸款、卡費選扣款帳戶，收入選入帳帳戶。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueRecordDialog(
    dialog: DueDialog,
    onClose: () -> Unit,
    onAmount: (String) -> Unit,
    onMethod: (PaymentMethod) -> Unit,
    onCard: (Long?) -> Unit,
    onAccount: (Long) -> Unit,
    onUseDueDate: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(dialog.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dialog.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dialog.amountEditable) {
                    OutlinedTextField(
                        value = dialog.amountText,
                        onValueChange = onAmount,
                        label = { Text("金額") },
                        prefix = { Text("$") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("金額 ${dialog.amountText}", style = MaterialTheme.typography.titleMedium)
                }
                if (dialog.choosesMethod) {
                    Text("支付方式", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentMethod.entries.forEach { method ->
                            FilterChip(
                                selected = dialog.method == method,
                                onClick = { onMethod(method) },
                                label = { Text(method.label) },
                            )
                        }
                    }
                    val planned = dialog.plannedMethod
                    if (planned != null && dialog.method != null && dialog.method != planned) {
                        Text(
                            "計畫是${planned.label}，這次用${dialog.method.label}；一樣算在這個項目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (dialog.showCards) {
                    Text("卡片", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = dialog.cardId == null, onClick = { onCard(null) }, label = { Text("不指定") })
                        dialog.cards.forEach { card ->
                            FilterChip(selected = dialog.cardId == card.id, onClick = { onCard(card.id) }, label = { Text(card.name) })
                        }
                    }
                }
                if (dialog.choosesAccount) {
                    Text(dialog.accountLabel, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dialog.accounts.forEach { account ->
                            FilterChip(selected = dialog.accountId == account.id, onClick = { onAccount(account.id) }, label = { Text(account.name) })
                        }
                    }
                }
                dialog.dueDateLabel?.let { dueLabel ->
                    Text("付款日", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !dialog.useDueDate, onClick = { onUseDueDate(false) }, label = { Text("今天") })
                        FilterChip(selected = dialog.useDueDate, onClick = { onUseDueDate(true) }, label = { Text(dueLabel) })
                    }
                }
                dialog.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onRecord) { Text(dialog.recordLabel) } },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("這個月沒有") }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Keypad(onPress: (String) -> Unit, onBackspace: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "back")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    OutlinedButton(
                        onClick = { if (key == "back") onBackspace() else onPress(key) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        if (key == "back") {
                            Icon(Icons.Default.Backspace, contentDescription = "刪除一位")
                        } else {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onLoadSample: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("還沒有帳戶與項目", style = MaterialTheme.typography.titleMedium)
                Text(
                    "記帳需要先有帳戶（錢包、銀行、信用卡）和預算項目。\n" +
                        "可以到「帳戶」頁新增，或在「計畫」頁匯入年度計畫 CSV。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLoadSample, modifier = Modifier.fillMaxWidth()) {
                    Text("載入示意資料試用")
                }
                Text(
                    "示意資料是虛構的家庭帳，方便先試操作；之後可以在設定清空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
