package com.example.myfsl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(vm: MainViewModel) {
    val transactions by vm.transactions.collectAsState()

    // 加入調試日誌
    LaunchedEffect(transactions) {
        println("TransactionListScreen: Got ${transactions.size} transactions")
        transactions.forEach { transaction ->
            println("Transaction: amount=${transaction.amount}, isExpense=${transaction.isExpense}, category=${transaction.category}")
        }
    }

    val groupedTransactions = transactions.groupBy {
        it.transactionDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }

    if (groupedTransactions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("尚未有任何交易紀錄。")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val sortedGroups = groupedTransactions.entries.sortedByDescending { it.key }

            sortedGroups.forEach { (date, transactionsOnDate) ->
                stickyHeader {
                    DateHeader(date = date)
                }
                items(transactionsOnDate) { transaction ->
                    TransactionRowItem(transaction = transaction)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun DateHeader(date: LocalDate?) {
    if (date == null) return
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)

    val dateText = when (date) {
        today -> "今天"
        yesterday -> "昨天"
        else -> SimpleDateFormat("yyyy年 M月 d日 (E)", Locale.TAIWAN).format(
            Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = dateText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TransactionRowItem(transaction: Transaction) {
    // 加入調試日誌
    println("TransactionRowItem: amount=${transaction.amount}, isExpense=${transaction.isExpense}")

    // 定義顏色
    val backgroundColor = if (transaction.isExpense) {
        Color(0xFFFFF3F3) // 淡紅色背景
    } else {
        Color(0xFFF0FFF4) // 淡綠色背景
    }

    val borderColor = if (transaction.isExpense) {
        Color(0xFFFFE1E1) // 紅色邊框
    } else {
        Color(0xFFE1F5E1) // 綠色邊框
    }

    val amountColor = if (transaction.isExpense) {
        Color(0xFFDC2626) // 紅色金額
    } else {
        Color(0xFF059669) // 綠色金額
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左側：圖示 + 分類資訊
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 收支標籤
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(borderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (transaction.isExpense) "支" else "收",
                        color = amountColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 交易資訊
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (transaction.isExpense) "支出" else "收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = amountColor,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = transaction.category,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (transaction.notes.isNotBlank()) {
                        Text(
                            text = transaction.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }

                    val details = mutableListOf<String>()
                    if (transaction.isExpense && transaction.paymentMethod != "N/A") {
                        details.add(transaction.paymentMethod)
                    }
                    transaction.transactionDate.let {
                        details.add(SimpleDateFormat("HH:mm", Locale.TAIWAN).format(it))
                    }
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            // 右側：金額
            Column(horizontalAlignment = Alignment.End) {
                val amountText = "%,.0f".format(transaction.amount)
                Text(
                    text = if (transaction.isExpense) "-NT$ $amountText" else "+NT$ $amountText",
                    color = amountColor,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}