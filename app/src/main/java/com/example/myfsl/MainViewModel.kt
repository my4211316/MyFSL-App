package com.example.myfsl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

data class Budget(
    val id: String = "",
    val category: String = "",
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val month: String = "",
    val year: Int = 0,
    val userId: String = ""
) {
    constructor() : this("", "", 0.0, 0.0, "", 0, "")
}

data class Category(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val isExpense: Boolean = true,
    val userId: String = ""
) {
    constructor() : this("", "", "", true, "")
}

class MainViewModel : ViewModel() {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // State flows
    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _budgets = MutableStateFlow<List<Budget>>(emptyList())
    val budgets: StateFlow<List<Budget>> = _budgets.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var transactionListener: ListenerRegistration? = null
    private var budgetListener: ListenerRegistration? = null
    private var categoryListener: ListenerRegistration? = null

    init {
        loadData()
    }

    private fun loadData() {
        auth.currentUser?.let { user ->
            loadTransactions(user.uid)
            loadBudgets(user.uid)
            loadCategories(user.uid)
        }
    }

    private fun loadTransactions(userId: String) {
        transactionListener = firestore.collection("transactions")
            .whereEqualTo("userId", userId)
            .orderBy("transactionDate", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _errorMessage.value = "載入交易記錄失敗: ${e.message}"
                    return@addSnapshotListener
                }

                val transactionList = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Transaction::class.java)?.copy()
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                _transactions.value = transactionList
            }
    }

    private fun loadBudgets(userId: String) {
        budgetListener = firestore.collection("budgets")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _errorMessage.value = "載入預算失敗: ${e.message}"
                    return@addSnapshotListener
                }

                val budgetList = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Budget::class.java)?.copy(id = doc.id)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                _budgets.value = budgetList
            }
    }

    private fun loadCategories(userId: String) {
        categoryListener = firestore.collection("categories")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _errorMessage.value = "載入分類失敗: ${e.message}"
                    return@addSnapshotListener
                }

                val categoryList = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Category::class.java)?.copy(id = doc.id)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                _categories.value = categoryList
            }
    }

    fun addTransaction(transaction: Transaction) {
        auth.currentUser?.let { user ->
            _isLoading.value = true
            val transactionWithUser = transaction.copy(userId = user.uid)

            viewModelScope.launch {
                try {
                    firestore.collection("transactions")
                        .add(transactionWithUser)
                        .await()

                    // 更新預算
                    if (transaction.isExpense) {
                        updateBudgetSpent(transaction.category, transaction.amount)
                    }

                    _errorMessage.value = null
                } catch (e: Exception) {
                    _errorMessage.value = "新增交易失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private suspend fun updateBudgetSpent(category: String, amount: Double) {
        auth.currentUser?.let { user ->
            val currentDate = LocalDate.now()
            val month = currentDate.monthValue.toString().padStart(2, '0')
            val year = currentDate.year

            try {
                val budgetQuery = firestore.collection("budgets")
                    .whereEqualTo("userId", user.uid)
                    .whereEqualTo("category", category)
                    .whereEqualTo("month", month)
                    .whereEqualTo("year", year)
                    .get()
                    .await()

                if (!budgetQuery.isEmpty) {
                    val budgetDoc = budgetQuery.documents[0]
                    val currentSpent = budgetDoc.getDouble("spent") ?: 0.0
                    budgetDoc.reference.update("spent", currentSpent + amount).await()
                }
            } catch (e: Exception) {
                _errorMessage.value = "更新預算失敗: ${e.message}"
            }
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val querySnapshot = firestore.collection("transactions")
                    .whereEqualTo("userId", transaction.userId)
                    .whereEqualTo("amount", transaction.amount)
                    .whereEqualTo("category", transaction.category)
                    .whereEqualTo("transactionDate", transaction.transactionDate)
                    .get()
                    .await()

                for (document in querySnapshot.documents) {
                    document.reference.delete().await()
                }

                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "刪除交易失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addBudget(budget: Budget) {
        auth.currentUser?.let { user ->
            _isLoading.value = true
            val budgetWithUser = budget.copy(userId = user.uid)

            viewModelScope.launch {
                try {
                    firestore.collection("budgets")
                        .add(budgetWithUser)
                        .await()
                    _errorMessage.value = null
                } catch (e: Exception) {
                    _errorMessage.value = "新增預算失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun updateBudget(budget: Budget) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                firestore.collection("budgets")
                    .document(budget.id)
                    .set(budget)
                    .await()
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "更新預算失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteBudget(budgetId: String) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                firestore.collection("budgets")
                    .document(budgetId)
                    .delete()
                    .await()
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "刪除預算失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addCategory(category: Category) {
        auth.currentUser?.let { user ->
            _isLoading.value = true
            val categoryWithUser = category.copy(userId = user.uid)

            viewModelScope.launch {
                try {
                    firestore.collection("categories")
                        .add(categoryWithUser)
                        .await()
                    _errorMessage.value = null
                } catch (e: Exception) {
                    _errorMessage.value = "新增分類失敗: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // 儀表板數據計算
    fun getTotalIncome(): Double {
        return _transactions.value
            .filter { !it.isExpense }
            .sumOf { it.amount }
    }

    fun getTotalExpenses(): Double {
        return _transactions.value
            .filter { it.isExpense }
            .sumOf { it.amount }
    }

    fun getMonthlyIncome(): Double {
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        return _transactions.value
            .filter { !it.isExpense }
            .filter { transaction ->
                val calendar = Calendar.getInstance().apply { time = transaction.transactionDate }
                calendar.get(Calendar.MONTH) == currentMonth &&
                        calendar.get(Calendar.YEAR) == currentYear
            }
            .sumOf { it.amount }
    }

    fun getMonthlyExpenses(): Double {
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        return _transactions.value
            .filter { it.isExpense }
            .filter { transaction ->
                val calendar = Calendar.getInstance().apply { time = transaction.transactionDate }
                calendar.get(Calendar.MONTH) == currentMonth &&
                        calendar.get(Calendar.YEAR) == currentYear
            }
            .sumOf { it.amount }
    }

    fun getExpensesByCategory(): Map<String, Double> {
        return _transactions.value
            .filter { it.isExpense }
            .groupBy { it.category }
            .mapValues { (_, transactions) ->
                transactions.sumOf { it.amount }
            }
    }

    fun getRecentTransactions(limit: Int = 5): List<Transaction> {
        return _transactions.value.take(limit)
    }

    // 圖表數據 - Vico 1.13.1 相容
    fun getChartData(): List<Double> {
        val dailyExpenses = _transactions.value
            .filter { it.isExpense }
            .filter { transaction ->
                val calendar = Calendar.getInstance().apply { time = transaction.transactionDate }
                val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                calendar.get(Calendar.MONTH) == currentMonth &&
                        calendar.get(Calendar.YEAR) == currentYear
            }
            .groupBy { transaction ->
                val calendar = Calendar.getInstance().apply { time = transaction.transactionDate }
                calendar.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { (_, transactions) -> transactions.sumOf { it.amount } }

        // 生成最近7天的數據
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return (currentDay - 6..currentDay).map { day ->
            dailyExpenses[day] ?: 0.0
        }
    }

    override fun onCleared() {
        super.onCleared()
        transactionListener?.remove()
        budgetListener?.remove()
        categoryListener?.remove()
    }
}