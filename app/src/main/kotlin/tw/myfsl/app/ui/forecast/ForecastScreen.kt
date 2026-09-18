package tw.myfsl.app.ui.forecast

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.Comparison
import tw.myfsl.app.core.domain.ScenarioOutcome
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.ui.components.CashLineChart
import tw.myfsl.app.ui.components.ChartSeries
import tw.myfsl.app.ui.components.SeriesColors
import tw.myfsl.app.ui.theme.StatusColors
import java.util.Locale

/** 試算：多情境現金水位比較、比較表、我的情境、反推。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForecastScreen(
    state: ForecastUiState,
    onMonths: (Int) -> Unit,
    onToggleVisible: (Long) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Scenario) -> Unit,
    onToggleSeek: () -> Unit,
    onSeekTarget: (SeekTarget) -> Unit,
    onSeekItem: (Long) -> Unit,
    onRunSeek: () -> Unit,
    onSaveSeek: () -> Unit,
    editorContent: @Composable (ScenarioEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    state.editor?.let {
        editorContent(it)
        return
    }
    val comparison = state.comparison
    if (comparison == null) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("試算", style = MaterialTheme.typography.titleLarge)
            Text("還沒有資料。先新增帳戶並建立年度計畫，才能試算未來的現金水位。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("試算", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    listOf(12, 24, 36).forEach { m ->
                        FilterChip(selected = state.months == m, onClick = { onMonths(m) }, label = { Text("$m 個月") }, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            item {
                val visible = comparison.outcomes.withIndex().filter { (_, o) -> o.scenario?.id?.let { it !in state.hidden } ?: true }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("每月最低現金水位", style = MaterialTheme.typography.titleSmall)
                        CashLineChart(
                            series = visible.map { (i, o) -> ChartSeries(o.name, SeriesColors.at(i), o.monthlyLows) },
                            labels = comparison.labels,
                            safetyLevel = comparison.safetyLevel,
                            height = 200.dp,
                            showLegend = true,
                        )
                    }
                }
            }

            item { ComparisonTable(comparison) }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的情境", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                if (comparison.outcomes.size == 1) {
                    Text(
                        "還沒有情境。按「新增情境」試試：貸款整合卡債、可調支出減 10%、刷卡改現金…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            comparison.outcomes.withIndex().filter { it.value.scenario != null }.forEach { (index, outcome) ->
                val scenario = outcome.scenario!!
                item(key = "scenario-${scenario.id}") {
                    Card(
                        onClick = { onEdit(scenario) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(14.dp).height(3.dp).padding(end = 0.dp)) {
                                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(SeriesColors.at(index)) }
                                }
                                Text(scenario.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                Text("顯示於圖表", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(checked = scenario.id !in state.hidden, onCheckedChange = { onToggleVisible(scenario.id) }, modifier = Modifier.padding(start = 6.dp))
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.descriptions[scenario.id].orEmpty().forEach { AssistChip(onClick = { onEdit(scenario) }, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
                            }
                            if (scenario.note.isNotBlank()) Text(scenario.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { SeekCard(state, onToggleSeek, onSeekTarget, onSeekItem, onRunSeek, onSaveSeek) }
            item {
                Text(
                    "試算只是依你輸入的計畫與假設推算，不是財務建議。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("新增情境") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun ComparisonTable(c: Comparison) {
    data class Metric(val label: String, val value: (ScenarioOutcome) -> String, val best: (ScenarioOutcome) -> Boolean, val bad: (ScenarioOutcome) -> Boolean)

    val metrics = listOf(
        Metric("最低水位\n（半月估算）", { MoneyFormat.currency(it.lowest) + (it.lowestLabel?.let { l -> "\n$l" } ?: "") }, { it.lowest == c.bestLowest }, { it.lowest < 0 }),
        Metric("低於安全線", { it.firstBelowSafetyLabel ?: "不會" }, { it.firstBelowSafetyLabel == null }, { it.firstBelowSafetyLabel != null }),
        Metric("期間年化缺口", { MoneyFormat.signed(it.structuralGapPerYear) }, { it.structuralGapPerYear == c.bestGap }, { it.structuralGapPerYear < 0 }),
        Metric("扣款帳戶不足", { it.shortfallLabel ?: "不會" }, { it.shortfallLabel == null }, { it.shortfallLabel != null }),
        Metric("期末卡債", { MoneyFormat.currency(it.endCardDebt) }, { it.endCardDebt == c.bestCardDebt }, { false }),
        Metric("期末總負債", { MoneyFormat.currency(it.endTotalDebt) }, { it.endTotalDebt == c.bestTotalDebt }, { false }),
        Metric("循環利息合計", { MoneyFormat.currency(it.cardInterest) }, { it.cardInterest == c.bestInterest }, { false }),
    )
    val multi = c.outcomes.size > 1

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Text("${c.months} 個月比較", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Cell("", header = true, width = 88)
                    metrics.forEach { Cell(it.label, header = true, width = 88) }
                }
                c.outcomes.forEachIndexed { i, o ->
                    Column {
                        Cell(o.name, header = true, color = SeriesColors.at(i))
                        metrics.forEach { m ->
                            Cell(
                                m.value(o),
                                bold = multi && m.best(o),
                                color = if (m.bad(o)) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        }
                    }
                }
            }
            if (multi) Text("粗體是各列最好的數字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "最低水位：以半月為單位、同半月內支出先於收入的估算，不是精確某一天的銀行餘額。" +
                    "期間年化缺口＝(收入 − 支出 − 貸款本金) × 24 ÷ 期數，支出含利息與分期手續費。" +
                    "期末總負債含未入帳的分期本金。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false, bold: Boolean = false, color: Color = Color.Unspecified, width: Int = 112) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        color = color,
        textAlign = if (header && width == 88) TextAlign.Start else TextAlign.End,
        modifier = Modifier.width(width.dp).height(40.dp).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekCard(
    state: ForecastUiState,
    onToggle: () -> Unit,
    onTarget: (SeekTarget) -> Unit,
    onItem: (Long) -> Unit,
    onRun: () -> Unit,
    onSave: () -> Unit,
) {
    val seek = state.seek
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("反推目標", style = MaterialTheme.typography.titleSmall)
                    Text("選的項目要一起減多少，才能達成目標", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggle) { Text(if (state.seekOpen) "收起" else "開始反推") }
            }
            if (!state.seekOpen) return@Column

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeekTarget.entries.forEach { t -> FilterChip(selected = seek.target == t, onClick = { onTarget(t) }, label = { Text(t.label) }) }
            }
            Text("要減的項目（預設為可調支出）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.expenseItems.forEach { item ->
                    FilterChip(selected = item.id in seek.itemIds, onClick = { onItem(item.id) }, label = { Text(item.name) })
                }
            }
            Button(onClick = onRun, enabled = seek.itemIds.isNotEmpty() && !seek.running) { Text(if (seek.running) "計算中…" else "計算") }

            val percent = seek.cutPercent
            if (percent != null) {
                val text = when {
                    seek.nothingToCut -> "選的項目在這段期間沒有可以減的計畫金額（既有分期與延期款不會被減），請換別的項目。"
                    seek.lowBeforeStart -> "最低點發生在下個月開始減少之前，怎麼減都來不及；要先處理這個月的現金（例如延後付款或轉入存款）。"
                    percent == 0.0 -> "現況已經達成，不用減。"
                    !seek.achievable -> "這些項目全部停掉也達不到，要搭配其他做法（例如整合貸款、增加收入）。"
                    else -> "從下個月起，這些項目一起減少 ${String.format(Locale.US, "%.1f", percent)}% 才能達成。"
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (seek.achievable) MaterialTheme.colorScheme.onSurface else StatusColors.warningText)
                seek.lowestAfter?.let { Text("減完後最低水位 ${MoneyFormat.currency(it)}", style = MaterialTheme.typography.bodySmall) }
                if (seek.achievable && percent > 0) {
                    seek.cutsPerYear.take(8).forEach { (name, amount): Pair<String, Money> ->
                        Row {
                            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("每年少 ${MoneyFormat.currency(amount)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = onSave) { Text("存成情境") }
                }
            }
        }
    }
}
