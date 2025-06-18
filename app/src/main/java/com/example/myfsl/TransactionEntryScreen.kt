package com.example.myfsl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.filled.ArrowDropDown
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntryScreen(vm: MainViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(vm.saveStatus) {
        vm.saveStatus?.let { status ->
            snackbarHostState.showSnackbar(message = status, duration = SnackbarDuration.Short)
            vm.onSaveStatusConsumed()
        }
    }

    if (vm.showCategoryDialog) {
        CategorySelectionDialog(
            isExpense = vm.isExpense,
            categories = if (vm.isExpense) vm.expenseCategories else vm.incomeCategories,
            onCategorySelected = vm::onCategorySelected,
            onDismiss = vm::dismissCategoryDialog,
            onAddNewCategory = vm::onAddNewCategoryClicked
        )
    }

    if (vm.showAddCategoryDialog) {
        AddCategoryDialog(
            onCategoryAdded = vm::onNewCategoryAdded,
            onDismiss = vm::dismissAddCategoryDialog
        )
    }

    if (vm.showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = vm.selectedDate.time)
        DatePickerDialog(
            onDismissRequest = vm::dismissDatePicker,
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { vm.onDateSelected(Date(it)) }
                }) { Text("確定") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDatePicker) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "記帳",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                RadioButtonWithText(
                    text = "支出",
                    selected = vm.isExpense,
                    onClick = {
                        println("Selected 支出: isExpense will be true")
                        vm.onTransactionTypeChange(true)
                    }
                )
                Spacer(Modifier.width(32.dp))
                RadioButtonWithText(
                    text = "收入",
                    selected = !vm.isExpense,
                    onClick = {
                        println("Selected 收入: isExpense will be false")
                        vm.onTransactionTypeChange(false)
                    }
                )
            }

            OutlinedTextField(
                value = vm.amount,
                onValueChange = vm::onAmountChange,
                label = { Text("金額") },
                prefix = { Text("NT$ ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // 分類選擇欄位
            OutlinedTextField(
                value = vm.selectedCategory,
                onValueChange = {},
                readOnly = true,
                label = { Text("分類") },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, "選擇分類")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.openCategoryDialog() },
                interactionSource = remember { MutableInteractionSource() }
                    .also { interactionSource ->
                        LaunchedEffect(interactionSource) {
                            interactionSource.interactions.collect {
                                if (it is PressInteraction.Release) {
                                    vm.openCategoryDialog()
                                }
                            }
                        }
                    }
            )

            // 日期選擇欄位
            OutlinedTextField(
                value = formatDateForDisplay(vm.selectedDate),
                onValueChange = {},
                readOnly = true,
                label = { Text("日期") },
                trailingIcon = {
                    Icon(Icons.Default.DateRange, "選擇日期")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.openDatePicker() },
                interactionSource = remember { MutableInteractionSource() }
                    .also { interactionSource ->
                        LaunchedEffect(interactionSource) {
                            interactionSource.interactions.collect {
                                if (it is PressInteraction.Release) {
                                    vm.openDatePicker()
                                }
                            }
                        }
                    }
            )

            if (vm.isExpense) {
                PaymentMethodDropdown(
                    selectedMethod = vm.selectedPaymentMethod,
                    methods = vm.paymentMethods,
                    onMethodSelected = vm::onPaymentMethodSelected
                )
            }

            OutlinedTextField(
                value = vm.notes,
                onValueChange = vm::onNotesChange,
                label = { Text("備註 (選填)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = vm::saveTransaction,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    if (vm.isExpense) "記錄支出" else "記錄收入",
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun RadioButtonWithText(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.selectable(selected = selected, onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = text,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodDropdown(
    selectedMethod: String,
    methods: List<String>,
    onMethodSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedMethod,
            onValueChange = {},
            readOnly = true,
            label = { Text("支付方式") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            methods.forEach { method ->
                DropdownMenuItem(
                    text = { Text(method) },
                    onClick = {
                        onMethodSelected(method)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CategorySelectionDialog(
    isExpense: Boolean,
    categories: List<String>,
    onCategorySelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddNewCategory: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isExpense) "選擇支出分類" else "選擇收入分類",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(categories) { category ->
                        Text(
                            text = category,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCategorySelected(category) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = onAddNewCategory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ 新增分類", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun AddCategoryDialog(onCategoryAdded: (String) -> Unit, onDismiss: () -> Unit) {
    var newCategoryName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "新增分類",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("分類名稱") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank())
                                onCategoryAdded(newCategoryName.trim())
                        },
                        enabled = newCategoryName.isNotBlank()
                    ) {
                        Text("新增")
                    }
                }
            }
        }
    }
}

// 格式化日期顯示的輔助函式
fun formatDateForDisplay(date: Date): String {
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日 (E)", Locale.TAIWAN)
    return dateFormat.format(date)
}