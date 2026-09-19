package tw.myfsl.app.ui.accounts

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import tw.myfsl.app.core.domain.AccountDraft
import tw.myfsl.app.core.domain.AccountForm.Field
import tw.myfsl.app.core.domain.CardView
import tw.myfsl.app.core.domain.LoanView
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.ui.theme.StatusColors

/** 帳戶：清單與新增／編輯。細節都是選填。 */
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onAdd: () -> Unit,
    onEdit: (Account) -> Unit,
    onChange: ((AccountDraft) -> AccountDraft) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
    onOpenBill: (Long) -> Unit,
    onBillChange: ((BillEditor) -> BillEditor) -> Unit,
    onBillSave: () -> Unit,
    onBillDelete: () -> Unit,
    onBillClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val editor = state.editor
    if (editor != null) {
        AccountEditorForm(editor, state.payAccounts, onChange, onToggleAdvanced, onSave, onCancel, onArchive, modifier)
        return
    }
    val overview = state.overview ?: return
    state.bill?.let { BillDialog(it, onBillChange, onBillSave, onBillDelete, onBillClose) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("帳戶", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTile("可動用現金", MoneyFormat.currency(overview.liquid), Modifier.weight(1f))
                    SummaryTile("負債合計", MoneyFormat.currency(overview.totalDebt), Modifier.weight(1f))
                }
                if (overview.cardReserve > 0) {
                    Text(
                        "可動用現金裡有 ${MoneyFormat.currency(overview.cardReserve)} 要留著繳卡費（已經刷了、之後要繳的），" +
                            "扣掉後真正可用 ${MoneyFormat.currency(overview.freeCash)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (overview.cards.isNotEmpty() || overview.unassignedCardSpending > 0) {
                    Text(
                        "信用卡總負債 ${MoneyFormat.currency(overview.cardTotalDebt)}＝已入帳卡款 ${MoneyFormat.currency(overview.cardDebt)}" +
                            "＋未入帳分期本金 ${MoneyFormat.currency(overview.pendingInstallmentPrincipal)}" +
                            if (overview.unassignedCardSpending > 0) "（含未指定卡片的刷卡 ${MoneyFormat.currency(overview.unassignedCardSpending)}）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (overview.liquidAccounts.isNotEmpty()) {
                item { SectionTitle("現金與存款") }
                items(overview.liquidAccounts, key = { "liquid-${it.id}" }) { account ->
                    AccountRow(account.name, account.kind.label, MoneyFormat.currency(account.balance), emptyList(), false) { onEdit(account) }
                }
            }

            if (overview.cards.isNotEmpty()) {
                item { SectionTitle("信用卡") }
                items(overview.cards, key = { "card-${it.account.id}" }) { card ->
                    AccountRow(
                        title = card.account.name,
                        subtitle = listOf(card.account.kind.label, card.account.issuer).filter { it.isNotBlank() }.joinToString(" · "),
                        amount = "欠 " + MoneyFormat.currency(card.account.balance) +
                            if (card.pendingInstallmentPrincipal > 0) "\n總 ${MoneyFormat.currency(card.totalDebt)}" else "",
                        details = cardDetails(card),
                        warn = (card.utilizationPercent ?: 0) >= 50,
                        action = card.cycle?.let { cycle ->
                            (if (card.billEntered) "修改帳單（${cycle.statement.monthValue}/${cycle.statement.dayOfMonth} 結帳）" else "輸入帳單（${cycle.statement.monthValue}/${cycle.statement.dayOfMonth} 結帳）") to
                                { onOpenBill(card.account.id) }
                        },
                    ) { onEdit(card.account) }
                }
            }

            if (overview.loans.isNotEmpty()) {
                item { SectionTitle("貸款") }
                items(overview.loans, key = { "loan-${it.account.id}" }) { loan ->
                    AccountRow(loan.account.name, loan.account.kind.label, "欠 " + MoneyFormat.currency(loan.account.balance), loanDetails(loan), false) {
                        onEdit(loan.account)
                    }
                }
            }

            if (overview.liquidAccounts.isEmpty() && overview.cards.isEmpty() && overview.loans.isEmpty()) {
                item {
                    Text(
                        "還沒有帳戶。先新增錢包、銀行和信用卡，記帳時才有地方扣款。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新增帳戶") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private fun cardDetails(card: CardView): List<String> = buildList {
    card.account.creditLimit?.let { limit ->
        add(
            "額度 ${MoneyFormat.currency(limit)} · 已佔用 ${MoneyFormat.currency(card.usedCredit)} · 可用 ${MoneyFormat.currency(card.available ?: 0)}" +
                (card.utilizationPercent?.let { " · 使用率 $it%" } ?: ""),
        )
    }
    if (card.installmentCount > 0) {
        add("分期 ${card.installmentCount} 筆 · 未入帳 ${MoneyFormat.currency(card.pendingInstallmentPrincipal)} · 下期 ${MoneyFormat.currency(card.nextInstallmentAmount)}")
    }
    val days = listOfNotNull(card.account.statementDay?.let { "結帳日 $it 日" }, card.account.paymentDueDay?.let { "截止日 $it 日" })
    if (days.isNotEmpty()) add(days.joinToString(" · "))
    card.account.card?.let { terms ->
        add(
            listOfNotNull(
                "繳款方式：${terms.payMode.label}",
                terms.revolvingRatePercent?.let { "循環年利率 ${trim(it)}%" },
                card.interest.takeIf { it > 0 }?.let { "下期利息約 ${MoneyFormat.currency(it)}" },
            ).joinToString(" · "),
        )
        val cycle = card.cycle
        val bill = card.currentBill
        if (cycle != null && bill != null && bill > 0) {
            add(
                "本期帳單還要繳 ${MoneyFormat.currency(bill)}（${cycle.due.monthValue}/${cycle.due.dayOfMonth} 截止）" +
                    (card.minimumPayment?.let { " · 帳單最低 ${MoneyFormat.currency(it)}" } ?: "") +
                    if (card.billEntered) " · 已照帳單校正" else "",
            )
        }
    }
    if (card.monthSpending > 0) add("本月已刷 ${MoneyFormat.currency(card.monthSpending)}")
}

private fun loanDetails(loan: LoanView): List<String> = buildList {
    loan.account.loan?.let { terms ->
        add("${terms.method.label} · 年利率 ${trim(terms.annualRatePercent)}% · 剩 ${terms.remainingMonths} 期")
    }
    if (loan.monthlyPayment > 0) add("每月約繳 ${MoneyFormat.currency(loan.monthlyPayment)}")
    loan.repaidPercent?.let { add("已還 $it%") }
}

private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AccountRow(
    title: String,
    subtitle: String,
    amount: String,
    details: List<String>,
    warn: Boolean,
    action: Pair<String, () -> Unit>? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(amount, style = MaterialTheme.typography.titleSmall)
            }
            details.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn && it.contains("使用率")) StatusColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let { (label, onAction) -> TextButton(onClick = onAction) { Text(label) } }
        }
    }
}

// ---------------- 編輯表單 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountEditorForm(
    editor: AccountEditor,
    payAccounts: List<Account>,
    onChange: ((AccountDraft) -> AccountDraft) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = editor.draft
    val errors = editor.errors

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editor.isNew) "新增帳戶" else "編輯帳戶", style = MaterialTheme.typography.titleLarge)

        Text("種類", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountKind.entries.forEach { kind ->
                FilterChip(
                    selected = draft.kind == kind,
                    onClick = { onChange { it.copy(kind = kind) } },
                    label = { Text(kind.label) },
                    enabled = editor.isNew || draft.kind == kind,
                )
            }
        }

        TextInput("名稱", draft.name, errors[Field.NAME]) { value -> onChange { it.copy(name = value) } }
        TextInput(draft.balanceLabel, draft.balance, errors[Field.BALANCE], number = true) { value -> onChange { it.copy(balance = value) } }

        if (draft.isCard || draft.isLoan) {
            TextButton(onClick = onToggleAdvanced) {
                Icon(if (editor.showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                Text(if (editor.showAdvanced) "收起進階（選填）" else "進階（選填）")
            }
        }

        if (draft.isCard && editor.showAdvanced) {
            HorizontalDivider()
            Text("這些都可以不填；不填的話卡片只記欠款，繳卡費照計畫走。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextInput("發卡銀行", draft.issuer, null) { value -> onChange { it.copy(issuer = value) } }
            TextInput("額度", draft.creditLimit, errors[Field.LIMIT], number = true) { value -> onChange { it.copy(creditLimit = value) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("結帳日", draft.statementDay, errors[Field.STATEMENT_DAY], number = true, modifier = Modifier.weight(1f)) { value ->
                    onChange { it.copy(statementDay = value) }
                }
                TextInput("繳款截止日", draft.payDay, errors[Field.PAY_DAY], number = true, modifier = Modifier.weight(1f)) { value ->
                    onChange { it.copy(payDay = value) }
                }
            }
            SwitchRow("依帳單繳款（到期列在本月到期）", draft.scheduleEnabled) { checked -> onChange { it.copy(scheduleEnabled = checked) } }
            if (draft.scheduleEnabled) {
                Text("繳款方式（每期到期時還可以臨時換）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardPayMode.entries.forEach { mode ->
                        FilterChip(selected = draft.payMode == mode, onClick = { onChange { it.copy(payMode = mode) } }, label = { Text(mode.label) })
                    }
                }
                Text(
                    when (draft.payMode) {
                        CardPayMode.FULL -> "每期繳清帳單金額，不會有循環利息。"
                        CardPayMode.FREE -> "每期自己決定繳多少；沒繳清的部分會計循環利息。"
                        CardPayMode.MINIMUM -> "照帳單上的最低應繳（帳單來時輸入）；沒繳清的部分會計循環利息。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val partial = draft.payMode != CardPayMode.FULL
                TextInput(if (partial) "循環年利率（%）" else "循環年利率（%，選填）", draft.revolvingRate, errors[Field.RATE], number = true) { value ->
                    onChange { it.copy(revolvingRate = value) }
                }
                if (partial) {
                    TextInput("預估每月繳款", draft.estimatedPayment, errors[Field.ESTIMATE], number = true) { value ->
                        onChange { it.copy(estimatedPayment = value) }
                    }
                    Text("試算用；帳單來時可以輸入實際的最低應繳。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PayAccountPicker(draft.payAccountId, payAccounts, null) { id -> onChange { it.copy(payAccountId = id) } }
            }
        }

        if (draft.isLoan && editor.showAdvanced) {
            HorizontalDivider()
            SwitchRow("設定攤還條件（自動產生每月繳款）", draft.loanEnabled) { checked -> onChange { it.copy(loanEnabled = checked) } }
            if (draft.loanEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("年利率（%）", draft.loanRate, errors[Field.LOAN_RATE], number = true, modifier = Modifier.weight(1f)) { value ->
                        onChange { it.copy(loanRate = value) }
                    }
                    TextInput("剩餘期數", draft.loanMonths, errors[Field.LOAN_MONTHS], number = true, modifier = Modifier.weight(1f)) { value ->
                        onChange { it.copy(loanMonths = value) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("繳款日", draft.payDay, errors[Field.PAY_DAY], number = true, modifier = Modifier.weight(1f)) { value ->
                        onChange { it.copy(payDay = value) }
                    }
                    TextInput("原貸金額（選填）", draft.loanOriginal, errors[Field.LOAN_ORIGINAL], number = true, modifier = Modifier.weight(1f)) { value ->
                        onChange { it.copy(loanOriginal = value) }
                    }
                }
                Text("攤還方式", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepaymentMethod.entries.forEach { method ->
                        FilterChip(selected = draft.loanMethod == method, onClick = { onChange { it.copy(loanMethod = method) } }, label = { Text(method.label) })
                    }
                }
                PayAccountPicker(draft.payAccountId, payAccounts, errors[Field.PAY_ACCOUNT]) { id -> onChange { it.copy(payAccountId = id) } }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("儲存") }
        }
        if (!editor.isNew) {
            TextButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                Text("封存這個帳戶", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 帳單校正（R-CARD-23）：輸入帳單金額與選填的最低應繳；和 App 估計不同時，差額另記一筆。 */
@Composable
private fun BillDialog(
    bill: BillEditor,
    onChange: ((BillEditor) -> BillEditor) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${bill.cardName} 帳單（${bill.cycle.statement.monthValue}/${bill.cycle.statement.dayOfMonth} 結帳）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "App 估計 ${MoneyFormat.currency(bill.estimate)}，${bill.cycle.due.monthValue}/${bill.cycle.due.dayOfMonth} 截止。照帳單輸入，差額會另記一筆「帳單差額」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextInput("帳單金額", bill.amount, bill.error.takeIf { it?.contains("帳單金額") == true && it.startsWith("請") }, number = true) { value ->
                    onChange { it.copy(amount = value) }
                }
                TextInput("最低應繳（選填）", bill.minimum, bill.error.takeIf { it?.startsWith("最低") == true }, number = true) { value ->
                    onChange { it.copy(minimum = value) }
                }
                bill.error?.takeIf { !it.startsWith("請") && !it.startsWith("最低") }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (bill.existing) {
                    TextButton(onClick = onDelete) { Text("刪除這一期的校正", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    error: String?,
    number: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit,
) {
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayAccountPicker(selected: Long?, accounts: List<Account>, error: String?, onSelect: (Long?) -> Unit) {
    Text("扣款帳戶", style = MaterialTheme.typography.labelLarge)
    if (accounts.isEmpty()) {
        Text("先新增銀行或現金帳戶，才能選扣款帳戶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account ->
            FilterChip(selected = selected == account.id, onClick = { onSelect(account.id) }, label = { Text(account.name) })
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
