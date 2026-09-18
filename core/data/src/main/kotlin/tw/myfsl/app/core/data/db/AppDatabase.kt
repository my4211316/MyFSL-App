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
    ],
    // 1.0.0 首發的結構。之後改 schema：提高版本，並在 Migrations 加上升級 SQL（MigrationTest 會檢查）。
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 1
        const val NAME = "myfsl.db"
    }

    abstract fun accountDao(): AccountDao
    abstract fun planDao(): PlanDao
    abstract fun actualDao(): ActualDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun maintenanceDao(): MaintenanceDao
}
