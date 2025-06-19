package com.example.myfsl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// 注意：FilterType 和 DateFilterState 已經移到 MainViewModel.kt 中

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(vm: MainViewModel) {

    // 根據ViewModel的狀態，決定是否顯示日期範圍選擇器
    if (vm.showDateRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState()
        DateRangePickerDialog(
            state = dateRangePickerState,
            onDismiss = vm::closeDateRangePicker,
            onConfirm = {
                val start = dateRangePickerState.selectedStartDateMillis?.let { Date(it) }
                val end = dateRangePickerState.selectedEndDateMillis?.let { Date(it) }
                if (start != null && end != null) {
                    vm.onFilterChange(FilterType.CUSTOM, startDate = start, endDate = end)
                }
                vm.closeDateRangePicker()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("交易列表") },
                actions = {
                    // 右上角的日曆按鈕
                    IconButton(onClick = vm::openDateRangePicker) {
                        Icon(imageVector = Icons.Default.DateRange, contentDescription = "選擇日期範圍")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // 1. 定義我們的分頁 - 動態增加自訂範圍頁籤
            val tabs = when (vm.dateFilterState.type) {
                FilterType.CUSTOM -> listOf("本日", "本週", "本月", "自訂範圍")
                else -> listOf("本日", "本週", "本月")
            }

            val pagerState = rememberPagerState { tabs.size }
            val coroutineScope = rememberCoroutineScope()

            // 監聽並同步 Pager 和 Tab 的狀態
            LaunchedEffect(vm.dateFilterState.type) {
                // 當篩選條件改變時，同步pager位置
                val targetIndex = when (vm.dateFilterState.type) {
                    FilterType.TODAY -> 0
                    FilterType.THIS_WEEK -> 1
                    FilterType.THIS_MONTH -> 2
                    FilterType.CUSTOM -> 3 // 自訂範圍到第4個頁籤
                }
                if (targetIndex < tabs.size && targetIndex != pagerState.currentPage) {
                    pagerState.animateScrollToPage(targetIndex)
                }
            }

            LaunchedEffect(pagerState.settledPage) {
                // 只有在用戶手動滑動或點擊Tab時才觸發篩選
                when (pagerState.settledPage) {
                    0 -> vm.onFilterChange(FilterType.TODAY)
                    1 -> vm.onFilterChange(FilterType.THIS_WEEK)
                    2 -> vm.onFilterChange(FilterType.THIS_MONTH)
                    3 -> {
                        // 如果是自訂範圍頁籤，保持當前的自訂篩選
                        if (vm.dateFilterState.type != FilterType.CUSTOM) {
                            // 如果不是自訂範圍狀態，回到本月
                            vm.onFilterChange(FilterType.THIS_MONTH)
                        }
                    }
                }
            }

            // 2. 建立頂部的 TabRow (頁籤) - 只在有自訂範圍時顯示4個tab
            if (tabs.size > 3) {
                val selectedTabIndex = when(vm.dateFilterState.type) {
                    FilterType.TODAY -> 0
                    FilterType.THIS_WEEK -> 1
                    FilterType.THIS_MONTH -> 2
                    FilterType.CUSTOM -> 3
                }

                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                coroutineScope.launch {
                                    // 先滑動到對應頁面
                                    pagerState.animateScrollToPage(index)
                                    // 再觸發篩選
                                    when (index) {
                                        0 -> vm.onFilterChange(FilterType.TODAY)
                                        1 -> vm.onFilterChange(FilterType.THIS_WEEK)
                                        2 -> vm.onFilterChange(FilterType.THIS_MONTH)
                                        3 -> {
                                            // 保持當前的自訂範圍狀態
                                            // 不需要重新觸發篩選
                                        }
                                    }
                                }
                            },
                            text = {
                                if (index == 3 && vm.dateFilterState.type == FilterType.CUSTOM) {
                                    // 顯示自訂範圍的日期
                                    val startDate = vm.dateFilterState.startDate?.let {
                                        SimpleDateFormat("MM/dd", Locale.getDefault()).format(it)
                                    } ?: ""
                                    val endDate = vm.dateFilterState.endDate?.let {
                                        SimpleDateFormat("MM/dd", Locale.getDefault()).format(it)
                                    } ?: ""
                                    Text(
                                        text = if (startDate.isNotEmpty() && endDate.isNotEmpty()) {
                                            "$startDate-$endDate"
                                        } else {
                                            title
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                } else {
                                    Text(text = title)
                                }
                            }
                        )
                    }
                }
            } else {
                // 原本的3個tab
                val selectedTabIndex = when(vm.dateFilterState.type) {
                    FilterType.TODAY -> 0
                    FilterType.THIS_WEEK -> 1
                    FilterType.THIS_MONTH -> 2
                    FilterType.CUSTOM -> 2 // 如果是自訂範圍但沒有專屬tab，回到本月
                }

                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                    when (index) {
                                        0 -> vm.onFilterChange(FilterType.TODAY)
                                        1 -> vm.onFilterChange(FilterType.THIS_WEEK)
                                        2 -> vm.onFilterChange(FilterType.THIS_MONTH)
                                    }
                                }
                            },
                            text = { Text(text = title) }
                        )
                    }
                }
            }

            // 3. 建立可以水平滑動的 Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                // Pager 的每一頁，都是我們之前建立的那個交易列表
                TransactionList(vm = vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    state: DateRangePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
            ) {
                Text("確認")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    ) {
        // 使用更大的高度避免裁切，並調整 padding
        DateRangePicker(
            state = state,
            modifier = Modifier
                .height(500.dp)  // 增加高度避免底部裁切
                .padding(horizontal = 8.dp), // 增加水平邊距避免文字被圓角擋到
            title = {
                Text(
                    text = "選取日期",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp) // 給標題額外的左邊距
                )
            },
            headline = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp), // 增加邊距
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = state.selectedStartDateMillis?.let {
                                SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
                            } ?: "開始日期",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "-",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Column {
                        Text(
                            text = state.selectedEndDateMillis?.let {
                                SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
                            } ?: "結束日期",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            showModeToggle = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionList(vm: MainViewModel) {
    val transactions by vm.transactions.collectAsState()

    // 根據日期篩選交易
    val filteredTransactions = remember(transactions, vm.dateFilterState) {
        filterTransactionsByDate(transactions, vm.dateFilterState)
    }

    // 按日期分組
    val groupedTransactions = remember(filteredTransactions) {
        filteredTransactions
            .groupBy {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it.transactionDate)
            }
            .toSortedMap(compareByDescending { it })
    }

    if (filteredTransactions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "此期間無交易記錄",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groupedTransactions.forEach { (date, transactionsForDate) ->
                stickyHeader {
                    DateHeader(date = date)
                }

                items(
                    items = transactionsForDate,
                    key = { transaction -> transaction.hashCode() }
                ) { transaction ->
                    TransactionRowItem(transaction = transaction)
                }
            }
        }
    }
}

@Composable
fun DateHeader(date: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = formatDisplayDate(date),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun TransactionRowItem(transaction: Transaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.category,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (transaction.notes.isNotBlank()) {
                    Text(
                        text = transaction.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }

                if (transaction.isExpense && transaction.paymentMethod.isNotBlank()) {
                    Text(
                        text = transaction.paymentMethod,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (transaction.isExpense) {
                        "-NT$ ${"%,.0f".format(transaction.amount)}"
                    } else {
                        "+NT$ ${"%,.0f".format(transaction.amount)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.isExpense) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF4CAF50)
                    }
                )

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(transaction.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

// 輔助函數
private fun filterTransactionsByDate(
    transactions: List<Transaction>,
    filterState: DateFilterState
): List<Transaction> {
    val calendar = Calendar.getInstance()
    val now = Date()

    return when (filterState.type) {
        FilterType.TODAY -> {
            calendar.time = now
            val startOfDay = calendar.apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            val endOfDay = calendar.apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.time

            transactions.filter {
                !it.transactionDate.before(startOfDay) && !it.transactionDate.after(endOfDay)
            }
        }

        FilterType.THIS_WEEK -> {
            calendar.time = now
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfWeek = calendar.time

            transactions.filter { !it.transactionDate.before(startOfWeek) }
        }

        FilterType.THIS_MONTH -> {
            calendar.time = now
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfMonth = calendar.time

            transactions.filter { !it.transactionDate.before(startOfMonth) }
        }

        FilterType.CUSTOM -> {
            if (filterState.startDate != null && filterState.endDate != null) {
                // 設定開始日期為當天的00:00:00
                calendar.time = filterState.startDate
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfStartDate = calendar.time

                // 設定結束日期為當天的23:59:59
                calendar.time = filterState.endDate
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                val endOfEndDate = calendar.time

                transactions.filter {
                    !it.transactionDate.before(startOfStartDate) && !it.transactionDate.after(endOfEndDate)
                }
            } else {
                transactions
            }
        }
    }
}

private fun formatDisplayDate(dateString: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MM月dd日 (E)", Locale.getDefault())
        val date = inputFormat.parse(dateString)
        outputFormat.format(date ?: Date())
    } catch (e: Exception) {
        dateString
    }
}