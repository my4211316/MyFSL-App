package tw.myfsl.app.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Upsert
    suspend fun upsert(entity: AccountEntity): Long

    @Query("UPDATE accounts SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT * FROM balance_snapshots ORDER BY epochDay, id")
    fun observeSnapshots(): Flow<List<BalanceSnapshotEntity>>

    @Insert
    suspend fun insertSnapshot(entity: BalanceSnapshotEntity): Long
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_groups ORDER BY sortOrder, id")
    fun observeGroups(): Flow<List<PlanGroupEntity>>

    @Query("SELECT * FROM plan_items ORDER BY sortOrder, id")
    fun observeItems(): Flow<List<PlanItemEntity>>

    @Query("SELECT * FROM plan_amounts")
    fun observeAmounts(): Flow<List<PlanAmountEntity>>

    @Upsert
    suspend fun upsertGroup(entity: PlanGroupEntity): Long

    @Upsert
    suspend fun upsertItem(entity: PlanItemEntity): Long

    @Upsert
    suspend fun upsertAmounts(entities: List<PlanAmountEntity>)

    @Query("DELETE FROM plan_amounts WHERE itemId = :itemId AND year = :year")
    suspend fun deleteAmounts(itemId: Long, year: Int)

    @Query("UPDATE plan_items SET archived = :archived WHERE id = :id")
    suspend fun setItemArchived(id: Long, archived: Boolean)
}

@Dao
interface ActualDao {
    @Query("SELECT * FROM item_actuals")
    fun observeAll(): Flow<List<ItemActualEntity>>

    @Upsert
    suspend fun upsert(entities: List<ItemActualEntity>)

    @Query("SELECT * FROM ledger_entries ORDER BY epochDay DESC, id DESC")
    fun observeLedger(): Flow<List<LedgerEntryEntity>>

    @Insert
    suspend fun insertLedger(entity: LedgerEntryEntity): Long

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteLedger(id: Long)

    @Update
    suspend fun updateLedger(entity: LedgerEntryEntity)

    @Query("SELECT * FROM check_ins ORDER BY epochDay DESC, id DESC LIMIT 1")
    fun observeLatestCheckIn(): Flow<CheckInEntity?>

    @Insert
    suspend fun insertCheckIn(entity: CheckInEntity): Long

    @Query("SELECT * FROM card_installments ORDER BY id DESC")
    fun observeInstallments(): Flow<List<CardInstallmentEntity>>

    @Upsert
    suspend fun upsertInstallment(entity: CardInstallmentEntity): Long

    @Query("DELETE FROM card_installments WHERE id = :id")
    suspend fun deleteInstallment(id: Long)

    @Query("DELETE FROM ledger_entries WHERE installmentId = :installmentId")
    suspend fun deleteLedgerOfInstallment(installmentId: Long)

    @Query("UPDATE card_installments SET settled = :settled WHERE id = :id")
    suspend fun setInstallmentSettled(id: Long, settled: Boolean)
}

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY createdEpochDay DESC, id DESC")
    fun observeAll(): Flow<List<ScenarioEntity>>

    @Upsert
    suspend fun upsert(entity: ScenarioEntity): Long

    @Query("DELETE FROM scenarios WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MaintenanceDao {
    // ---- 完整備份：全部資料（含封存與已結清） ----
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun allAccounts(): List<AccountEntity>
    @Query("SELECT * FROM balance_snapshots ORDER BY id") suspend fun allSnapshots(): List<BalanceSnapshotEntity>
    @Query("SELECT * FROM plan_groups ORDER BY id") suspend fun allGroups(): List<PlanGroupEntity>
    @Query("SELECT * FROM plan_items ORDER BY id") suspend fun allItems(): List<PlanItemEntity>
    @Query("SELECT * FROM plan_amounts") suspend fun allAmounts(): List<PlanAmountEntity>
    @Query("SELECT * FROM item_actuals") suspend fun allActuals(): List<ItemActualEntity>
    @Query("SELECT * FROM ledger_entries ORDER BY id") suspend fun allLedger(): List<LedgerEntryEntity>
    @Query("SELECT * FROM card_installments ORDER BY id") suspend fun allInstallments(): List<CardInstallmentEntity>
    @Query("SELECT * FROM scenarios ORDER BY id") suspend fun allScenarios(): List<ScenarioEntity>
    @Query("SELECT * FROM check_ins ORDER BY id") suspend fun allCheckIns(): List<CheckInEntity>

    // ---- 還原：保留原本的 id ----
    @Insert suspend fun insertAccounts(list: List<AccountEntity>)
    @Insert suspend fun insertSnapshots(list: List<BalanceSnapshotEntity>)
    @Insert suspend fun insertGroups(list: List<PlanGroupEntity>)
    @Insert suspend fun insertItems(list: List<PlanItemEntity>)
    @Insert suspend fun insertAmounts(list: List<PlanAmountEntity>)
    @Insert suspend fun insertActuals(list: List<ItemActualEntity>)
    @Insert suspend fun insertLedger(list: List<LedgerEntryEntity>)
    @Insert suspend fun insertInstallments(list: List<CardInstallmentEntity>)
    @Insert suspend fun insertScenarios(list: List<ScenarioEntity>)
    @Insert suspend fun insertCheckIns(list: List<CheckInEntity>)

    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM balance_snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM plan_groups") suspend fun clearGroups()
    @Query("DELETE FROM plan_items") suspend fun clearItems()
    @Query("DELETE FROM plan_amounts") suspend fun clearAmounts()
    @Query("DELETE FROM item_actuals") suspend fun clearActuals()
    @Query("DELETE FROM ledger_entries") suspend fun clearLedger()
    @Query("DELETE FROM scenarios") suspend fun clearScenarios()
    @Query("DELETE FROM check_ins") suspend fun clearCheckIns()
    @Query("DELETE FROM card_installments") suspend fun clearInstallments()
}
