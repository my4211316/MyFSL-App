package com.example.myfsl

import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Transaction(
    val amount: Double = 0.0,
    @get:PropertyName("isExpense") @set:PropertyName("isExpense")
    var isExpense: Boolean = true,
    val category: String = "",
    val paymentMethod: String = "",
    val notes: String = "",
    val transactionDate: Date = Date(),
    @ServerTimestamp val createdAt: Date? = null,
    val userId: String = ""
) {
    constructor() : this(0.0, true, "", "", "", Date(), null, "")
}