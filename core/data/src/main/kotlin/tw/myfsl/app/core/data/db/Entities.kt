package tw.myfsl.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// 列舉一律以名稱字串儲存，日期以 epochDay 儲存。
// 支付方式在主鍵欄位中以空字串代表「無」（收入、轉帳）。

@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val creditLimit: Long?,
    val paymentDueDay: Int?,
    val loanRatePercent: Double?,
    val loanRemainingMonths: Int?,
    val loanMethod: String?,
    val loanPayAccountId: Long?,
    val loanPayDay: Int?,
    val loanOriginalPrincipal: Long?,
    val cardRatePercent: Double? = null,
    val cardMinPercent: Double? = null,
    val cardMinFloor: Long? = null,
    val cardPayMode: String? = null,
    val cardFixedPayment: Long? = null,
    val cardPayAccountId: Long? = null,
    val cardPayDay: Int? = null,
    val cardStatementDay: Int? = null,
    val cardRevolvingBalance: Long? = null,
    val issuer: String = "",
    val archived: Boolean,
    val sortOrder: Int,
)

@Serializable
@Entity(tableName = "balance_snapshots", indices = [Index("accountId")])
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val epochDay: Long,
    val balance: Long,
    val recordedAtMillis: Long = 0,
)

@Serializable
@Entity(tableName = "plan_groups")
data class PlanGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
)

@Serializable
@Entity(tableName = "plan_items", indices = [Index("groupId")])
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long,
    val type: String,
    val accountId: Long?,
    val toAccountId: Long?,
    val timing: String,
    val flexibility: String,
    val tracking: String,
    val note: String,
    val archived: Boolean,
    val sortOrder: Int,
)

@Serializable
@Entity(tableName = "plan_amounts", primaryKeys = ["itemId", "method", "year", "month"])
data class PlanAmountEntity(
    val itemId: Long,
    val method: String,
    val year: Int,
    val month: Int,
    val amount: Long,
)

@Serializable
@Entity(tableName = "item_actuals", primaryKeys = ["itemId", "method", "year", "month"])
data class ItemActualEntity(
    val itemId: Long,
    val method: String,
    val year: Int,
    val month: Int,
    val status: String,
    val updatedEpochDay: Long,
)

@Serializable
@Entity(tableName = "ledger_entries", indices = [Index("itemId"), Index("epochDay")])
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val type: String,
    val amount: Long,
    val itemId: Long?,
    val method: String?,
    val accountId: Long?,
    val toAccountId: Long?,
    val note: String,
    val source: String = "MANUAL",
    val installmentId: Long? = null,
    val createdAtMillis: Long = 0,
)

@Serializable
@Entity(tableName = "card_installments", indices = [Index("cardAccountId"), Index("itemId")])
data class CardInstallmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardAccountId: Long?,
    val itemId: Long?,
    val purchaseEpochDay: Long,
    val amount: Long,
    val months: Int,
    val fee: String,
    val feeValue: Double,
    val firstPeriodIndex: Int,
    val note: String,
    val settled: Boolean,
)

@Serializable
@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdEpochDay: Long,
    val changesJson: String,
    val note: String,
)

@Serializable
@Entity(tableName = "check_ins")
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val note: String,
)
