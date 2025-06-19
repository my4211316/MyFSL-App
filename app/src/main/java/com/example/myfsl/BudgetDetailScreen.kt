package com.example.myfsl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetDetailScreen(
    viewModel: MainViewModel,
    budgetId: String,
    onNavigateBack: () -> Unit
) {
    val budgets by viewModel.budgets.collectAsState()
    val transactions by viewModel.transactions.collectAsState()

    // 使用 LaunchedEffect 來處理 side effects
    LaunchedEffect(budgetId) {
        // 這裡可以加入需要在組件載入時執行的邏輯
        // 例如：載入特定預算的詳細資料
    }

    val budget = budgets.find { it.id == budgetId }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("zh", "TW")).apply {
        currency = Currency.getInstance("TWD")
    }

    if (budget == null) {
        // 預算不存在的情況
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Error,
                contentDescription = "錯誤",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "找不到指定的預算",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateBack) {
                Text("返回")
            }
        }
        return
    }

    // 計算相關交易
    val relatedTransactions = transactions.filter { transaction ->
        transaction.category == budget.category &&
                transaction.isExpense &&
                isInSameMonth(transaction.transactionDate, budget.month, budget.year)
    }

    val usagePercentage = if (budget.limit > 0) {
        (budget.spent / budget.limit * 100).coerceAtMost(100.0)
    } else 0.0

    val remaining = budget.limit - budget.spent

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("預算詳情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "編輯")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 預算概覽卡片
            item {
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
                                text = budget.category,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${budget.month}/${budget.year}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 進度條
                        LinearProgressIndicator(
                            progress = (usagePercentage / 100).toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            color = when {
                                usagePercentage >= 100 -> MaterialTheme.colorScheme.error
                                usagePercentage >= 80 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${String.format("%.1f", usagePercentage)}% 已使用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 預算統計卡片
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 預算限額
                    Card(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.AccountBalance,
                                contentDescription = "預算限額",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "預算限額",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currencyFormatter.format(budget.limit),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 已花費
                    Card(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Payments,
                                contentDescription = "已花費",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "已花費",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currencyFormatter.format(budget.spent),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // 剩餘額度
                    Card(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Savings,
                                contentDescription = "剩餘額度",
                                tint = if (remaining >= 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "剩餘額度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currencyFormatter.format(remaining),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (remaining >= 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 相關交易標題
            item {
                Text(
                    text = "相關交易 (${relatedTransactions.size} 筆)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // 相關交易列表
            if (relatedTransactions.isEmpty()) {
                item {
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
                                Icons.Filled.Receipt,
                                contentDescription = "無交易",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "本月還沒有此分類的交易記錄",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(relatedTransactions) { transaction ->
                    TransactionCard(
                        transaction = transaction,
                        currencyFormatter = currencyFormatter
                    )
                }
            }
        }
    }

    // 編輯預算對話框
    if (showEditDialog) {
        EditBudgetDialog(
            budget = budget,
            onDismiss = { showEditDialog = false },
            onSave = { updatedBudget ->
                viewModel.updateBudget(updatedBudget)
                showEditDialog = false
            }
        )
    }

    // 刪除確認對話框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("確認刪除") },
            text = { Text("確定要刪除這個預算嗎？此操作無法復原。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBudget(budget.id)
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("刪除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun TransactionCard(
    transaction: Transaction,
    currencyFormatter: NumberFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (transaction.notes.isNotEmpty()) transaction.notes else "無備註",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = transaction.paymentMethod,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                            .format(transaction.transactionDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "- ${currencyFormatter.format(transaction.amount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBudgetDialog(
    budget: Budget,
    onDismiss: () -> Unit,
    onSave: (Budget) -> Unit
) {
    var newLimit by remember { mutableStateOf(budget.limit.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯預算") },
        text = {
            Column {
                Text(
                    text = "分類：${budget.category}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = newLimit,
                    onValueChange = { newLimit = it },
                    label = { Text("預算限額") },
                    modifier = Modifier.fillMaxWidth(),
                    suffix = { Text("元") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val limitAmount = newLimit.toDoubleOrNull()
                    if (limitAmount != null && limitAmount > 0) {
                        onSave(budget.copy(limit = limitAmount))
                    }
                },
                enabled = newLimit.toDoubleOrNull()?.let { it > 0 } == true
            ) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// 輔助函式：檢查交易日期是否在指定月份
private fun isInSameMonth(transactionDate: Date, month: String, year: Int): Boolean {
    val calendar = Calendar.getInstance().apply { time = transactionDate }
    val transactionMonth = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val transactionYear = calendar.get(Calendar.YEAR)

    return transactionMonth == month && transactionYear == year
}