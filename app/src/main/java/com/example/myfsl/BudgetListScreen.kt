package com.example.myfsl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetListScreen(
    viewModel: MainViewModel,
    onNavigateToBudgetDetail: (String) -> Unit
) {
    val budgets by viewModel.budgets.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showAddBudgetDialog by remember { mutableStateOf(false) }
    var selectedMonth by remember { mutableStateOf(getCurrentMonth()) }
    var selectedYear by remember { mutableStateOf(getCurrentYear()) }

    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("zh", "TW")).apply {
        currency = Currency.getInstance("TWD")
    }

    // 篩選當前月份的預算
    val filteredBudgets = budgets.filter {
        it.month == selectedMonth && it.year == selectedYear
    }

    // 可用的分類 (排除已有預算的分類)
    val availableCategories = categories
        .filter { it.isExpense }
        .filter { category ->
            filteredBudgets.none { budget -> budget.category == category.name }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 標題和新增按鈕
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "預算管理",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            FloatingActionButton(
                onClick = { showAddBudgetDialog = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新增預算")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 月份年份選擇器
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "檢視月份：",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 月份選擇
                    OutlinedButton(
                        onClick = {
                            // TODO: 實作月份選擇器
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("$selectedMonth 月")
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "選擇月份"
                        )
                    }

                    // 年份選擇
                    OutlinedButton(
                        onClick = {
                            // TODO: 實作年份選擇器
                        }
                    ) {
                        Text("$selectedYear 年")
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "選擇年份"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 預算概覽統計
        if (filteredBudgets.isNotEmpty()) {
            val totalBudget = filteredBudgets.sumOf { it.limit }
            val totalSpent = filteredBudgets.sumOf { it.spent }
            val totalRemaining = totalBudget - totalSpent
            val overallUsage = if (totalBudget > 0) (totalSpent / totalBudget * 100) else 0.0

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "${selectedMonth}/${selectedYear} 預算概覽",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BudgetSummaryItem(
                            title = "總預算",
                            amount = totalBudget,
                            icon = Icons.Filled.AccountBalance,
                            iconTint = MaterialTheme.colorScheme.primary,
                            currencyFormatter = currencyFormatter
                        )

                        BudgetSummaryItem(
                            title = "已花費",
                            amount = totalSpent,
                            icon = Icons.Filled.Payments,
                            iconTint = MaterialTheme.colorScheme.error,
                            currencyFormatter = currencyFormatter
                        )

                        BudgetSummaryItem(
                            title = "剩餘",
                            amount = totalRemaining,
                            icon = Icons.Filled.Savings,
                            iconTint = if (totalRemaining >= 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            currencyFormatter = currencyFormatter
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 整體使用率
                    LinearProgressIndicator(
                        progress = (overallUsage / 100).toFloat().coerceAtMost(1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = when {
                            overallUsage >= 100 -> MaterialTheme.colorScheme.error
                            overallUsage >= 80 -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "整體使用率：${String.format("%.1f", overallUsage)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 預算列表
        if (filteredBudgets.isEmpty()) {
            // 空狀態
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = "無預算",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "尚未設定任何預算",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "點擊右上角的 + 按鈕來新增第一個預算",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showAddBudgetDialog = true }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("新增預算")
                    }
                }
            }
        } else {
            // 預算卡片列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredBudgets) { budget ->
                    BudgetCard(
                        budget = budget,
                        currencyFormatter = currencyFormatter,
                        onClick = { onNavigateToBudgetDetail(budget.id) }
                    )
                }
            }
        }

        // 載入指示器
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    // 新增預算對話框
    if (showAddBudgetDialog) {
        AddBudgetDialog(
            availableCategories = availableCategories,
            selectedMonth = selectedMonth,
            selectedYear = selectedYear,
            onDismiss = { showAddBudgetDialog = false },
            onAddBudget = { budget ->
                viewModel.addBudget(budget)
                showAddBudgetDialog = false
            }
        )
    }
}

@Composable
fun BudgetSummaryItem(
    title: String,
    amount: Double,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    currencyFormatter: NumberFormat
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = currencyFormatter.format(amount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BudgetCard(
    budget: Budget,
    currencyFormatter: NumberFormat,
    onClick: () -> Unit
) {
    val usagePercentage = if (budget.limit > 0) {
        (budget.spent / budget.limit * 100).coerceAtMost(100.0)
    } else 0.0

    val statusColor = when {
        usagePercentage >= 100 -> MaterialTheme.colorScheme.error
        usagePercentage >= 80 -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    val statusIcon = when {
        usagePercentage >= 100 -> Icons.Filled.Warning
        usagePercentage >= 80 -> Icons.Filled.Error
        else -> Icons.Filled.CheckCircle
    }

    val statusText = when {
        usagePercentage >= 100 -> "超支"
        usagePercentage >= 80 -> "警告"
        else -> "正常"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 標題行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = budget.category,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        statusIcon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 金額資訊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "已花費：${currencyFormatter.format(budget.spent)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "預算：${currencyFormatter.format(budget.limit)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 進度條
            LinearProgressIndicator(
                progress = (usagePercentage / 100).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                color = statusColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 使用率和剩餘金額
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${String.format("%.1f", usagePercentage)}% 已使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val remaining = budget.limit - budget.spent
                Text(
                    text = "剩餘：${currencyFormatter.format(remaining)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remaining >= 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBudgetDialog(
    availableCategories: List<Category>,
    selectedMonth: String,
    selectedYear: Int,
    onDismiss: () -> Unit,
    onAddBudget: (Budget) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("") }
    var budgetLimit by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增預算") },
        text = {
            Column {
                // 月份年份顯示
                Text(
                    text = "設定 $selectedMonth/$selectedYear 的預算",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 分類選擇
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("選擇分類") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (availableCategories.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("無可用分類") },
                                onClick = { },
                                enabled = false
                            )
                        } else {
                            availableCategories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedCategory = category.name
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 預算金額
                OutlinedTextField(
                    value = budgetLimit,
                    onValueChange = { budgetLimit = it },
                    label = { Text("預算金額") },
                    suffix = { Text("元") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (availableCategories.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "提示：請先在設定中新增支出分類",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val limitAmount = budgetLimit.toDoubleOrNull()
                    if (selectedCategory.isNotEmpty() && limitAmount != null && limitAmount > 0) {
                        onAddBudget(
                            Budget(
                                category = selectedCategory,
                                limit = limitAmount,
                                spent = 0.0,
                                month = selectedMonth,
                                year = selectedYear
                            )
                        )
                    }
                },
                enabled = selectedCategory.isNotEmpty() &&
                        budgetLimit.toDoubleOrNull()?.let { it > 0 } == true
            ) {
                Text("新增")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// 輔助函式
private fun getCurrentMonth(): String {
    return (Calendar.getInstance().get(Calendar.MONTH) + 1).toString().padStart(2, '0')
}

private fun getCurrentYear(): Int {
    return Calendar.getInstance().get(Calendar.YEAR)
}