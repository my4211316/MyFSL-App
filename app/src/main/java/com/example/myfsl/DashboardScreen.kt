package com.example.myfsl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.axis.horizontal.bottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.startAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val transactions by viewModel.transactions.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("zh", "TW")).apply {
        currency = Currency.getInstance("TWD")
    }

    // 計算統計數據
    val totalIncome = viewModel.getTotalIncome()
    val totalExpenses = viewModel.getTotalExpenses()
    val monthlyIncome = viewModel.getMonthlyIncome()
    val monthlyExpenses = viewModel.getMonthlyExpenses()
    val expensesByCategory = viewModel.getExpensesByCategory()
    val recentTransactions = viewModel.getRecentTransactions()

    // 圖表數據
    val chartData = viewModel.getChartData()
    val chartEntryModel = entryModelOf(*chartData.toTypedArray())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 標題
        Text(
            text = "財務儀表板",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 載入指示器
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // 錯誤訊息
        errorMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 月度概覽卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 本月收入
            OverviewCard(
                title = "本月收入",
                amount = monthlyIncome,
                icon = Icons.Filled.TrendingUp,
                iconTint = Color(0xFF4CAF50),
                currencyFormatter = currencyFormatter,
                modifier = Modifier.weight(1f)
            )

            // 本月支出
            OverviewCard(
                title = "本月支出",
                amount = monthlyExpenses,
                icon = Icons.Filled.TrendingDown,
                iconTint = Color(0xFFF44336),
                currencyFormatter = currencyFormatter,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 總體概覽卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 總收入
            OverviewCard(
                title = "總收入",
                amount = totalIncome,
                icon = Icons.Filled.AccountBalance,
                iconTint = MaterialTheme.colorScheme.primary,
                currencyFormatter = currencyFormatter,
                modifier = Modifier.weight(1f)
            )

            // 總支出
            OverviewCard(
                title = "總支出",
                amount = totalExpenses,
                icon = Icons.Filled.Payments,
                iconTint = MaterialTheme.colorScheme.error,
                currencyFormatter = currencyFormatter,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 支出圖表
        if (transactions.isNotEmpty() && chartData.any { it > 0 }) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "近7天支出趨勢",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Filled.Timeline,
                            contentDescription = "圖表",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Chart(
                        chart = columnChart(),
                        model = chartEntryModel,
                        startAxis = startAxis(),
                        bottomAxis = bottomAxis(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 分類支出統計
        if (expensesByCategory.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "分類支出統計",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Filled.PieChart,
                            contentDescription = "分類統計",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    expensesByCategory.toList()
                        .sortedByDescending { it.second }
                        .take(5)
                        .forEach { (category, amount) ->
                            CategoryExpenseItem(
                                category = category,
                                amount = amount,
                                percentage = (amount / totalExpenses * 100).toFloat(),
                                currencyFormatter = currencyFormatter
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 預算警示
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonthBudgets = budgets.filter {
            it.month == currentMonth.toString().padStart(2, '0') && it.year == currentYear
        }

        if (currentMonthBudgets.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "本月預算狀況",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = "預算",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    currentMonthBudgets.forEach { budget ->
                        BudgetStatusItem(
                            budget = budget,
                            currencyFormatter = currencyFormatter
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 最近交易
        if (recentTransactions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近交易",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Filled.Receipt,
                            contentDescription = "交易記錄",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    recentTransactions.forEach { transaction ->
                        RecentTransactionItem(
                            transaction = transaction,
                            currencyFormatter = currencyFormatter
                        )
                        if (transaction != recentTransactions.last()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverviewCard(
    title: String,
    amount: Double,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    currencyFormatter: NumberFormat,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = title,
                modifier = Modifier.size(32.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currencyFormatter.format(amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CategoryExpenseItem(
    category: String,
    amount: Double,
    percentage: Float,
    currencyFormatter: NumberFormat
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                progress = percentage / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${String.format("%.1f", percentage)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = currencyFormatter.format(amount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BudgetStatusItem(
    budget: Budget,
    currencyFormatter: NumberFormat
) {
    val usagePercentage = if (budget.limit > 0) {
        (budget.spent / budget.limit * 100).coerceAtMost(100.0)
    } else 0.0

    val statusColor = when {
        usagePercentage >= 100 -> MaterialTheme.colorScheme.error
        usagePercentage >= 80 -> Color(0xFFFF9800)  // 警告橘色
        else -> MaterialTheme.colorScheme.primary
    }

    val statusIcon = when {
        usagePercentage >= 100 -> Icons.Filled.Warning
        usagePercentage >= 80 -> Icons.Filled.Error
        else -> Icons.Filled.CheckCircle
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            statusIcon,
            contentDescription = "狀態",
            tint = statusColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = budget.category,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                progress = (usagePercentage / 100).toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = statusColor
            )
            Text(
                text = "${currencyFormatter.format(budget.spent)} / ${currencyFormatter.format(budget.limit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${String.format("%.0f", usagePercentage)}%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )
    }
}

@Composable
fun RecentTransactionItem(
    transaction: Transaction,
    currencyFormatter: NumberFormat
) {
    val dateFormatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 交易類型圖示
        Icon(
            if (transaction.isExpense) Icons.Filled.TrendingDown else Icons.Filled.TrendingUp,
            contentDescription = if (transaction.isExpense) "支出" else "收入",
            tint = if (transaction.isExpense) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (transaction.notes.isNotEmpty()) transaction.notes else "無備註",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row {
                Text(
                    text = transaction.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dateFormatter.format(transaction.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "${if (transaction.isExpense) "-" else "+"} ${currencyFormatter.format(transaction.amount)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (transaction.isExpense) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
        )
    }
}