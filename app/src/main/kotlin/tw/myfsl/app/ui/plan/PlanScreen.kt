package tw.myfsl.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.PlanIssue
import tw.myfsl.app.core.domain.Severity
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.ui.theme.StatusColors

/** 計畫：年度摘要、計畫檢查、匯入與匯出。 */
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
    editorContent: @Composable (ItemEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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

    Box(modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("計畫", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onPreviousYear) { Icon(Icons.Default.ChevronLeft, contentDescription = "上一年") }
                Text("${state.year} 年", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onNextYear) { Icon(Icons.Default.ChevronRight, contentDescription = "下一年") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Text(" 匯入 CSV")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(" 匯出計畫")
                }
            }
            TextButton(onClick = onTemplate) { Text("下載空白範本") }
        }

        val summary = state.summary
        if (summary == null) {
            item {
                Text(
                    "${state.year} 年還沒有計畫。可以匯入 CSV（Excel 另存成 CSV 即可）、下載範本照著填，或按右下「新增項目」一個一個建。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("全年收入", MoneyFormat.currency(summary.totalIncome), Modifier.weight(1f))
                    Tile("全年支出", MoneyFormat.currency(summary.totalExpense), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("結構缺口", MoneyFormat.signed(summary.structuralGap), Modifier.weight(1f), negative = summary.structuralGap < 0)
                    Tile("卡債全年增加", MoneyFormat.signed(summary.cardDebtIncrease), Modifier.weight(1f), negative = summary.cardDebtIncrease > 0)
                }
            }
            if (summary.totalCardInterest > 0) {
                item {
                    Text(
                        "其中預估循環利息 ${MoneyFormat.currency(summary.totalCardInterest)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warningText,
                    )
                }
            }
        }

        if (state.issues.isNotEmpty()) {
            item { Text("計畫檢查", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(state.issues) { IssueRow(it) }
        }

        if (state.rows.isNotEmpty()) {
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("項目", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(selected = !state.showTable, onClick = { onShowTable(false) }, label = { Text("清單") })
                    FilterChip(selected = state.showTable, onClick = { onShowTable(true) }, label = { Text("月份表格") }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        val table = state.table
        if (state.showTable && table != null) {
            item { PlanMonthTable(table, onEditItem) }
        } else if (state.rows.isNotEmpty()) {
            var lastGroup: String? = null
            state.rows.forEach { row ->
                if (row.groupName != lastGroup) {
                    val group = row.groupName
                    item { Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    lastGroup = group
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { onEditItem(row.itemId) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MethodDot(row.method, Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.itemName, style = MaterialTheme.typography.bodyMedium)
                            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(MoneyFormat.currency(row.total), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
        ExtendedFloatingActionButton(
            onClick = onAddItem,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新增項目") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier, negative: Boolean = false) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun IssueRow(issue: PlanIssue) {
    val (label, color) = when (issue.severity) {
        Severity.ERROR -> "錯誤" to MaterialTheme.colorScheme.error
        Severity.WARNING -> "注意" to StatusColors.warningText
        Severity.INFO -> "提示" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 2.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = if (issue.severity == Severity.INFO) color else Color.Unspecified)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewPanel(
    import: ImportState,
    onMode: (ImportMode) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = import.preview
    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("匯入預覽", style = MaterialTheme.typography.titleLarge)
            Text("${import.fileName} · ${import.encoding} · 匯入到 ${preview.year} 年", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("項目", "${preview.itemCount} 個（${preview.lineCount} 列）", Modifier.weight(1f))
                Tile("全年收入", MoneyFormat.currency(preview.income), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("全年支出", MoneyFormat.currency(preview.expense), Modifier.weight(1f))
                Tile("其中刷卡", MoneyFormat.currency(preview.cardSpending), Modifier.weight(1f))
            }
        }
        if (preview.newGroups.isNotEmpty()) {
            item { Text("會新增群組：${preview.newGroups.joinToString("、")}", style = MaterialTheme.typography.bodySmall) }
        }

        val shown = preview.issues.filterNot { it.severity == Severity.INFO && (it.message.startsWith("共 ") || it.message.startsWith("會新增群組")) }
        if (shown.isNotEmpty()) {
            item { Text("檢查結果", style = MaterialTheme.typography.titleSmall) }
            items(shown) { IssueRow(it) }
        }

        item {
            HorizontalDivider()
            Text("匯入方式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportMode.entries.forEach { mode ->
                    FilterChip(selected = import.mode == mode, onClick = { onMode(mode) }, label = { Text(mode.label) })
                }
            }
            Text(
                when (import.mode) {
                    ImportMode.REPLACE_YEAR -> "檔案裡的項目會覆蓋這一年的金額；檔案沒有的項目保持不動。"
                    ImportMode.ADD_ONLY -> "只新增 App 裡還沒有的項目，已有的項目不會被改。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            if (!preview.canImport) {
                Text(
                    "有錯誤不能匯入。請在 Excel 修正上面列出的列號後再匯入一次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = onConfirm, enabled = preview.canImport && !import.importing, modifier = Modifier.weight(1f)) {
                    Text(if (import.importing) "匯入中…" else "匯入", textAlign = TextAlign.Center)
                }
            }
        }
    }
}
