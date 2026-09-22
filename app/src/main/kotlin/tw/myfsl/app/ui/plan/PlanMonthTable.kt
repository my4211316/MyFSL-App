package tw.myfsl.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import tw.myfsl.app.ui.components.MethodIcon
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.ui.theme.Spacing
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.PlanTable
import tw.myfsl.app.core.domain.PlanTableView
import tw.myfsl.app.core.domain.TableRow
import tw.myfsl.app.core.domain.TableRowKind
import tw.myfsl.app.core.model.MoneyFormat

private val RowHeight = 36.dp
private val NameWidth = 132.dp
private val CellWidth = 64.dp
private val TotalWidth = 80.dp

/**
 * 年度月份表：左邊項目名稱固定，右邊 12 個月加全年可以橫向捲動。
 * 當月欄加底色；點項目列可以編輯；自動估算的列以灰字顯示。
 */
@Composable
fun PlanMonthTable(
    table: PlanTable,
    onEditItem: (Long) -> Unit,
    onView: (PlanTableView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val highlight = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    // 同一張表兩個問題（R-PLS-10）：刷卡是隔月繳的，「花」和「付」不在同一個月。
    SegmentedChoice(PlanTableView.entries, table.view, { it.label }, onView, Modifier.padding(bottom = Spacing.sm))

    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(NameWidth)) {
            HeaderCell("項目", Modifier.width(NameWidth), TextAlign.Start)
            table.rows.forEach { row ->
                val clickable = row.itemId != null
                Row(
                    Modifier.width(NameWidth).height(RowHeight).background(rowBackground(row))
                        .then(if (clickable) Modifier.clickable { onEditItem(row.itemId!!) } else Modifier)
                        .padding(horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label + if (row.flexible) "・可調" else "",
                        style = if (row.kind == TableRowKind.ITEM) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                        color = textColor(row),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(Modifier.horizontalScroll(scroll)) {
            Row {
                (1..12).forEach { m ->
                    HeaderCell("${m}月", Modifier.width(CellWidth).background(if (m == table.currentMonth) highlight else Color.Transparent))
                }
                HeaderCell("全年", Modifier.width(TotalWidth))
            }
            table.rows.forEach { row ->
                Row(Modifier.height(RowHeight).background(rowBackground(row))) {
                    if (row.kind == TableRowKind.GROUP) {
                        Box(Modifier.width(CellWidth * 12 + TotalWidth).height(RowHeight))
                    } else {
                        row.monthly.forEachIndexed { i, v ->
                            ValueCell(
                                row, v, Modifier.width(CellWidth).background(if (i + 1 == table.currentMonth) highlight else Color.Transparent),
                                month = i + 1,
                            )
                        }
                        ValueCell(row, row.total, Modifier.width(TotalWidth), bold = true)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        "向左滑看其他月份。點項目可以修改；灰字是依貸款與卡片條件自動估算的。" + when (table.view) {
            PlanTableView.SPEND ->
                "刷卡那一列是刷的月份，要等繳卡費才真的出去，所以一欄加起來不是那個月的現金——" +
                    "想看每個月要準備多少，切到「什麼時候付」。"

            PlanTableView.CASH ->
                "這裡的每一格都是那個月真的從帳戶出去的錢：刷卡不在這裡，繳卡費才在。" +
                    "1 月的繳卡費繳的是去年 12 月的帳單，12 月刷的要明年 1 月才繳。" +
                    "「已過」是今天以前的月份——那些已經發生過了，不再推估；實際繳了多少在「紀錄」裡看。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun rowBackground(row: TableRow): Color = when (row.kind) {
    TableRowKind.GROUP -> MaterialTheme.colorScheme.surfaceContainer
    TableRowKind.TOTAL, TableRowKind.CASH_FLOW -> MaterialTheme.colorScheme.surfaceContainerLow
    TableRowKind.ITEM -> Color.Transparent
}

@Composable
private fun textColor(row: TableRow): Color = when {
    row.auto -> MaterialTheme.colorScheme.onSurfaceVariant
    row.kind == TableRowKind.GROUP -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.End) {
    Box(modifier.height(RowHeight).padding(horizontal = Spacing.xs), contentAlignment = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.CenterEnd) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
    }
}

@Composable
private fun ValueCell(row: TableRow, value: Long, modifier: Modifier, bold: Boolean = false, month: Int? = null) {
    // 「0」和「這個月已經過去、不再推估」要看得出差別（R-PLS-11）：金額 0 沿用「—」，過去的月份寫「已過」。
    if (month != null && month in row.pastMonths) {
        Box(modifier.height(RowHeight).padding(horizontal = Spacing.xs), contentAlignment = Alignment.CenterEnd) {
            Text("已過", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outlineVariant, maxLines = 1)
        }
        return
    }
    val negative = row.kind == TableRowKind.CASH_FLOW && value < 0
    Box(modifier.height(RowHeight).padding(horizontal = Spacing.xs), contentAlignment = Alignment.CenterEnd) {
        Text(
            if (value == 0L) "—" else MoneyFormat.compact(value),
            style = if (bold || row.kind != TableRowKind.ITEM) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            color = when {
                negative -> MaterialTheme.colorScheme.error
                value == 0L -> MaterialTheme.colorScheme.outlineVariant
                else -> textColor(row)
            },
            maxLines = 1,
        )
    }
}
