package tw.myfsl.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.AccountDraft
import tw.myfsl.app.core.domain.AccountForm.Field
import tw.myfsl.app.core.domain.CardView
import tw.myfsl.app.core.domain.LoanView
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.EmptyLine
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.FormScaffold
import tw.myfsl.app.ui.components.HeroCard
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.MoneyField
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.Sheet
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.components.Tone
import tw.myfsl.app.ui.components.ToneProgress
import tw.myfsl.app.ui.theme.Spacing

/** 帳戶（設計稿：全畫面改版 v1）：可動用現金、現金與存款、信用卡、貸款；新增／編輯與帳單校正。 */
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
        Loading(modifier)
        return
    }
    val editor = state.editor
    if (editor != null) {
        AccountEditorForm(editor, state.payAccounts, onChange, onToggleAdvanced, onSave, onCancel, onArchive, modifier)
        return
    }
    val overview = state.overview ?: return
    state.bill?.let { BillSheet(it, onBillChange, onBillSave, onBillDelete, onBillClose) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("帳戶")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                item(key = "hero") {
                    HeroCard(
                        label = "可動用現金",
                        value = MoneyFormat.currency(overview.liquid),
                        // 現金旁邊只放和現金有關的數字；負債放在下面信用卡、貸款區段（使用者要求）。
                        figures = if (overview.cardReserve > 0) {
                            listOf("要留給卡費" to MoneyFormat.currency(overview.cardReserve), "扣掉後可用" to MoneyFormat.currency(overview.freeCash))
                        } else {
                            emptyList()
                        },
                    )
                }

                val nothing = overview.liquidAccounts.isEmpty() && overview.cards.isEmpty() && overview.loans.isEmpty()
                if (nothing) {
                    item(key = "empty") { ListCard { EmptyLine("還沒有帳戶。按右下「新增帳戶」，先新增錢包、銀行和信用卡，記帳時才有地方扣款。") } }
                }

                if (overview.liquidAccounts.isNotEmpty()) {
                    item(key = "liquid") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SectionHeader("現金與存款")
                            ListCard {
                                overview.liquidAccounts.forEachIndexed { index, account ->
                                    if (index > 0) ListDivider()
                                    Surface(onClick = { onEdit(account) }, color = Color.Transparent) {
                                        ListRow(
                                            title = account.name,
                                            detail = account.kind.label,
                                            lead = { KindIcon(account.kind) },
                                            trailing = MoneyFormat.currency(account.balance),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (overview.cards.isNotEmpty()) {
                    item(key = "cards-title") {
                        SectionHeader(
                            "信用卡",
                            trailing = "總負債 ${MoneyFormat.currency(overview.cardTotalDebt)}",
                        )
                    }
                    overview.cards.forEach { card ->
                        item(key = "card-${card.account.id}") { CardItem(card, onEdit, onOpenBill) }
                    }
                    item(key = "cards-note") {
                        HintText(
                            "信用卡總負債＝已入帳卡款 ${MoneyFormat.currency(overview.cardDebt)}＋未入帳分期本金 ${MoneyFormat.currency(overview.pendingInstallmentPrincipal)}" +
                                if (overview.unassignedCardSpending > 0) "（含未指定卡片的刷卡 ${MoneyFormat.currency(overview.unassignedCardSpending)}）" else "",
                        )
                    }
                }

                if (overview.loans.isNotEmpty()) {
                    item(key = "loans") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SectionHeader("貸款", trailing = "負債合計 ${MoneyFormat.currency(overview.totalDebt)}")
                            overview.loans.forEach { loan -> LoanItem(loan, onEdit) }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(88.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("新增帳戶") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
        )
    }
}

private fun kindIcon(kind: AccountKind): ImageVector = when (kind) {
    AccountKind.CASH -> Icons.Rounded.AccountBalanceWallet
    AccountKind.BANK -> Icons.Rounded.AccountBalance
    AccountKind.STORED_VALUE -> Icons.Rounded.Contactless
    AccountKind.CREDIT_CARD -> Icons.Rounded.CreditCard
    AccountKind.LOAN -> Icons.Rounded.RequestQuote
    AccountKind.POLICY_LOAN -> Icons.Rounded.HealthAndSafety
}

@Composable
private fun KindIcon(kind: AccountKind) {
    Icon(kindIcon(kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 一張信用卡：欠款、額度使用率（過半變黃）、帳單與分期；「輸入帳單／修改帳單」是卡片上的按鈕。 */
@Composable
private fun CardItem(card: CardView, onEdit: (Account) -> Unit, onOpenBill: (Long) -> Unit) {
    val account = card.account
    Surface(onClick = { onEdit(account) }, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                KindIcon(account.kind)
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.bodyMedium)
                    val days = listOfNotNull(account.statementDay?.let { "$it 日結帳" }, account.paymentDueDay?.let { "$it 日截止" }, account.issuer.takeIf { it.isNotBlank() })
                    if (days.isNotEmpty()) Text(days.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("欠 " + MoneyFormat.currency(account.balance), style = MaterialTheme.typography.titleSmall)
                    if (card.pendingInstallmentPrincipal > 0) {
                        Text("含分期共 ${MoneyFormat.currency(card.totalDebt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            account.creditLimit?.let { limit ->
                val used = card.utilizationPercent ?: 0
                ToneProgress(used / 100f, if (used >= 50) Tone.WARNING else Tone.NEUTRAL)
                Text(
                    "額度 ${MoneyFormat.currency(limit)} · 可用 ${MoneyFormat.currency(card.available ?: 0)}" + (card.utilizationPercent?.let { " · 使用率 $it%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cardLines(card).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            card.cycle?.let { cycle ->
                FilledTonalButton(onClick = { onOpenBill(account.id) }) {
                    Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text((if (card.billEntered) "修改帳單" else "輸入帳單") + "（${cycle.statement.monthValue}/${cycle.statement.dayOfMonth} 結帳）")
                }
            }
        }
    }
}

private fun cardLines(card: CardView): List<String> = buildList {
    val cycle = card.cycle
    val bill = card.currentBill
    if (cycle != null && bill != null && bill > 0) {
        add(
            "本期帳單還要繳 ${MoneyFormat.currency(bill)}（${cycle.due.monthValue}/${cycle.due.dayOfMonth} 截止）" +
                (card.minimumPayment?.let { " · 最低 ${MoneyFormat.currency(it)}" } ?: "") +
                if (card.billEntered) " · 已照帳單校正" else "",
        )
    }
    if (card.installmentCount > 0) {
        add("分期 ${card.installmentCount} 筆 · 未入帳 ${MoneyFormat.currency(card.pendingInstallmentPrincipal)} · 下期 ${MoneyFormat.currency(card.nextInstallmentAmount)}")
    }
    card.account.card?.let { terms ->
        listOfNotNull(
            terms.revolvingRatePercent?.let { "循環年利率 ${trim(it)}%" },
            card.interest.takeIf { it > 0 }?.let { "下期利息約 ${MoneyFormat.currency(it)}" },
        ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
        card.assumption?.let { add("試算：" + it.describe(card.account.name).substringAfter("：")) }
    }
    if (card.monthSpending > 0) add("本月已刷 ${MoneyFormat.currency(card.monthSpending)}")
}

/** 一筆貸款：欠款、已還比例、每月約繳與剩餘期數。 */
@Composable
private fun LoanItem(loan: LoanView, onEdit: (Account) -> Unit) {
    val account = loan.account
    Surface(onClick = { onEdit(account) }, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                KindIcon(account.kind)
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.bodyMedium)
                    Text(account.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("欠 " + MoneyFormat.currency(account.balance), style = MaterialTheme.typography.titleSmall)
            }
            loan.repaidPercent?.let { ToneProgress(it / 100f, Tone.NEUTRAL) }
            val lines = buildList {
                loan.repaidPercent?.let { add("已還 $it%") }
                if (loan.monthlyPayment > 0) add("每月約繳 ${MoneyFormat.currency(loan.monthlyPayment)}")
                account.loan?.let { add("${it.method.label} · 年利率 ${trim(it.annualRatePercent)}% · 剩 ${it.remainingMonths} 期") }
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// ---------------- 編輯表單 ----------------

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

    FormScaffold(title = if (editor.isNew) "新增帳戶" else "編輯帳戶", onClose = onCancel, onSave = onSave, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("種類")
            ChoiceChips(AccountKind.entries, { draft.kind == it }, { it.label }, { kind -> onChange { it.copy(kind = kind) } }, enabled = { editor.isNew || draft.kind == it })
        }
        TextInput("名稱", draft.name, { v -> onChange { it.copy(name = v) } }, error = errors[Field.NAME])
        MoneyField(draft.balanceLabel, draft.balance, { v -> onChange { it.copy(balance = v) } }, error = errors[Field.BALANCE])

        if (draft.isCard || draft.isLoan) {
            SectionCard {
                Surface(onClick = onToggleAdvanced, color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (draft.isCard) "卡片細節（選填）" else "攤還條件（選填）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        Icon(if (editor.showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (editor.showAdvanced) "收起" else "展開")
                    }
                }
                if (editor.showAdvanced && draft.isCard) CardFields(draft, errors, payAccounts, onChange)
                if (editor.showAdvanced && draft.isLoan) LoanFields(draft, errors, payAccounts, onChange)
            }
        }

        if (!editor.isNew) DangerButton("封存這個帳戶", onClick = onArchive, icon = Icons.Rounded.Archive)
    }
}

@Composable
private fun CardFields(draft: AccountDraft, errors: Map<String, String>, payAccounts: List<Account>, onChange: ((AccountDraft) -> AccountDraft) -> Unit) {
    HintText("這些都可以不填；不填的話卡片只記欠款，繳卡費照計畫走。")
    TextInput("發卡銀行", draft.issuer, { v -> onChange { it.copy(issuer = v) } })
    MoneyField("額度", draft.creditLimit, { v -> onChange { it.copy(creditLimit = v) } }, error = errors[Field.LIMIT])
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextInput("結帳日", draft.statementDay, { v -> onChange { it.copy(statementDay = v) } }, Modifier.weight(1f), error = errors[Field.STATEMENT_DAY], number = true)
        TextInput("繳款截止日", draft.payDay, { v -> onChange { it.copy(payDay = v) } }, Modifier.weight(1f), error = errors[Field.PAY_DAY], number = true)
    }
    SwitchRow("依帳單繳款", draft.scheduleEnabled, { checked -> onChange { it.copy(scheduleEnabled = checked) } }, detail = "到期時列在本月到期，每期選全額、最低或自己填")
    if (draft.scheduleEnabled) {
        TextInput(
            "循環年利率（%，選填）", draft.revolvingRate, { v -> onChange { it.copy(revolvingRate = v) } },
            error = errors[Field.RATE], number = true,
            supporting = "沒繳清時計息用；每期都繳清的卡可以不填。有欠款的卡，建好後請輸入最近一期帳單。",
        )
        PayAccountPicker(draft.payAccountId, payAccounts, null) { id -> onChange { it.copy(payAccountId = id) } }
    }
}

@Composable
private fun LoanFields(draft: AccountDraft, errors: Map<String, String>, payAccounts: List<Account>, onChange: ((AccountDraft) -> AccountDraft) -> Unit) {
    SwitchRow("設定攤還條件", draft.loanEnabled, { checked -> onChange { it.copy(loanEnabled = checked) } }, detail = "自動產生每月繳款，到期列在本月到期")
    if (draft.loanEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextInput("年利率（%）", draft.loanRate, { v -> onChange { it.copy(loanRate = v) } }, Modifier.weight(1f), error = errors[Field.LOAN_RATE], number = true)
            TextInput("剩餘期數", draft.loanMonths, { v -> onChange { it.copy(loanMonths = v) } }, Modifier.weight(1f), error = errors[Field.LOAN_MONTHS], number = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextInput("繳款日", draft.payDay, { v -> onChange { it.copy(payDay = v) } }, Modifier.weight(1f), error = errors[Field.PAY_DAY], number = true)
            TextInput("原貸金額（選填）", draft.loanOriginal, { v -> onChange { it.copy(loanOriginal = v) } }, Modifier.weight(1f), error = errors[Field.LOAN_ORIGINAL], number = true)
        }
        FieldLabel("攤還方式")
        SegmentedChoice(RepaymentMethod.entries, draft.loanMethod, { it.label }, { m -> onChange { it.copy(loanMethod = m) } })
        PayAccountPicker(draft.payAccountId, payAccounts, errors[Field.PAY_ACCOUNT]) { id -> onChange { it.copy(payAccountId = id) } }
    }
}

@Composable
private fun PayAccountPicker(selected: Long?, accounts: List<Account>, error: String?, onSelect: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FieldLabel("扣款帳戶")
        if (accounts.isEmpty()) HintText("先新增銀行或現金帳戶，才能選扣款帳戶")
        ChoiceChips(accounts, { selected == it.id }, { it.name }, { onSelect(it.id) })
        error?.let { ErrorText(it) }
    }
}

/** 帳單校正（R-CARD-23）：底部面板，輸入帳單金額與選填的最低應繳；和 App 估計不同時，差額另記一筆。 */
@Composable
private fun BillSheet(
    bill: BillEditor,
    onChange: ((BillEditor) -> BillEditor) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Sheet(onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("${bill.cardName} 帳單", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "${bill.cycle.statement.monthValue}/${bill.cycle.statement.dayOfMonth} 結帳 · ${bill.cycle.due.monthValue}/${bill.cycle.due.dayOfMonth} 截止 · App 估計 ${MoneyFormat.currency(bill.estimate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyField(
            "帳單金額", bill.amount, { v -> onChange { it.copy(amount = v) } },
            error = bill.error.takeIf { it?.contains("帳單金額") == true && it.startsWith("請") },
            supporting = "照帳單輸入；和估計不同時，差額會另記一筆「帳單差額」。",
        )
        MoneyField("最低應繳（選填）", bill.minimum, { v -> onChange { it.copy(minimum = v) } }, error = bill.error.takeIf { it?.startsWith("最低") == true })
        bill.error?.takeIf { !it.startsWith("請") && !it.startsWith("最低") }?.let { ErrorText(it) }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("儲存帳單")
        }
        if (bill.existing) DangerButton("刪除這一期的校正", onClick = onDelete, icon = Icons.Rounded.Delete)
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}
