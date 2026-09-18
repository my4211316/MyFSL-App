package tw.myfsl.app.core.data

import androidx.room.withTransaction
import tw.myfsl.app.core.data.db.AppDatabase
import tw.myfsl.app.core.data.db.BalanceSnapshotEntity
import tw.myfsl.app.core.data.db.CheckInEntity
import tw.myfsl.app.core.data.db.PlanAmountEntity
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.data.db.toColumn
import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.data.db.toModel
import tw.myfsl.app.core.data.db.toPaymentMethod
import tw.myfsl.app.core.domain.BalanceRules
import tw.myfsl.app.core.domain.CheckInResult
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.ImportPreview
import tw.myfsl.app.core.domain.PlanImport
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RecordMark
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.isAfter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 今天日期與寫入時間的來源；測試時可替換。 */
interface TimeProvider {
    fun today(): LocalDate

    /** 寫入時間（epoch 毫秒），用來比較同一天內記帳與回報、校正的先後。 */
    fun nowMillis(): Long
}

/**
 * 所有財務資料的單一入口。畫面透過 [snapshot] 取得完整資料，
 * 再交給 core.domain 的純函式計算。
 */
@Singleton
class FinanceRepository @Inject constructor(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val time: TimeProvider,
) {
    private val accountDao = db.accountDao()
    private val planDao = db.planDao()
    private val actualDao = db.actualDao()
    private val scenarioDao = db.scenarioDao()

    private data class PlanPart(
        val groups: List<PlanGroup>,
        val items: List<PlanItem>,
        val amounts: Map<Int, MonthlyAmounts>,
    )

    private data class ActualPart(
        val actuals: List<ItemActual>,
        val ledger: List<LedgerEntry>,
        val installments: List<CardInstallment>,
    )

    private val planPart: Flow<PlanPart> = combine(
        planDao.observeGroups(),
        planDao.observeItems(),
        planDao.observeAmounts(),
    ) { groups, items, amounts ->
        PlanPart(
            groups = groups.map { it.toModel() },
            items = items.map { it.toModel() },
            amounts = amounts.groupBy { it.year }.mapValues { (_, rows) ->
                rows.groupBy { PlanLine(it.itemId, it.method.toPaymentMethod()) }.mapValues { (_, lineRows) ->
                    List(12) { m -> lineRows.firstOrNull { it.month == m + 1 }?.amount ?: 0L }
                }
            },
        )
    }

    private val actualPart: Flow<ActualPart> = combine(
        actualDao.observeAll(),
        actualDao.observeLedger(),
        actualDao.observeInstallments(),
    ) { actuals, ledger, installments ->
        ActualPart(actuals.map { it.toModel() }, ledger.map { it.toModel() }, installments.map { it.toModel() })
    }

    private val accounts: Flow<List<Account>> = combine(
        accountDao.observeAll(),
        accountDao.observeSnapshots(),
        actualDao.observeLedger(),
    ) { accounts, snapshots, ledger ->
        val latest = snapshots.groupBy { it.accountId }
            .mapValues { (_, list) -> list.maxWith(compareBy({ it.epochDay }, { it.recordedAtMillis }, { it.id })) }
        val entries = ledger.map { it.toModel() }
        accounts.map { entity ->
            val model = entity.toModel()
            val snapshot = latest[entity.id]
            val mark = snapshot?.let { RecordMark(LocalDate.ofEpochDay(it.epochDay), it.recordedAtMillis) }
            val delta = entries
                .filter { mark == null || it.isAfter(mark) }
                .sumOf { BalanceRules.effect(it, model.id, model.kind) }
            model.copy(
                balance = (snapshot?.balance ?: 0L) + delta,
                balanceAsOf = mark?.date,
                balanceRecordedAt = mark?.recordedAt ?: 0L,
            )
        }
    }

    val snapshot: Flow<FinanceSnapshot> = combine(
        accounts,
        planPart,
        actualPart,
        settingsRepository.settings,
        actualDao.observeLatestCheckIn(),
    ) { accounts, plan, actual, settings, checkIn ->
        val latestCardSnapshot = accounts
            .filter { it.kind == AccountKind.CREDIT_CARD }
            .mapNotNull { card -> card.balanceAsOf?.let { RecordMark(it, card.balanceRecordedAt) } }
            .maxWithOrNull(compareBy({ it.date }, { it.recordedAt }))
        FinanceSnapshot(
            today = time.today(),
            accounts = accounts,
            groups = plan.groups,
            items = plan.items,
            amountsByYear = plan.amounts,
            actuals = actual.actuals,
            ledger = actual.ledger,
            installments = actual.installments,
            settings = settings,
            lastCheckIn = checkIn?.toModel(),
            unassignedCardSpending = BalanceRules.unassignedCardSpending(actual.ledger, latestCardSnapshot),
        )
    }

    val scenarios: Flow<List<Scenario>> = scenarioDao.observeAll().map { list -> list.map { it.toModel() } }

    fun today(): LocalDate = time.today()

    // ---- 帳戶 ----

    suspend fun saveAccount(account: Account, balance: Money?, asOf: LocalDate = time.today()): Long =
        db.withTransaction {
            val entity = account.toEntity()
            val result = accountDao.upsert(entity)
            val id = if (entity.id == 0L) result else entity.id
            if (balance != null) {
                accountDao.insertSnapshot(
                    BalanceSnapshotEntity(accountId = id, epochDay = asOf.toEpochDay(), balance = balance, recordedAtMillis = time.nowMillis()),
                )
            }
            id
        }

    suspend fun setAccountArchived(id: Long, archived: Boolean) = accountDao.setArchived(id, archived)

    /** 校正帳戶餘額：寫入快照，不修改歷史紀錄。 */
    suspend fun recordBalances(balances: Map<Long, Money>, date: LocalDate = time.today()) = db.withTransaction {
        val now = time.nowMillis()
        balances.forEach { (id, value) ->
            accountDao.insertSnapshot(BalanceSnapshotEntity(accountId = id, epochDay = date.toEpochDay(), balance = value, recordedAtMillis = now))
        }
    }

    // ---- 計畫 ----

    suspend fun saveGroup(group: PlanGroup): Long {
        val entity = group.toEntity()
        val result = planDao.upsertGroup(entity)
        return if (entity.id == 0L) result else entity.id
    }

    /** 儲存項目與各年度、各支付方式的 12 個月金額（收入與轉帳的支付方式為 null）。 */
    suspend fun saveItem(item: PlanItem, amountsByYear: Map<Int, Map<PaymentMethod?, List<Money>>>): Long =
        db.withTransaction {
            val entity = item.toEntity()
            val result = planDao.upsertItem(entity)
            val id = if (entity.id == 0L) result else entity.id
            amountsByYear.forEach { (year, byMethod) ->
                planDao.deleteAmounts(id, year)
                planDao.upsertAmounts(
                    byMethod.flatMap { (method, months) ->
                        months.mapIndexedNotNull { index, amount ->
                            if (amount != 0L) PlanAmountEntity(id, method.toColumn(), year, index + 1, amount) else null
                        }
                    },
                )
            }
            id
        }

    suspend fun setItemArchived(id: Long, archived: Boolean) = planDao.setItemArchived(id, archived)

    /**
     * 匯入年度計畫。[ImportMode.REPLACE_YEAR] 會覆蓋檔案裡出現的項目在該年度的金額，
     * 檔案裡沒有的項目保持不動；[ImportMode.ADD_ONLY] 只新增還不存在的項目。
     * 回傳實際寫入的項目數。
     */
    suspend fun importPlan(preview: ImportPreview, mode: ImportMode = ImportMode.REPLACE_YEAR): Int =
        db.withTransaction {
            val existingGroups = planDao.observeGroups().first().map { it.toModel() }.toMutableList()
            val existingItems = planDao.observeItems().first().map { it.toModel() }
            var written = 0
            preview.items.forEach { entry ->
                val key = PlanImport.normalize(entry.groupName)
                val groupId = existingGroups.firstOrNull { PlanImport.normalize(it.name) == key }?.id
                    ?: saveGroup(PlanGroup(name = entry.groupName, sortOrder = existingGroups.size + 1))
                        .also { id -> existingGroups += PlanGroup(id, entry.groupName, existingGroups.size + 1) }
                val existing = existingItems.firstOrNull {
                    PlanImport.normalize(it.name) == PlanImport.normalize(entry.item.name) && it.groupId == groupId
                }
                if (mode == ImportMode.ADD_ONLY && existing != null) return@forEach
                saveItem(
                    entry.item.copy(id = existing?.id ?: 0, groupId = groupId, sortOrder = existing?.sortOrder ?: 0),
                    mapOf(preview.year to entry.amounts),
                )
                written++
            }
            written
        }

    // ---- 記帳與執行控管 ----

    /** 新增記帳並蓋上寫入時間。 */
    suspend fun addLedgerEntry(entry: LedgerEntry): Long =
        actualDao.insertLedger(entry.copy(createdAt = time.nowMillis()).toEntity())

    suspend fun deleteLedgerEntry(id: Long) = actualDao.deleteLedger(id)

    /** 修改記帳；保留原本的寫入時間，讓它和餘額校正的先後關係不變。 */
    suspend fun updateLedgerEntry(entry: LedgerEntry) = actualDao.updateLedger(entry.toEntity())

    /**
     * 分期消費：一筆記帳（全額，用來算預算）＋一筆分期（各期入帳，用來算卡債與額度）。
     * 第一期預設落在下個月的繳款日所在半月。
     */
    suspend fun addInstallmentPurchase(entry: LedgerEntry, installment: CardInstallment): Long =
        db.withTransaction {
            val now = time.nowMillis()
            val installmentId = actualDao.upsertInstallment(installment.copy(id = 0).toEntity())
            actualDao.insertLedger(entry.copy(installmentId = installmentId, createdAt = now).toEntity())
            installmentId
        }

    suspend fun settleInstallment(id: Long) = actualDao.setInstallmentSettled(id, true)

    /** 刪掉一筆分期消費：分期本身與它的記帳一起刪。 */
    suspend fun deleteInstallment(id: Long) = db.withTransaction {
        actualDao.deleteLedgerOfInstallment(id)
        actualDao.deleteInstallment(id)
    }

    /**
     * 本週檢查：一次寫入對帳產生的記帳、到期確認狀態與校正餘額。
     * 記帳與校正使用同一個寫入時間，所以校正餘額已經包含這些記帳，不會重複扣。
     */
    suspend fun recordCheckIn(
        result: CheckInResult,
        date: LocalDate = time.today(),
        note: String = "",
    ) = db.withTransaction {
        val now = time.nowMillis()
        result.entries.forEach { entry ->
            actualDao.insertLedger(entry.copy(createdAt = now).toEntity())
        }
        if (result.actuals.isNotEmpty()) actualDao.upsert(result.actuals.map { it.toEntity() })
        result.balances.forEach { (id, value) ->
            accountDao.insertSnapshot(
                BalanceSnapshotEntity(accountId = id, epochDay = date.toEpochDay(), balance = value, recordedAtMillis = now),
            )
        }
        actualDao.insertCheckIn(CheckInEntity(epochDay = date.toEpochDay(), note = note))
    }

    suspend fun saveActual(actual: ItemActual) = actualDao.upsert(listOf(actual.toEntity()))

    // ---- 試算情境 ----

    suspend fun saveScenario(scenario: Scenario): Long {
        val entity = scenario.toEntity()
        val result = scenarioDao.upsert(entity)
        return if (entity.id == 0L) result else entity.id
    }

    suspend fun deleteScenario(id: Long) = scenarioDao.delete(id)

    // ---- 維護 ----

    /** 載入示意資料試用（會清掉現有資料）。金額為虛構。 */
    suspend fun installSample() {
        clearAll()
        db.withTransaction {
            SampleHousehold.accounts.forEach { account ->
                accountDao.upsert(account.toEntity())
                accountDao.insertSnapshot(
                    BalanceSnapshotEntity(
                        accountId = account.id,
                        epochDay = SampleHousehold.today.toEpochDay(),
                        balance = account.balance,
                        recordedAtMillis = 1,
                    ),
                )
            }
            SampleHousehold.groups.forEach { planDao.upsertGroup(it.toEntity()) }
            SampleHousehold.items.forEach { planDao.upsertItem(it.toEntity()) }
            listOf(2026, 2027, 2028).forEach { year ->
                planDao.upsertAmounts(
                    SampleHousehold.yearlyPlan.flatMap { (line, months) ->
                        months.mapIndexedNotNull { index, amount ->
                            if (amount != 0L) PlanAmountEntity(line.itemId, line.method.toColumn(), year, index + 1, amount) else null
                        }
                    },
                )
            }
            SampleHousehold.septemberLedger.forEach { actualDao.insertLedger(it.toEntity()) }
        }
        settingsRepository.setCashAccount(SampleHousehold.CASH)
        settingsRepository.setTransferAccount(SampleHousehold.BANK)
        settingsRepository.setPickCard(true)
        settingsRepository.setSafetyLevel(SampleHousehold.settings.safetyLevel)
    }

    /** 備份檔已經寫出去之後才呼叫，用來提醒「多久沒備份」。 */
    suspend fun markBackedUp() = settingsRepository.setLastBackup(time.today().toEpochDay())

    suspend fun setOnboarded() = settingsRepository.setOnboarded(true)

    /** 完整備份：所有資料表與設定。 */
    suspend fun exportBackup(): BackupFile = with(db.maintenanceDao()) {
        db.withTransaction {
            BackupFile(
                exportedAtMillis = time.nowMillis(),
                accounts = allAccounts(),
                snapshots = allSnapshots(),
                groups = allGroups(),
                items = allItems(),
                amounts = allAmounts(),
                actuals = allActuals(),
                ledger = allLedger(),
                installments = allInstallments(),
                scenarios = allScenarios(),
                checkIns = allCheckIns(),
                settings = SettingsBackup.of(settingsRepository.settings.first()),
            )
        }
    }

    /** 用備份取代目前所有資料（先清空再寫入，同一個交易，失敗時不會留下一半）。 */
    suspend fun restoreBackup(file: BackupFile) {
        with(db.maintenanceDao()) {
            db.withTransaction {
                clearAccounts(); clearSnapshots(); clearGroups(); clearItems(); clearAmounts()
                clearActuals(); clearLedger(); clearScenarios(); clearCheckIns(); clearInstallments()
                insertAccounts(file.accounts)
                insertSnapshots(file.snapshots)
                insertGroups(file.groups)
                insertItems(file.items)
                insertAmounts(file.amounts)
                insertActuals(file.actuals)
                insertInstallments(file.installments)
                insertLedger(file.ledger)
                insertScenarios(file.scenarios)
                insertCheckIns(file.checkIns)
            }
        }
        file.settings?.let { settingsRepository.save(it.toSettings(settingsRepository.settings.first())) }
    }

    suspend fun clearAll() = db.withTransaction {
        with(db.maintenanceDao()) {
            clearAccounts(); clearSnapshots(); clearGroups(); clearItems(); clearAmounts()
            clearActuals(); clearLedger(); clearScenarios(); clearCheckIns(); clearInstallments()
        }
    }
}
