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
    /** 依帳單繳款（R-CARD-20）；false 時卡片只是一個餘額。 */
    val cardSchedule: Boolean = false,
    val cardRatePercent: Double? = null,
    val cardPayAccountId: Long? = null,
    val cardStatementDay: Int? = null,
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
    /** 支出的支付方式（R-MIX-01）；收入與轉帳為 null。 */
    val method: String? = null,
    /** 試算是否依實際刷卡比例推估（R-MIX-03）。 */
    val useActualMix: Boolean = true,
    val flexibility: String,
    val tracking: String,
    val note: String,
    val archived: Boolean,
    val sortOrder: Int,
    val dueDay: Int? = null,
    val extraRepayment: Boolean = false,
    val archivedFrom: Int? = null,
)

@Serializable
@Entity(tableName = "plan_amounts", primaryKeys = ["itemId", "year", "month"])
data class PlanAmountEntity(
    val itemId: Long,
    val year: Int,
    val month: Int,
    val amount: Long,
)

@Serializable
@Entity(tableName = "item_actuals", primaryKeys = ["itemId", "year", "month"])
data class ItemActualEntity(
    val itemId: Long,
    val year: Int,
    val month: Int,
    val status: String,
    val updatedEpochDay: Long,
)

@Serializable
@Entity(tableName = "ledger_entries", indices = [Index("itemId"), Index("epochDay"), Index(value = ["postingKey"], unique = true)])
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
    /** 到期項目與延期付款的識別碼；唯一，保證同一筆不會記兩次。 */
    val postingKey: String? = null,
)

/** 選了「這個月沒有」的到期項目識別碼，之後不再列出。 */
@Serializable
@Entity(tableName = "posted_keys")
data class PostedKeyEntity(
    @PrimaryKey val key: String,
    val epochDay: Long,
)

/** 使用者輸入的某張卡某一期帳單（R-CARD-23）；year/month 是結帳日所在的年月。 */
@Serializable
@Entity(tableName = "card_statements", primaryKeys = ["cardId", "year", "month"])
data class CardStatementEntity(
    val cardId: Long,
    val year: Int,
    val month: Int,
    val amount: Long,
    val minimumPayment: Long? = null,
    val coversInterest: Boolean = false,
)

/** 資料世代（只有一列，id = 0）：整份替換資料時加一，和設定裡的值一致時快照才有效。不進備份。 */
@Entity(tableName = "data_generation")
data class DataGenerationEntity(
    @PrimaryKey val id: Int = 0,
    val generation: Long,
)

@Serializable
@Entity(tableName = "deferrals", indices = [Index("itemId")])
data class DeferralEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val method: String,
    val fromYear: Int,
    val fromMonth: Int,
    val dueYear: Int,
    val dueMonth: Int,
    val amount: Long,
    val settled: Boolean,
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
