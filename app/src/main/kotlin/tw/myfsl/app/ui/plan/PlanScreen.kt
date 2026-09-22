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
import androidx.compose.material.icons.rounded.Warning
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
import tw.myfsl.app.core.domain.PlanTableView
import tw.myfsl.app.core.domain.Severity
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.ui.components.BottomActions
import tw.myfsl.app.ui.components.CashLineChart
import tw.myfsl.app.ui.components.ChartSeries
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
import tw.myfsl.app.ui.theme.chartColors
import tw.myfsl.app.ui.theme.warningColors

/**
 * 計畫（設計稿：全畫面改版 v1）：年度摘要、未來現金水位、計畫檢查、項目清單或月份表格。
 *
 * 水位圖放在這裡（R-PLS-09）：那是「照這份計畫走下去會怎樣」，和年度計畫是同一件事；
 * 要比較「改了會怎樣」請到試算開情境（R-SCN）。
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
    onTableView: (PlanTableView) -> Unit,
    onOpenForecast: () -> Unit,
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
                            // 三格加得起來（R-PLS-08）：收入 − 支出 = 結構缺口，中間不再外加任何一格。
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                SummaryTile("全年收入", MoneyFormat.currency(summary.totalIncome), Modifier.weight(1f))
                                SummaryTile("全年支出", MoneyFormat.currency(summary.totalExpense), Modifier.weight(1f))
                            }
                            SummaryTile(
                                "結構缺口（收入 − 支出）", MoneyFormat.signed(summary.structuralGap), Modifier.fillMaxWidth(),
                                valueColor = if (summary.structuralGap < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            // 支出＝一定要付出去的錢：貸款月繳與繳卡費都在裡面（R-PLS-04）。
                            HintText(
                                "支出含貸款月繳 ${MoneyFormat.currency(summary.totalLoanPayments)}" +
                                    "（利息 ${MoneyFormat.currency(summary.totalLoanInterest)}）" +
                                    "、繳卡費 ${MoneyFormat.currency(summary.totalCardPayments)}" +
                                    "（循環利息 ${MoneyFormat.currency(summary.totalCardInterest)}）",
                            )
                            // 編列的是「打算花多少」，支出是「要付出去多少」——刷卡隔月繳，兩者本來就不一樣（R-PLS-10）。
                            if (summary.totalCardSpending > 0) {
                                HintText(
                                    "今年打算花 ${MoneyFormat.currency(summary.totalPlannedExpense)}" +
                                        "（刷卡 ${MoneyFormat.currency(summary.totalCardSpending)}" +
                                        " ＋ 非刷卡 ${MoneyFormat.currency(summary.totalNonCardSpending)}）：" +
                                        "刷的要等繳卡費才真的出去，所以和上面的支出不一樣",
                                )
                            }
                            // 還本金不是花掉，是把負債換成淨值，所以另外標出來（R-PLS-04）。
                            if (summary.debtPrincipal != 0L) {
                                HintText(
                                    "其中 ${MoneyFormat.currency(summary.debtPrincipal)} 是在還債務本金；" +
                                        "不算還本金的話，費用比收入${if (summary.gapWithoutPrincipal < 0) "多" else "少"} " +
                                        MoneyFormat.currency(kotlin.math.abs(summary.gapWithoutPrincipal)),
                                )
                            }
                            // 卡債的標題跟著方向走（R-PLS-07）：不會用「增加」描述一個負數。
                            if (summary.cards.isNotEmpty()) {
                                HintText(
                                    "${summary.cardDebtLabel} ${MoneyFormat.currency(kotlin.math.abs(summary.cardDebtChange))}：" +
                                        "年初 ${MoneyFormat.currency(summary.cardDebtStart)} → " +
                                        "年底 ${MoneyFormat.currency(summary.cardDebtEnd)}",
                                    color = if (summary.cardDebtChange > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 逐卡把「刷多少 vs 繳多少」講出來（R-PLS-10）：這是編刷卡列時最需要看到的一句話。
                                summary.cards.filter { it.spending > 0 || it.payments > 0 }.forEach { card ->
                                    HintText(
                                        "${card.name}：刷 ${MoneyFormat.currency(card.spending)}" +
                                            "、利息 ${MoneyFormat.currency(card.interest)}" +
                                            "、繳 ${MoneyFormat.currency(card.payments)}" +
                                            " → 年底 ${MoneyFormat.currency(card.end)}",
                                        color = if (card.change > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (summary.loans.isNotEmpty()) {
                                HintText(
                                    "貸款年底還欠 ${MoneyFormat.currency(summary.loanDebtEnd)}" +
                                        "（年初 ${MoneyFormat.currency(summary.loanDebtStart)}）",
                                )
                            }
                            // 自動產生的貸款與卡費只算「有編計畫、而且今天以後」的月份（R-PLS-05）。
                            if (summary.monthCount in 1..11) {
                                HintText(
                                    "貸款與卡費只算 ${summary.autoMonths.first()}–${summary.autoMonths.last()} 月" +
                                        "（共 ${summary.monthCount} 個月）：有編計畫、而且今天以後的月份才預測",
                                    color = MaterialTheme.warningColors.warning,
                                )
                            }
                        }
                    }
                }

                val outlook = state.outlook
                if (outlook != null && outlook.hasCurve) {
                    item(key = "cash") {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text("未來現金水位", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                outlook.monthsUntilBelowSafety?.let { months ->
                                    StatusBadge(
                                        if (months <= 0) "本月低於安全線" else "約 $months 個月後低於安全線",
                                        tone = Tone.WARNING,
                                        icon = Icons.Rounded.Warning,
                                    )
                                }
                            }
                            CashLineChart(
                                series = listOf(ChartSeries("現況", MaterialTheme.chartColors.at(0), outlook.monthlyLows)),
                                labels = outlook.monthLabels,
                                safetyLevel = outlook.safetyLevel,
                                height = 120.dp,
                                interactive = false,
                            )
                            Text(
                                "最低 ${MoneyFormat.currency(outlook.lowest)}" + (outlook.lowestLabel?.let { "（$it）" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (outlook.lowest < outlook.safetyLevel) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            HintText("一個月一個點，就是月底還剩多少。照這份計畫走下去的結果，還沒有改任何東西。")
                            outlook.shortfall?.let { HintText(it) }
                            TextButton(onClick = onOpenForecast, modifier = Modifier.padding(start = 0.dp)) { Text("到試算比較不同做法") }
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
                val table = state.table
                if (state.showTable && table != null) {
                    item(key = "table") { PlanMonthTable(table, onEditItem, onTableView) }
                } else if (state.rows.isNotEmpty()) {
                    state.rows.groupBy { it.groupName }.forEach { (group, rows) ->
                        item(key = "group-$group") { PlanRowGroup(group, rows, onEditItem) }
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

/** 一個群組的計畫列。 */
@Composable
private fun PlanRowGroup(title: String, rows: List<PlanRow>, onEditItem: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GroupLabel(title)
        ListCard {
            rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider()
                Surface(onClick = { onEditItem(row.itemId) }, color = Color.Transparent) {
                    ListRow(
                        title = row.itemName,
                        detail = row.detail,
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
                SummaryTile("項目列", "${preview.lineCount} 列", Modifier.weight(1f))
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
