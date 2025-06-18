package com.example.myfsl

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

            // 圖表功能提示（暫時替代）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📊 圖表功能",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "收支趨勢圖表功能即將推出",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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