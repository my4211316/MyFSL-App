package tw.myfsl.app.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.ConfirmChoice
import tw.myfsl.app.core.domain.DiffKind
import tw.myfsl.app.core.domain.DueCheck
import tw.myfsl.app.core.domain.Resolution
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.ReportInput
import tw.myfsl.app.ui.components.BottomActions
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.MoneyField
import tw.myfsl.app.ui.components.NavAction
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.theme.Spacing

/**
 * 本週檢查（設計稿：全畫面改版 v1）：到期確認 → 帳戶對帳 → 會寫入什麼。
 * 上方進度條與「第 N 步，共 3 步」；下方固定「上一步／下一步」，第一步的左邊是「先不做」。
 */
@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onChoose: (String, ConfirmChoice) -> Unit,
    onConfirmAmount: (String, String) -> Unit,
    onReport: (PlanLine, String) -> Unit,
    onDueChoose: (String, DueCheck) -> Unit,
    onDueAmount: (String, String) -> Unit,
    onBalance: (Long, String) -> Unit,
    onMatch: (Long) -> Unit,
    onResolution: (Long, Resolution) -> Unit,
    onItem: (Long, Long) -> Unit,
    onFinish: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Loading(modifier)
        return
    }
    val close = NavAction(Icons.Rounded.Close, "關閉", onClose)
    if (state.done) {
        Column(modifier.fillMaxSize()) {
            ScreenTopBar("本週檢查", navigation = close)
            DoneContent(state.doneLines, Modifier.weight(1f))
            BottomActions(primary = "完成", onPrimary = onClose, secondary = "再檢查一次", onSecondary = onAgain)
        }
        return
    }

    val index = state.steps.indexOf(state.step)
    Column(modifier.fillMaxSize().imePadding()) {
        ScreenTopBar("本週檢查", subtitle = "第 ${index + 1} 步，共 ${state.steps.size} 步 · ${state.step.title}", navigation = close)
        StepBar(index, state.steps.size)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            when (state.step) {
                CheckInStep.CONFIRM -> ConfirmStep(state, onChoose, onConfirmAmount, onReport, onDueChoose, onDueAmount)
                CheckInStep.RECONCILE -> ReconcileStep(state, onBalance, onMatch, onResolution, onItem)
                CheckInStep.REVIEW -> ReviewStep(state)
            }
        }
        if (state.step != CheckInStep.REVIEW) {
            BottomActions(
                primary = "下一步",
                onPrimary = onNext,
                secondary = if (index == 0) "先不做" else "上一步",
                onSecondary = if (index == 0) onClose else onBack,
            )
        } else {
            val empty = state.result?.isEmpty != false
            BottomActions(
                primary = if (empty) "沒有要寫入的東西" else if (state.saving) "寫入中…" else "完成檢查",
                onPrimary = onFinish,
                primaryEnabled = !empty && !state.saving,
                secondary = "上一步",
                onSecondary = onBack,
            )
        }
    }
}

/** 三段進度條：已完成與目前這步用主色。 */
@Composable
private fun StepBar(index: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).clearAndSetSemantics { contentDescription = "第 ${index + 1} 步，共 $count 步" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(count) { i ->
            Surface(
                color = if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.weight(1f).height(4.dp),
            ) {}
        }
    }
}

@Composable
private fun ConfirmStep(
    state: CheckInUiState,
    onChoose: (String, ConfirmChoice) -> Unit,
    onAmount: (String, String) -> Unit,
    onReport: (PlanLine, String) -> Unit,
    onDueChoose: (String, DueCheck) -> Unit,
    onDueAmount: (String, String) -> Unit,
) {
    if (state.dues.isNotEmpty()) {
        SectionHeader("到期還沒記下")
        HintText("先處理這些，對帳時才不會被當成漏記。平常也可以在記帳頁右上角的今天總覽點一下記下。")
        state.dues.forEach { row ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.due.title, style = MaterialTheme.typography.titleSmall)
                        HintText(row.dateLabel)
                    }
                    Text(MoneyFormat.currency(row.due.amount), style = MaterialTheme.typography.titleSmall)
                }
                ChoiceChips(
                    DueCheck.entries.filter { it != DueCheck.DIFFERENT_AMOUNT || row.due.amountEditable },
                    { row.choice == it },
                    { it.labelFor(row.due.isIncome) },
                    { onDueChoose(row.due.key, it) },
                )
                if (row.choice == DueCheck.DIFFERENT_AMOUNT) {
                    MoneyField("實際金額", row.amountText, { onDueAmount(row.due.key, it) }, error = if (row.amountError) "請輸入實際金額" else null)
                }
            }
        }
    }
    if (state.confirms.isEmpty() && state.dues.isEmpty()) {
        HintText("本月沒有要確認的到期項目，直接下一步。")
    }
    if (state.confirms.isNotEmpty()) SectionHeader("本月到期確認")
    state.confirms.forEach { row ->
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("計畫 " + MoneyFormat.currency(row.line.planned), style = MaterialTheme.typography.bodyMedium)
            }
            if (row.line.recorded > 0) HintText("本月已記 ${MoneyFormat.currency(row.line.recorded)}，只會補差額")
            ChoiceChips(ConfirmChoice.entries, { row.choice == it }, { it.labelFor(row.line.item.type) }, { onChoose(row.line.key, it) })
            if (row.choice == ConfirmChoice.DIFFERENT_AMOUNT) {
                MoneyField("實際金額", row.amountText, { onAmount(row.line.key, it) }, error = if (row.amountError) "請輸入實際金額" else null)
            }
        }
    }

    if (state.reports.isNotEmpty()) {
        SectionHeader("每週回報")
        state.reports.forEach { row ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("計畫 " + MoneyFormat.currency(row.line.planned), style = MaterialTheme.typography.bodyMedium)
                }
                val label = if (row.line.input == ReportInput.REMAINING) "還剩多少" else "本月累計花費"
                MoneyField(label, row.text, { onReport(row.line.line, it) }, placeholder = row.line.prefill.toString(), supporting = "已記帳 ${MoneyFormat.currency(row.line.recorded)}；沒填就不回報")
            }
        }
    }
}

@Composable
private fun ReconcileStep(
    state: CheckInUiState,
    onBalance: (Long, String) -> Unit,
    onMatch: (Long) -> Unit,
    onResolution: (Long, Resolution) -> Unit,
    onItem: (Long, Long) -> Unit,
) {
    HintText("數一下錢包、看銀行 App 的餘額和各張卡的欠款，填進來。沒填的帳戶會跳過。")
    if (state.reconciles.isEmpty()) HintText("還沒有帳戶，先到「帳戶」新增。")
    state.reconciles.forEach { rec ->
        val row = rec.row
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text((if (row.isCard) "推算欠款 " else "推算 ") + MoneyFormat.currency(row.computed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.accounts.forEach { account ->
                MoneyField(if (row.isCard) "${account.name} 欠款" else "實際餘額", rec.inputs[account.id].orEmpty(), { onBalance(account.id, it) })
            }
            if (row.isCard && row.accounts.size > 1) HintText("每一張卡都要填，少一張就整列跳過")
            AssistChip(
                onClick = { onMatch(row.id) },
                label = { Text(if (row.isCard) "填入各卡推算欠款" else "和推算相符") },
                leadingIcon = { Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            if (row.isCard && row.computed != row.accounts.sumOf { it.balance }) {
                HintText("推算裡有未指定卡片的刷卡 ${MoneyFormat.currency(row.computed - row.accounts.sumOf { it.balance })}，請看銀行 App 加到實際刷的那張卡")
            }

            if (rec.kind != null && rec.message != null) {
                val warn = rec.kind == DiffKind.MISSED || rec.kind == DiffKind.OVER_RECORDED
                if (warn) WarningText(rec.message) else Text(rec.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rec.kind != DiffKind.MATCHED) {
                    ChoiceChips(Resolution.entries, { rec.resolution == it }, { it.label }, { onResolution(row.id, it) })
                    if (rec.resolution == Resolution.ASSIGN_ITEM) {
                        FieldLabel("差額歸到")
                        ChoiceChips(state.itemChoices, { rec.item?.id == it.id }, { it.name }, { onItem(row.id, it.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(state: CheckInUiState) {
    if (state.summaryLines.isEmpty()) {
        HintText("目前沒有任何要寫入的東西。回到前面填帳戶金額或選到期項目。")
        return
    }
    SectionHeader("完成後會寫入")
    ListCard {
        state.summaryLines.forEachIndexed { index, line ->
            if (index > 0) ListDivider()
            ListRow(title = line)
        }
    }
    HintText("記帳與校正餘額用同一個時間寫入，餘額不會重複扣。")
}

/** 完成：大勾勾、做了哪些事（mobile-app-ui-design 的 Peak-End 收尾）。 */
@Composable
private fun DoneContent(lines: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(36.dp)) }
        }
        Text("本週檢查完成", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (lines.isNotEmpty()) {
            ListCard {
                lines.forEachIndexed { index, line ->
                    if (index > 0) ListDivider()
                    ListRow(title = line)
                }
            }
        }
    }
}
