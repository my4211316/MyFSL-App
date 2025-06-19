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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    var currentScreen by remember { mutableStateOf(SettingsScreenType.MAIN) }
    var selectedCategoryType by remember { mutableStateOf(true) } // true = 支出, false = 收入

    when (currentScreen) {
        SettingsScreenType.MAIN -> {
            MainSettingsScreen(
                onNavigateToCategoryManagement = { isExpense ->
                    selectedCategoryType = isExpense
                    currentScreen = SettingsScreenType.CATEGORY_MANAGEMENT
                }
            )
        }
        SettingsScreenType.CATEGORY_MANAGEMENT -> {
            CategoryManagementScreen(
                vm = vm,
                isExpenseType = selectedCategoryType,
                onBack = { currentScreen = SettingsScreenType.MAIN }
            )
        }
    }
}

enum class SettingsScreenType {
    MAIN, CATEGORY_MANAGEMENT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    onNavigateToCategoryManagement: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("設定") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "分類管理",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsItem(
                    title = "支出分類",
                    subtitle = "管理支出分類項目",
                    icon = Icons.Default.ShoppingCart,
                    onClick = { onNavigateToCategoryManagement(true) }
                )
            }

            item {
                SettingsItem(
                    title = "收入分類",
                    subtitle = "管理收入分類項目",
                    icon = Icons.Default.AccountBox,
                    onClick = { onNavigateToCategoryManagement(false) }
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            item {
                Text(
                    text = "其他功能",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsItem(
                    title = "預算設定",
                    subtitle = "設定各分類的月度預算",
                    icon = Icons.Default.Settings,
                    onClick = { /* TODO: 實作預算設定 */ },
                    enabled = false
                )
            }

            item {
                SettingsItem(
                    title = "資料匯出",
                    subtitle = "匯出交易記錄",
                    icon = Icons.Default.Share,
                    onClick = { /* TODO: 實作資料匯出 */ },
                    enabled = false
                )
            }

            item {
                SettingsItem(
                    title = "備份與還原",
                    subtitle = "雲端備份您的資料",
                    icon = Icons.Default.Settings,
                    onClick = { /* TODO: 實作備份功能 */ },
                    enabled = false
                )
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "進入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementScreen(
    vm: MainViewModel,
    isExpenseType: Boolean,
    onBack: () -> Unit
) {
    val categories = if (isExpenseType) vm.expenseCategories else vm.incomeCategories
    val title = if (isExpenseType) "支出分類管理" else "收入分類管理"

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf("") }

    if (showAddDialog) {
        AddCategoryDialog(
            isExpense = isExpenseType,
            onConfirm = { newName ->
                vm.onNewCategoryAdded(newName, isExpenseType)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    if (showEditDialog) {
        EditCategoryDialog(
            categoryName = categoryToEdit,
            onConfirm = { newName ->
                vm.updateCategory(categoryToEdit, newName, isExpenseType)
                showEditDialog = false
                categoryToEdit = ""
            },
            onDismiss = {
                showEditDialog = false
                categoryToEdit = ""
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "新增分類"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (categories.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "尚無分類",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "點擊右上角 + 號新增分類",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(categories) { category ->
                    CategoryItemCard(
                        name = category,
                        onEdit = {
                            categoryToEdit = category
                            showEditDialog = true
                        },
                        onDelete = { vm.deleteCategory(category, isExpenseType) }
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryItemCard(
    name: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "編輯 $name",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "刪除 $name",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AddCategoryDialog(
    isExpense: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isExpense) "新增支出分類" else "新增收入分類")
        },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("分類名稱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName.trim())
                    }
                },
                enabled = newName.isNotBlank()
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

@Composable
fun EditCategoryDialog(
    categoryName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(categoryName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯分類名稱") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新名稱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName.trim())
                    }
                },
                enabled = newName.isNotBlank() && newName.trim() != categoryName
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