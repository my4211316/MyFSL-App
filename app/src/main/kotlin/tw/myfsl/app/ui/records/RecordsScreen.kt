package tw.myfsl.app.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.myfsl.app.ui.theme.StatusColors

/** 紀錄：逐筆看、刪掉記錯的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    state: RecordsUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFilter: (RecordFilter) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    editorContent: @Composable (RecordEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<RecordRow?>(null) }
    state.editor?.let {
        Box(modifier.fillMaxSize()) { editorContent(it) }
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("紀錄") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回記帳")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.ChevronLeft, contentDescription = "上個月") }
                    Text(
                        state.monthLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = onNext, enabled = state.canGoNext) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "下個月")
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Total("本月至今支出", state.expenseTotal, Modifier.weight(1f))
                    Total("本月至今收入", state.incomeTotal, Modifier.weight(1f))
                }
            }

            state.missedLabel?.let { label ->
                item {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warningText,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecordFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilter(filter) },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            if (state.empty) {
                item {
                    Text(
                        "這個月還沒有紀錄",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            state.days.forEach { day ->
                item(key = "day-${day.date}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(day.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            day.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }

                items(day.rows, key = { "row-${it.id}" }) { row ->
                    RecordItem(row = row, onClick = { onEdit(row.id) }, onDelete = { pendingDelete = row })
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("刪除這筆？") },
            text = {
                Text(
                    "${row.title} ${row.amountText}\n${row.subtitle}" +
                        if (row.sourceLabel?.startsWith("分") == true) "\n分期消費會連同尚未入帳的各期一起刪除。" else "",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(row.id)
                        pendingDelete = null
                    },
                ) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Total(label: String, amount: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(amount, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecordItem(row: RecordRow, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "修改", onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                row.sourceLabel?.let {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(it, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = StatusColors.warningContainer,
                            disabledLabelColor = StatusColors.onWarningContainer,
                        ),
                    )
                }
            }
            if (row.subtitle.isNotBlank()) {
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            row.amountText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = "刪除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
