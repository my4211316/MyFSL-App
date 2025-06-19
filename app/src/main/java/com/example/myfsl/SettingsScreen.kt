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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }

    val categories by viewModel.categories.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 標題
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 使用者資訊卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = "用戶",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = currentUser?.displayName ?: "用戶",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentUser?.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 設定選項
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingsItem(
                    icon = Icons.Filled.Category,
                    title = "分類管理",
                    subtitle = "管理收支分類",
                    onClick = { showAddCategoryDialog = true }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.Backup,
                    title = "資料備份",
                    subtitle = "備份您的記帳資料",
                    onClick = { /* TODO: 實作備份功能 */ }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.FileDownload,
                    title = "匯出資料",
                    subtitle = "匯出為 CSV 檔案",
                    onClick = { /* TODO: 實作匯出功能 */ }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.Notifications,
                    title = "通知設定",
                    subtitle = "預算提醒和通知",
                    onClick = { /* TODO: 實作通知設定 */ }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.Help,
                    title = "說明與支援",
                    subtitle = "使用說明和常見問題",
                    onClick = { /* TODO: 實作說明頁面 */ }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.Info,
                    title = "關於應用程式",
                    subtitle = "版本資訊和開發者資訊",
                    onClick = { /* TODO: 實作關於頁面 */ }
                )

                Divider()

                SettingsItem(
                    icon = Icons.Filled.ExitToApp,
                    title = "登出",
                    subtitle = "登出目前帳號",
                    onClick = { showLogoutDialog = true },
                    isDestructive = true
                )
            }
        }

        // 當前分類列表
        if (categories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "目前分類",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card {
                LazyColumn {
                    items(categories) { category ->
                        CategoryItem(category = category)
                        if (category != categories.last()) {
                            Divider()
                        }
                    }
                }
            }
        }
    }

    // 登出確認對話框
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("確認登出") },
            text = { Text("確定要登出嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        auth.signOut()
                        showLogoutDialog = false
                        // TODO: 導航到登入頁面
                    }
                ) {
                    Text("登出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 新增分類對話框
    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onDismiss = { showAddCategoryDialog = false },
            onAddCategory = { category ->
                viewModel.addCategory(category)
                showAddCategoryDialog = false
            }
        )
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = if (isDestructive)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDestructive)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = "進入",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CategoryItem(category: Category) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 分類圖示 (可以用 emoji 或預設圖示)
        Text(
            text = if (category.icon.isNotEmpty()) category.icon else "📁",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (category.isExpense) "支出" else "收入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAddCategory: (Category) -> Unit
) {
    var categoryName by remember { mutableStateOf("") }
    var categoryIcon by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增分類") },
        text = {
            Column {
                OutlinedTextField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = { Text("分類名稱") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = categoryIcon,
                    onValueChange = { categoryIcon = it },
                    label = { Text("圖示 (emoji)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("類型：")
                    Spacer(modifier = Modifier.width(8.dp))
                    Row {
                        FilterChip(
                            onClick = { isExpense = true },
                            label = { Text("支出") },
                            selected = isExpense
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            onClick = { isExpense = false },
                            label = { Text("收入") },
                            selected = !isExpense
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (categoryName.isNotBlank()) {
                        onAddCategory(
                            Category(
                                name = categoryName.trim(),
                                icon = categoryIcon.trim(),
                                isExpense = isExpense
                            )
                        )
                    }
                },
                enabled = categoryName.isNotBlank()
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