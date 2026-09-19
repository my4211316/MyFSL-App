package tw.myfsl.app.ui.forecast

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.Comparison
import tw.myfsl.app.core.domain.ScenarioOutcome
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.ui.components.CashLineChart
import tw.myfsl.app.ui.components.ChartSeries
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.components.StatePane
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.theme.Spacing
import tw.myfsl.app.ui.theme.chartColors
import java.util.Locale

/**
 * 試算（設計稿：全畫面改版 v1）：多情境現金水位比較、比較表、我的情境、反推、信用卡怎麼推估。
 * 線色依情境的固定順序取主題圖表色；表格裡的情境名稱用一般字色加色塊（dataviz skill：文字不用線的顏色）。
 */
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
        Loading(modifier)
        return
    }
    state.editor?.let {
        editorContent(it)
        return
    }
    val comparison = state.comparison
    if (comparison == null) {
        Column(modifier.fillMaxSize()) {
            ScreenTopBar("試算")
            StatePane(null, "還沒有資料", "先新增帳戶並建立年度計畫，才能試算未來的現金水位。")
        }
        return
    }
    val chart = MaterialTheme.chartColors

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("試算")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                item(key = "months") {
                    SegmentedChoice(listOf(12, 24, 36), state.months, { "$it 個月" }, onMonths)
                }
                item(key = "chart") {
                    val visible = comparison.outcomes.withIndex().filter { (_, o) -> o.scenario?.id?.let { it !in state.hidden } ?: true }
                    SectionCard {
                        Text("每月最低現金水位", style = MaterialTheme.typography.titleSmall)
                        CashLineChart(
                            series = visible.map { (i, o) -> ChartSeries(o.name, chart.at(i), o.monthlyLows) },
                            labels = comparison.labels,
                            safetyLevel = comparison.safetyLevel,
                            height = 200.dp,
                            showLegend = true,
                        )
                    }
                }
                item(key = "table") { ComparisonTable(comparison) }

                item(key = "mine") { SectionHeader("我的情境") }
                if (comparison.outcomes.size == 1) {
                    item(key = "mine-empty") {
                        HintText("還沒有情境。按右下「新增情境」試試：貸款整合卡債、可調支出減 10%、刷卡改現金…")
                    }
                }
                comparison.outcomes.withIndex().filter { it.value.scenario != null }.forEach { (index, outcome) ->
                    val scenario = outcome.scenario!!
                    item(key = "scenario-${scenario.id}") {
                        ScenarioCard(scenario, chart.at(index), scenario.id !in state.hidden, state.descriptions[scenario.id].orEmpty(), onEdit, onToggleVisible)
                    }
                }

                item(key = "seek") { SeekCard(state, onToggleSeek, onSeekTarget, onSeekItem, onRunSeek, onSaveSeek) }

                if (state.cardNotes.isNotEmpty()) {
                    item(key = "cards") {
                        SectionCard {
                            Text("信用卡怎麼推估", style = MaterialTheme.typography.titleSmall)
                            state.cardNotes.forEach { HintText(it) }
                        }
                    }
                }
                item(key = "disclaimer") { HintText("試算只是依你輸入的計畫與假設推算，不是財務建議。") }
                item(key = "end") { Spacer(Modifier.height(88.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("新增情境") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
        )
    }
}

/** 一個情境：色塊＋名稱＋「顯示於圖表」開關＋變動摘要；點卡片編輯。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioCard(
    scenario: Scenario,
    color: Color,
    visible: Boolean,
    descriptions: List<String>,
    onEdit: (Scenario) -> Unit,
    onToggleVisible: (Long) -> Unit,
) {
    Surface(onClick = { onEdit(scenario) }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(12.dp).background(color, MaterialTheme.shapes.extraSmall))
                Text(scenario.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("顯示於圖表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked = visible,
                    onCheckedChange = { onToggleVisible(scenario.id) },
                    modifier = Modifier.semantics { contentDescription = "${scenario.name} 顯示於圖表" },
                )
            }
            if (descriptions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    descriptions.forEach { AssistChip(onClick = { onEdit(scenario) }, label = { Text(it) }) }
                }
            }
            if (scenario.note.isNotBlank()) HintText(scenario.note)
        }
    }
}

/** 比較表：左邊指標固定，情境欄可以左右滑；各列最好的數字用粗一級的字，會出問題的用錯誤色。 */
@Composable
private fun ComparisonTable(c: Comparison) {
    data class Metric(val label: String, val value: (ScenarioOutcome) -> String, val best: (ScenarioOutcome) -> Boolean, val bad: (ScenarioOutcome) -> Boolean)

    val metrics = listOf(
        Metric("最低水位", { MoneyFormat.currency(it.lowest) + (it.lowestLabel?.let { l -> "\n$l" } ?: "") }, { it.lowest == c.bestLowest }, { it.lowest < 0 }),
        Metric("低於安全線", { it.firstBelowSafetyLabel ?: "不會" }, { it.firstBelowSafetyLabel == null }, { it.firstBelowSafetyLabel != null }),
        Metric("年化缺口", { MoneyFormat.signed(it.structuralGapPerYear) }, { it.structuralGapPerYear == c.bestGap }, { it.structuralGapPerYear < 0 }),
        Metric("扣款不足", { it.shortfallLabel ?: "不會" }, { it.shortfallLabel == null }, { it.shortfallLabel != null }),
        Metric("期末卡債", { MoneyFormat.currency(it.endCardDebt) }, { it.endCardDebt == c.bestCardDebt }, { false }),
        Metric("期末總負債", { MoneyFormat.currency(it.endTotalDebt) }, { it.endTotalDebt == c.bestTotalDebt }, { false }),
        Metric("循環利息", { MoneyFormat.currency(it.cardInterest) }, { it.cardInterest == c.bestInterest }, { false }),
    )
    val multi = c.outcomes.size > 1
    val chart = MaterialTheme.chartColors

    SectionCard {
        Text("${c.months} 個月比較", style = MaterialTheme.typography.titleSmall)
        Row {
            Column {
                Cell("", header = true, width = 80)
                metrics.forEach { Cell(it.label, header = true, width = 80, start = true) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                c.outcomes.forEachIndexed { i, o ->
                    Column {
                        Row(Modifier.width(112.dp).height(48.dp).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                            Box(Modifier.size(12.dp).background(chart.at(i), MaterialTheme.shapes.extraSmall))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(o.name, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                        }
                        metrics.forEach { m ->
                            Cell(m.value(o), bold = multi && m.best(o), color = if (m.bad(o)) MaterialTheme.colorScheme.error else Color.Unspecified)
                        }
                    }
                }
            }
        }
        if (multi) HintText("粗體是各列最好的數字；左右滑看其他情境。")
        HintText(
            "最低水位是以半月為單位、同半月內支出先於收入的估算，不是某一天的銀行餘額。" +
                "年化缺口＝(收入 − 支出 − 貸款本金) × 24 ÷ 期數，支出含利息與分期手續費。期末總負債含未入帳的分期本金。",
        )
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false, bold: Boolean = false, color: Color = Color.Unspecified, width: Int = 112, start: Boolean = false) {
    Text(
        text,
        style = when {
            header -> MaterialTheme.typography.bodySmall
            bold -> MaterialTheme.typography.labelMedium
            else -> MaterialTheme.typography.bodySmall
        },
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else color,
        textAlign = if (start) TextAlign.Start else TextAlign.End,
        modifier = Modifier.width(width.dp).height(48.dp).padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    )
}

/** 反推目標：選的項目要一起減多少才能達成。 */
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
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("反推目標", style = MaterialTheme.typography.titleSmall)
                HintText("選的項目要一起減多少，才能達成目標")
            }
            TextButton(onClick = onToggle) { Text(if (state.seekOpen) "收起" else "開始反推") }
        }
        if (!state.seekOpen) return@SectionCard

        ChoiceChips(SeekTarget.entries, { seek.target == it }, { it.label }, onTarget)
        FieldLabel("要減的項目（預設為可調支出）")
        ChoiceChips(state.expenseItems, { it.id in seek.itemIds }, { it.name }, { onItem(it.id) })
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
            if (seek.achievable) Text(text, style = MaterialTheme.typography.bodyMedium) else WarningText(text)
            seek.lowestAfter?.let { HintText("減完後最低水位 ${MoneyFormat.currency(it)}") }
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
