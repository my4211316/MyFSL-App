package tw.myfsl.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.PlanIssue
import tw.myfsl.app.core.domain.Severity
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.ui.components.BottomActions
import tw.myfsl.app.ui.components.GroupLabel
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.MethodIcon
import tw.myfsl.app.ui.components.NavAction
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.StatusBadge
import tw.myfsl.app.ui.components.SummaryTile
import tw.myfsl.app.ui.components.Tone
import tw.myfsl.app.ui.theme.Spacing
import tw.myfsl.app.ui.theme.warningColors

/**
 * 計畫（設計稿：全畫面改版 v1）：年度摘要、計畫檢查、項目清單或月份表格。
 * 匯入、匯出、下載範本一年只用幾次，收在右上角選單；還沒有計畫時畫面中間直接給「匯入 CSV」。
 */
@Composable
fun PlanScreen(
    state: PlanUiState,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onTemplate: () -> Unit,
    onImportMode: (ImportMode) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onAddItem: () -> Unit,
    onEditItem: (Long) -> Unit,
    onShowTable: (Boolean) -> Unit,
    onGrouping: (PlanGrouping) -> Unit,
    editorContent: @Composable (ItemEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Loading(modifier)
        return
    }
    state.import?.let {
        ImportPreviewPanel(it, onImportMode, onConfirmImport, onCancelImport, modifier)
        return
    }
    state.editor?.let {
        editorContent(it)
        return
    }
    var menu by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("計畫") {
                IconButton(onClick = onPreviousYear) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一年") }
                Text("${state.year}", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onNextYear) { Icon(Icons.Rounded.ChevronRight, contentDescription = "下一年") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "匯入與匯出") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("匯入 CSV") }, leadingIcon = { Icon(Icons.Rounded.Upload, null) }, onClick = { menu = false; onImport() })
                        DropdownMenuItem(text = { Text("匯出計畫") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text("下載空白範本") }, leadingIcon = { Icon(Icons.Rounded.Description, null) }, onClick = { menu = false; onTemplate() })
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                val summary = state.summary
                if (summary == null) {
                    item(key = "empty") {
                        SectionCard {
                            Text("${state.year} 年還沒有計畫", style = MaterialTheme.typography.titleSmall)
                            HintText("把 Excel 預算表另存成 CSV 匯入最快；也可以下載範本照著填，或按右下「新增項目」一個一個建。")
                            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Icon(Icons.Rounded.Upload, contentDescription = null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text("匯入 CSV")
                            }
                            TextButton(onClick = onTemplate) { Text("下載空白範本") }
                        }
                    }
                } else {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                SummaryTile("全年收入", MoneyFormat.currency(summary.totalIncome), Modifier.weight(1f))
                                SummaryTile("全年支出", MoneyFormat.currency(summary.totalExpense), Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                SummaryTile(
                                    "結構缺口", MoneyFormat.signed(summary.structuralGap), Modifier.weight(1f),
                                    valueColor = if (summary.structuralGap < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                                SummaryTile(
                                    "卡債全年增加", MoneyFormat.signed(summary.cardDebtIncrease), Modifier.weight(1f),
                                    valueColor = if (summary.cardDebtIncrease > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                            }
                            if (summary.totalCardInterest > 0) {
                                HintText("其中預估循環利息 ${MoneyFormat.currency(summary.totalCardInterest)}", color = MaterialTheme.warningColors.warning)
                            }
                        }
                    }
                }

                if (state.issues.isNotEmpty()) {
                    item(key = "issues") { IssuesCard(state.issues) }
                }

                if (state.rows.isNotEmpty()) {
                    item(key = "view") {
                        SegmentedChoice(listOf(false, true), state.showTable, { if (it) "月份表格" else "清單" }, onShowTable)
                    }
                }
                if (state.rows.isNotEmpty() && !state.showTable) {
                    item(key = "grouping") {
                        SegmentedChoice(PlanGrouping.entries, state.grouping, { it.label }, onGrouping)
                    }
                }
                val table = state.table
                if (state.showTable && table != null) {
                    item(key = "table") { PlanMonthTable(table, onEditItem) }
                } else if (state.rows.isNotEmpty()) {
                    when (state.grouping) {
                        PlanGrouping.GROUP -> state.rows.groupBy { it.groupName }.forEach { (group, rows) ->
                            item(key = "group-$group") {
                                PlanRowGroup(group, null, rows, onEditItem)
                            }
                        }

                        // 依支付方式：每組標出小計與佔全年支出多少，一眼看完支付結構（R-MIX-06）。
                        PlanGrouping.METHOD -> state.methodGroups.forEach { group ->
                            item(key = "method-${group.method.name}") {
                                PlanRowGroup(
                                    group.method.label,
                                    "${MoneyFormat.currency(group.total)} · ${group.percent}%",
                                    group.rows,
                                    onEditItem,
                                )
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(88.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAddItem,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("新增項目") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
        )
    }
}

/** 一組計畫列：標題（群組名或支付方式）可以帶小計。 */
@Composable
private fun PlanRowGroup(title: String, trailing: String?, rows: List<PlanRow>, onEditItem: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (trailing == null) {
            GroupLabel(title)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupLabel(title, Modifier.weight(1f))
                Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ListCard {
            rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider()
                Surface(onClick = { onEditItem(row.itemId) }, color = Color.Transparent) {
                    ListRow(
                        title = row.itemName,
                        detail = row.detail,
                        lead = row.method?.let { method -> { MethodIcon(method) } },
                        trailing = MoneyFormat.currency(row.total),
                    )
                }
            }
        }
    }
}

/** 計畫檢查：錯誤與注意的數量標在上面，先列三則，其餘展開看。 */
@Composable
private fun IssuesCard(issues: List<PlanIssue>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val errors = issues.count { it.severity == Severity.ERROR }
    val warnings = issues.count { it.severity == Severity.WARNING }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("計畫檢查", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (errors > 0) StatusBadge("錯誤 $errors", tone = Tone.ERROR)
            if (warnings > 0) StatusBadge("注意 $warnings", tone = Tone.WARNING)
        }
        val shown = if (all) issues else issues.take(3)
        shown.forEach { IssueRow(it) }
        if (issues.size > 3) {
            TextButton(onClick = { all = !all }) { Text(if (all) "收起" else "全部 ${issues.size} 則") }
        }
    }
}

@Composable
private fun IssueRow(issue: PlanIssue) {
    val (label, tone) = when (issue.severity) {
        Severity.ERROR -> "錯誤" to Tone.ERROR
        Severity.WARNING -> "注意" to Tone.WARNING
        Severity.INFO -> "提示" to Tone.NEUTRAL
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        StatusBadge(label, tone = tone)
        Text(issue.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** 匯入預覽：看清楚會匯入什麼、有沒有錯誤，再決定怎麼匯入。 */
@Composable
private fun ImportPreviewPanel(
    import: ImportState,
    onMode: (ImportMode) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = import.preview
    Column(modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(
            "匯入預覽",
            subtitle = "${import.fileName} · 匯入到 ${preview.year} 年",
            navigation = NavAction(Icons.Rounded.Close, "取消匯入", onCancel),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SummaryTile("項目", "${preview.itemCount} 個", Modifier.weight(1f))
                SummaryTile("全年收入", MoneyFormat.currency(preview.income), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SummaryTile("全年支出", MoneyFormat.currency(preview.expense), Modifier.weight(1f))
                SummaryTile("其中刷卡", MoneyFormat.currency(preview.cardSpending), Modifier.weight(1f))
            }
            HintText("共 ${preview.lineCount} 列 · 檔案編碼 ${import.encoding}")
            if (preview.newGroups.isNotEmpty()) HintText("會新增群組：${preview.newGroups.joinToString("、")}")

            val shown = preview.issues.filterNot { it.severity == Severity.INFO && (it.message.startsWith("共 ") || it.message.startsWith("會新增群組")) }
            if (shown.isNotEmpty()) {
                SectionHeader("檢查結果")
                SectionCard { shown.forEach { IssueRow(it) } }
            }

            SectionHeader("匯入方式")
            SegmentedChoice(ImportMode.entries, import.mode, { it.label }, onMode)
            HintText(
                when (import.mode) {
                    ImportMode.REPLACE_YEAR -> "檔案裡的項目會覆蓋這一年的金額；檔案沒有的項目保持不動。"
                    ImportMode.ADD_ONLY -> "只新增 App 裡還沒有的項目，已有的項目不會被改。"
                },
            )
            if (!preview.canImport) {
                Text("有錯誤不能匯入。請在 Excel 修正上面列出的列號後再匯入一次。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        BottomActions(
            primary = if (import.importing) "匯入中…" else "匯入",
            onPrimary = onConfirm,
            primaryEnabled = preview.canImport && !import.importing,
            secondary = "取消",
            onSecondary = onCancel,
        )
    }
}
