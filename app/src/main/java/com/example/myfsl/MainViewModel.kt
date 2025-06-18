package com.example.myfsl

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObjects
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

// 儀表板所需的數據狀態
data class DashboardUiState(
    val totalNetWorth: Double = 0.0,
    val thisMonthIncome: Double = 0.0,
    val thisMonthExpense: Double = 0.0,
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
    var saveStatus by mutableStateOf<String?>(null)
        private set

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _dashboardState = MutableStateFlow(DashboardUiState())
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
        // 修正：統一使用 isExpense 來判斷收支，金額都保持正數
        val totalIncome = transactions.filter { !it.isExpense }.sumOf { it.amount }
        val totalExpense = transactions.filter { it.isExpense }.sumOf { it.amount }
        val netWorth = totalIncome - totalExpense // 總淨值 = 總收入 - 總支出

        val now = LocalDate.now()
        val startOfMonth = now.withDayOfMonth(1)

        val thisMonthTransactions = transactions.filter {
            !it.transactionDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(startOfMonth)
        }

        val thisMonthIncome = thisMonthTransactions.filter { !it.isExpense }.sumOf { it.amount }
        val thisMonthExpense = thisMonthTransactions.filter { it.isExpense }.sumOf { it.amount }

        _dashboardState.value = DashboardUiState(
            totalNetWorth = netWorth,
            thisMonthIncome = thisMonthIncome,
            thisMonthExpense = thisMonthExpense,
            isLoading = false,
            errorMessage = null
        )
    }

    // 載入使用者自訂分類
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
    fun onAmountChange(newAmount: String) { if (newAmount.isEmpty() || newAmount.matches(Regex("^\\d*\\.?\\d*$"))) { amount = newAmount } }
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
    fun onSaveStatusConsumed() { saveStatus = null }

    // 修正新增分類功能
    fun onNewCategoryAdded(categoryName: String) {
        val userId = auth.currentUser?.uid ?: return

        if (isExpense) {
            // 先添加到本地列表（樂觀更新）
            expenseCategories.add(categoryName)

            // 儲存到 Firebase
            db.collection("userCategories")
                .document(userId)
                .collection("expenseCategories")
                .document(categoryName)
                .set(mapOf(
                    "createdAt" to com.google.firebase.Timestamp.now(),
                    "isDefault" to false
                ))
                .addOnFailureListener { e ->
                    Log.w("ViewModel", "Failed to save expense category", e)
                    // 如果儲存失敗，從本地列表移除
                    expenseCategories.remove(categoryName)
                    saveStatus = "新增分類失敗: ${e.message}"
                }
        } else {
            // 先添加到本地列表（樂觀更新）
            incomeCategories.add(categoryName)

            // 儲存到 Firebase
            db.collection("userCategories")
                .document(userId)
                .collection("incomeCategories")
                .document(categoryName)
                .set(mapOf(
                    "createdAt" to com.google.firebase.Timestamp.now(),
                    "isDefault" to false
                ))
                .addOnFailureListener { e ->
                    Log.w("ViewModel", "Failed to save income category", e)
                    // 如果儲存失敗，從本地列表移除
                    incomeCategories.remove(categoryName)
                    saveStatus = "新增分類失敗: ${e.message}"
                }
        }

        selectedCategory = categoryName
        showAddCategoryDialog = false
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