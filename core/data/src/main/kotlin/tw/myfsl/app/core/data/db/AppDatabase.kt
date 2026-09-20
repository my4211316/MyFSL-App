package tw.myfsl.app.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        BalanceSnapshotEntity::class,
        PlanGroupEntity::class,
        PlanItemEntity::class,
        PlanAmountEntity::class,
        ItemActualEntity::class,
        LedgerEntryEntity::class,
        ScenarioEntity::class,
        CheckInEntity::class,
        CardInstallmentEntity::class,
        PostedKeyEntity::class,
        DeferralEntity::class,
        CardStatementEntity::class,
        DataGenerationEntity::class,
    ],
    // 第 2 版：預算不再分支付方式（R-MIX-01）。改 schema：提高版本，並在 Migrations 加上升級 SQL（MigrationTest 會檢查）。
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 2
        const val NAME = "myfsl.db"
    }

    abstract fun accountDao(): AccountDao
    abstract fun planDao(): PlanDao
    abstract fun actualDao(): ActualDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun maintenanceDao(): MaintenanceDao
}
