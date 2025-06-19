package com.example.myfsl

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
// Vico 1.14.0 的正確 imports
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val dashboardState by vm.dashboardState.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "總覽",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // 錯誤訊息顯示
        errorMessage?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "載入錯誤",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = vm::retryLoadData) {
                                Text("重試")
                            }
                            TextButton(onClick = vm::clearErrorMessage) {
                                Text("關閉")
                            }
                        }
                    }
                }
            }
        }

        // 載入指示器
        if (dashboardState.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            // KPI 卡片 - 第一排
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KPICard(
                        title = "本月淨收支",
                        value = "NT$ ${"%,.0f".format(dashboardState.thisMonthIncome - dashboardState.thisMonthExpense)}",
                        modifier = Modifier.weight(1f)
                    )
                    KPICard(
                        title = "目前總淨值",
                        value = "NT$ ${"%,.0f".format(dashboardState.totalNetWorth)}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // KPI 卡片 - 第二排
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KPICard(
                        title = "本月總收入",
                        value = "NT$ ${"%,.0f".format(dashboardState.thisMonthIncome)}",
                        color = Color(0xFF09814A),
                        modifier = Modifier.weight(1f)
                    )
                    KPICard(
                        title = "本月總支出",
                        value = "NT$ ${"%,.0f".format(dashboardState.thisMonthExpense)}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 預算狀況
            item {
                BudgetStatusList(states = dashboardState.categoryBudgetStates)
            }

            // 圖表
            item {
                if (dashboardState.chartModelProducer != null && dashboardState.chartXAxisLabels.isNotEmpty()) {
                    ChartCard(
                        modelProducer = dashboardState.chartModelProducer!!,
                        xAxisLabels = dashboardState.chartXAxisLabels
                    )
                }
            }
        }
    }
}

@Composable
fun BudgetStatusList(states: List<CategoryBudgetState>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "本月預算狀況",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (states.isEmpty()) {
            Text(
                text = "無預算資料...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        } else {
            states.forEach { state ->
                BudgetStatusRow(state = state)
            }
        }
    }
}

@Composable
fun BudgetStatusRow(state: CategoryBudgetState) {
    val progress by animateFloatAsState(
        targetValue = state.percentage.coerceIn(0f, 1f),
        label = "budget_progress"
    )
    val progressColor = if (state.percentage > 1.0f) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.categoryName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            // 百分比顯示
            Text(
                text = "${(state.percentage * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = progressColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (state.percentage <= 1.0f) {
                    "剩餘 ${"%,.0f".format(state.budgetAmount - state.spentAmount)}"
                } else {
                    "超支 ${"%,.0f".format(state.spentAmount - state.budgetAmount)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.percentage > 1.0f) progressColor else Color.Gray,
                fontWeight = if (state.percentage > 1.0f) FontWeight.Bold else FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = progressColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "已花費 ${"%,.0f".format(state.spentAmount)} / 預算 ${"%,.0f".format(state.budgetAmount)}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

@Composable
fun KPICard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ChartCard(
    modelProducer: ChartEntryModelProducer,
    xAxisLabels: List<String>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "近六個月收支趨勢",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Vico 1.14.0 的簡化圖表實現
            ProvideChartStyle {
                Chart(
                    chart = columnChart(),
                    chartModelProducer = modelProducer,
                    modifier = Modifier.height(180.dp)
                )
            }
        }
    }
}