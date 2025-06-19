package com.example.myfsl

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObjects
import com.google.firebase.ktx.Firebase
// Vico 1.14.0 版本的正確 imports
import com.patrykandpatrick.vico.core.chart.values.ChartValues
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

// 篩選類型枚舉
enum class FilterType {
    TODAY, THIS_WEEK, THIS_MONTH, CUSTOM
}

// 日期篩選狀態
data class DateFilterState(
    val type: FilterType = FilterType.TODAY,
    val startDate: Date? = null,
    val endDate: Date? = null
)

// 用來代表單一預算項目狀態的資料類別
data class CategoryBudgetState(
    val categoryName: String,
    val budgetAmount: Double,
    val spentAmount: Double,
    val percentage: Float
)

// 儀表板所需的數據狀態
data class DashboardUiState(
    val totalNetWorth: Double = 0.0,
    val thisMonthIncome: Double = 0.0,
    val thisMonthExpense: Double = 0.0,
    val chartModelProducer: ChartEntryModelProducer? = null,
    val chartXAxisLabels: List<String> = emptyList(),
    val categoryBudgetStates: List<CategoryBudgetState> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel : ViewModel() {

    private val auth = Firebase.auth
    private val db = Firebase.firestore

    // --- 狀態 (State) ---
    val expenseCategories = mutableStateListOf<String>()
    val incomeCategories = mutableStateListOf<String>()
    val paymentMethods = mutableStateListOf("現金", "信用卡", "轉帳", "電子支付")

    var amount by mutableStateOf("")
        private set
    var notes by mutableStateOf("")
        private set
    var isExpense by mutableStateOf(true)
        private set
    var selectedCategory by mutableStateOf("選擇分類")
        private set
    var selectedPaymentMethod by mutableStateOf("現金")
        private set
    var selectedDate by mutableStateOf(Date())
        private set
    var showCategoryDialog by mutableStateOf(false)
        private set
    var showAddCategoryDialog by mutableStateOf(false)
        private set
    var showDatePicker by mutableStateOf(false)
        private set
    var showDateRangePicker by mutableStateOf(false)
        private set
    var dateFilterState by mutableStateOf(DateFilterState())
        private set
    var saveStatus by mutableStateOf<String?>(null)
        private set

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _dashboardState = MutableStateFlow(DashboardUiState())
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Vico 1.14.0 版本的正確建構方式
    private val chartModelProducer = ChartEntryModelProducer()

    // 暫時的預算資料庫，之後可以改為從Firebase讀取
    private val monthlyBudgets: Map<String, Double> = mapOf(
        "餐飲" to 13000.0, "交通" to 5000.0, "購物" to 11000.0,
        "娛樂" to 2000.0, "醫療" to 1000.0, "教育" to 3000.0,
        "其他" to 5000.0, "孝親費" to 10000.0, "信貸還款" to 27000.0
    )

    init {
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    loadData()
                    loadCategories()
                }
            }
        } else {
            loadData()
            loadCategories()
        }
    }

    private fun loadData() {
        val userId = auth.currentUser?.uid ?: return

        _dashboardState.value = _dashboardState.value.copy(isLoading = true, errorMessage = null)

        db.collection("transactions")
            .whereEqualTo("userId", userId)
            .orderBy("transactionDate", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, e ->
                _dashboardState.value = _dashboardState.value.copy(isLoading = false)

                if (e != null) {
                    Log.w("ViewModel", "Listen failed.", e)
                    val errorMsg = "資料載入失敗: ${e.message}"
                    _dashboardState.value = _dashboardState.value.copy(errorMessage = errorMsg)
                    _errorMessage.value = errorMsg
                    return@addSnapshotListener
                }

                snapshots?.let {
                    try {
                        val transactionList = it.toObjects<Transaction>()
                        _transactions.value = transactionList
                        processDataForDashboard(transactionList)
                        // 清除錯誤訊息
                        _dashboardState.value = _dashboardState.value.copy(errorMessage = null)
                        _errorMessage.value = null
                    } catch (e: Exception) {
                        Log.e("ViewModel", "Error processing transactions", e)
                        val errorMsg = "資料處理失敗: ${e.message}"
                        _dashboardState.value = _dashboardState.value.copy(errorMessage = errorMsg)
                        _errorMessage.value = errorMsg
                    }
                }
            }
    }

    private fun processDataForDashboard(transactions: List<Transaction>) {
        // 1. 修正：正確計算總淨值
        val totalIncome = transactions.filter { !it.isExpense }.sumOf { it.amount }
        val totalExpense = transactions.filter { it.isExpense }.sumOf { it.amount }
        val netWorth = totalIncome - totalExpense

        // 2. 計算本月收支
        val now = LocalDate.now()
        val startOfMonth = now.withDayOfMonth(1)
        val thisMonthTransactions = transactions.filter {
            !it.transactionDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(startOfMonth)
        }
        val thisMonthIncome = thisMonthTransactions.filter { !it.isExpense }.sumOf { it.amount }
        val thisMonthExpense = thisMonthTransactions.filter { it.isExpense }.sumOf { it.amount }

        // 3. 準備圖表資料 (最近6個月) - Vico 1.14.0 版本
        val monthlySummary = transactions
            .groupBy { YearMonth.from(it.transactionDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()) }
            .mapValues { entry ->
                val income = entry.value.filter { !it.isExpense }.sumOf { it.amount }
                val expense = entry.value.filter { it.isExpense }.sumOf { it.amount }
                Pair(income, expense)
            }.toSortedMap()
            .entries
            .toList()
            .takeLast(6)

        // Vico 1.14.0 新的數據設置方式
        val incomeEntries = monthlySummary.mapIndexed { index, entry ->
            entryOf(index, entry.value.first)
        }
        val expenseEntries = monthlySummary.mapIndexed { index, entry ->
            entryOf(index, entry.value.second)
        }

        chartModelProducer.setEntries(listOf(incomeEntries, expenseEntries))

        // 4. 計算本月各項預算的執行狀況
        val spendingByCategory = thisMonthTransactions
            .filter { it.isExpense }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val categoryBudgetStates = monthlyBudgets.map { (category, budget) ->
            val spent = spendingByCategory[category] ?: 0.0
            val percentage = if (budget > 0) (spent / budget).toFloat() else 0f
            CategoryBudgetState(category, budget, spent, percentage)
        }

        // 5. 更新UI狀態
        _dashboardState.value = DashboardUiState(
            totalNetWorth = netWorth,
            thisMonthIncome = thisMonthIncome,
            thisMonthExpense = thisMonthExpense,
            chartModelProducer = chartModelProducer,
            chartXAxisLabels = monthlySummary.map { it.key.format(DateTimeFormatter.ofPattern("M月")) },
            categoryBudgetStates = categoryBudgetStates,
            isLoading = false,
            errorMessage = null
        )
    }

    // 載入使用者自訂分類 - 簡化版本
    private fun loadCategories() {
        val userId = auth.currentUser?.uid ?: return

        // 載入支出分類
        db.collection("userCategories")
            .document(userId)
            .collection("expenseCategories")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("ViewModel", "Failed to load expense categories.", e)
                    return@addSnapshotListener
                }

                val categories = snapshots?.documents?.map { it.id } ?: emptyList()
                expenseCategories.clear()
                if (categories.isEmpty()) {
                    // 如果沒有自訂分類，使用預設分類並儲存到 Firebase
                    val defaultExpenseCategories = listOf("餐飲", "交通", "購物", "娛樂", "醫療", "教育", "其他")
                    expenseCategories.addAll(defaultExpenseCategories)
                    saveDefaultCategories(userId, "expenseCategories", defaultExpenseCategories)
                } else {
                    expenseCategories.addAll(categories)
                }
            }

        // 載入收入分類
        db.collection("userCategories")
            .document(userId)
            .collection("incomeCategories")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("ViewModel", "Failed to load income categories.", e)
                    return@addSnapshotListener
                }

                val categories = snapshots?.documents?.map { it.id } ?: emptyList()
                incomeCategories.clear()
                if (categories.isEmpty()) {
                    // 如果沒有自訂分類，使用預設分類並儲存到 Firebase
                    val defaultIncomeCategories = listOf("薪水", "獎金", "投資", "副業", "禮金", "其他")
                    incomeCategories.addAll(defaultIncomeCategories)
                    saveDefaultCategories(userId, "incomeCategories", defaultIncomeCategories)
                } else {
                    incomeCategories.addAll(categories)
                }
            }
    }

    // 儲存預設分類到 Firebase
    private fun saveDefaultCategories(userId: String, collectionName: String, categories: List<String>) {
        val batch = db.batch()
        categories.forEach { category ->
            val docRef = db.collection("userCategories")
                .document(userId)
                .collection(collectionName)
                .document(category)
            batch.set(docRef, mapOf(
                "createdAt" to com.google.firebase.Timestamp.now(),
                "isDefault" to true
            ))
        }
        batch.commit().addOnFailureListener { e ->
            Log.w("ViewModel", "Failed to save default categories", e)
        }
    }

    // --- 新增的分類管理功能 ---

    // 新增分類 - 統一接口，支援從設定頁面和記帳頁面呼叫
    fun onNewCategoryAdded(categoryName: String, isExpenseType: Boolean = isExpense) {
        val userId = auth.currentUser?.uid ?: return
        val collectionPath = if (isExpenseType) "expenseCategories" else "incomeCategories"

        db.collection("userCategories").document(userId)
            .collection(collectionPath).document(categoryName)
            .set(mapOf(
                "createdAt" to com.google.firebase.Timestamp.now(),
                "isDefault" to false
            ))
            .addOnSuccessListener {
                Log.d("ViewModel", "Category '$categoryName' added.")
                // 如果是從記帳頁新增，則直接選中
                if(showAddCategoryDialog) {
                    selectedCategory = categoryName
                    showAddCategoryDialog = false
                }
            }
            .addOnFailureListener { e ->
                saveStatus = "新增分類失敗: ${e.message}"
            }
    }

    // 刪除分類
    fun deleteCategory(categoryName: String, isExpenseType: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        val collectionPath = if (isExpenseType) "expenseCategories" else "incomeCategories"

        db.collection("userCategories").document(userId)
            .collection(collectionPath).document(categoryName)
            .delete()
            .addOnFailureListener { e ->
                saveStatus = "刪除分類失敗: ${e.message}"
            }
    }

    // 更新分類名稱
    fun updateCategory(oldName: String, newName: String, isExpenseType: Boolean) {
        if (oldName == newName || newName.isBlank()) return
        val userId = auth.currentUser?.uid ?: return
        val collectionPath = if (isExpenseType) "expenseCategories" else "incomeCategories"
        val collectionRef = db.collection("userCategories").document(userId).collection(collectionPath)

        // 因為文件ID就是分類名稱，所以更新等於「刪除舊的，新增新的」
        val batch = db.batch()
        batch.delete(collectionRef.document(oldName))
        batch.set(collectionRef.document(newName), mapOf(
            "createdAt" to com.google.firebase.Timestamp.now(),
            "isDefault" to false
        ))

        batch.commit()
            .addOnFailureListener { e ->
                saveStatus = "更新分類失敗: ${e.message}"
            }
    }

    // 錯誤訊息清除方法
    fun clearErrorMessage() {
        _errorMessage.value = null
        _dashboardState.value = _dashboardState.value.copy(errorMessage = null)
    }

    // 重試載入方法
    fun retryLoadData() {
        loadData()
    }

    // --- 事件 (Events) ---
    fun onAmountChange(newAmount: String) {
        if (newAmount.isEmpty() || newAmount.matches(Regex("^\\d*\\.?\\d*$"))) {
            amount = newAmount
        }
    }
    fun onNotesChange(newNotes: String) { notes = newNotes }
    fun onTransactionTypeChange(isExpenseType: Boolean) {
        Log.d("ViewModel", "onTransactionTypeChange called with: $isExpenseType")
        if (isExpense != isExpenseType) {
            isExpense = isExpenseType
            selectedCategory = "選擇分類"
            Log.d("ViewModel", "isExpense changed to: $isExpense")
        }
    }
    fun onCategorySelected(category: String) { selectedCategory = category; showCategoryDialog = false }
    fun onPaymentMethodSelected(method: String) { selectedPaymentMethod = method }
    fun onDateSelected(date: Date) { selectedDate = date; showDatePicker = false }
    fun openCategoryDialog() { showCategoryDialog = true }
    fun dismissCategoryDialog() { showCategoryDialog = false }
    fun onAddNewCategoryClicked() { showCategoryDialog = false; showAddCategoryDialog = true }
    fun dismissAddCategoryDialog() { showAddCategoryDialog = false }
    fun openDatePicker() { showDatePicker = true }
    fun dismissDatePicker() { showDatePicker = false }
    fun openDateRangePicker() { showDateRangePicker = true }
    fun closeDateRangePicker() { showDateRangePicker = false }
    fun onSaveStatusConsumed() { saveStatus = null }

    // 日期篩選相關方法
    fun onFilterChange(
        filterType: FilterType,
        startDate: Date? = null,
        endDate: Date? = null
    ) {
        dateFilterState = DateFilterState(
            type = filterType,
            startDate = startDate,
            endDate = endDate
        )
    }

    fun saveTransaction() {
        val amountValue = amount.toDoubleOrNull() ?: 0.0
        val userId = auth.currentUser?.uid

        // 驗證邏輯
        if (amountValue <= 0) { saveStatus = "請輸入有效的金額"; return }
        if (userId == null) { saveStatus = "使用者未登入，請重開App"; return }
        if (selectedCategory == "選擇分類") { saveStatus = "請選擇分類"; return }
        if (isExpense && selectedPaymentMethod == "選擇支付方式") { saveStatus = "請選擇支付方式"; return }

        val transaction = Transaction(
            amount = amountValue, // 統一保持正數
            isExpense = isExpense, // 用布林值標記收支類型
            category = selectedCategory,
            paymentMethod = if (isExpense) selectedPaymentMethod else "N/A",
            notes = notes,
            transactionDate = selectedDate,
            userId = userId
        )

        Log.d("ViewModel", "Saving transaction: amount=$amountValue, isExpense=$isExpense, category=$selectedCategory")

        db.collection("transactions")
            .add(transaction)
            .addOnSuccessListener { documentReference ->
                Log.d("ViewModel", "Transaction saved with ID: ${documentReference.id}")
                saveStatus = "儲存成功！"
                resetInputFields()
            }
            .addOnFailureListener { e ->
                Log.e("ViewModel", "Error saving transaction", e)
                saveStatus = "儲存失敗: ${e.message}"
            }
    }

    private fun resetInputFields() {
        amount = ""
        notes = ""
        selectedCategory = "選擇分類"
        selectedPaymentMethod = "現金"
        selectedDate = Date()
        isExpense = true
    }
}