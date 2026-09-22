package tw.myfsl.app.core.data

import androidx.room.withTransaction
import tw.myfsl.app.core.data.db.ScenarioEntity
import tw.myfsl.app.core.data.db.PlanItemEntity
import tw.myfsl.app.core.data.db.PlanGroupEntity
import tw.myfsl.app.core.data.db.LedgerEntryEntity
import tw.myfsl.app.core.data.db.ItemActualEntity
import tw.myfsl.app.core.data.db.DeferralEntity
import tw.myfsl.app.core.data.db.DataGenerationEntity
import tw.myfsl.app.core.data.db.CardInstallmentEntity
import tw.myfsl.app.core.data.db.CardStatementEntity
import tw.myfsl.app.core.data.db.AccountEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.room.InvalidationTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import tw.myfsl.app.core.model.AppSettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.myfsl.app.core.data.db.AppDatabase
import tw.myfsl.app.core.data.db.BalanceSnapshotEntity
import tw.myfsl.app.core.data.db.CheckInEntity
import tw.myfsl.app.core.data.db.PlanAmountEntity
import tw.myfsl.app.core.data.db.PostedKeyEntity
import tw.myfsl.app.core.data.db.toColumn
import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.data.db.toModel
import tw.myfsl.app.core.data.db.toPaymentMethod
import tw.myfsl.app.core.domain.BalanceRules
import tw.myfsl.app.core.domain.CheckInResult
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.ImportPreview
import tw.myfsl.app.core.domain.PlanImport
import tw.myfsl.app.core.domain.RecordRules
import tw.myfsl.app.core.domain.BillCorrection
import tw.myfsl.app.core.domain.Deletion
import tw.myfsl.app.core.domain.DueItems
import tw.myfsl.app.core.domain.DueRecord
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.Deferral
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.PostingKeys
import tw.myfsl.app.core.model.RecordMark
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.isAfter
import tw.myfsl.app.core.sample.SampleHousehold
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
    private val restoreJournal: RestoreJournal,
) {
    private val accountDao = db.accountDao()
    private val planDao = db.planDao()
    private val actualDao = db.actualDao()
    private val scenarioDao = db.scenarioDao()

    /** 所有帳務寫入與還原共用的鎖（F11）。不可重入：內部組合寫入時呼叫 *Locked 的私有方法。 */
    private val maintenance = Mutex()

    /** 資料層自己的工作（共用快照、還原）跑在這裡，不跟著某個畫面結束。 */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 寫入被拒絕時給畫面顯示的說明。 */
    val notices: SharedFlow<String> = _notices

    /** 帳務寫入的共同入口（F11）：拿鎖、確認 READY、比對世代，見 [WriteGate]。 */
    private val gate = WriteGate(
        maintenance = maintenance,
        state = { restore.state.value },
        currentGeneration = { currentGenerationLocked() },
        onRejected = { _notices.tryEmit(it) },
    )

    private suspend fun <T> writing(expected: Long?, block: suspend () -> T): T = gate.run(expected, block)

    /** 整份替換資料（清除、示意資料、世代對齊）；結束時資料庫與設定的世代一致，見 [WriteGate.replacingAll]。 */
    private suspend fun <T> replacingAll(block: suspend () -> T): T = gate.replacingAll(block)

    /** 資料庫與設定裡的世代；不一致時為 [FinanceSnapshot.NO_GENERATION]。 */
    private suspend fun currentGenerationLocked(): Long {
        val inDb = db.maintenanceDao().generation() ?: 0L
        val inSettings = settingsRepository.settings.first().dataGeneration
        return if (inDb == inSettings) inDb else FinanceSnapshot.NO_GENERATION
    }

    /** 整份替換資料時呼叫（在資料庫交易裡）：世代加一，回傳新世代。設定要另外用 [SettingsRepository.setDataGeneration] 寫同一個值。 */
    private suspend fun bumpGenerationInTransaction(): Long {
        val next = (db.maintenanceDao().generation() ?: 0L) + 1
        db.maintenanceDao().setGeneration(DataGenerationEntity(generation = next))
        return next
    }

    /** 資料庫一次讀完的內容（同一個交易，不會一半新一半舊）。 */
    private class DbState(
        val accounts: List<AccountEntity>,
        val snapshots: List<BalanceSnapshotEntity>,
        val groups: List<PlanGroupEntity>,
        val items: List<PlanItemEntity>,
        val amounts: List<PlanAmountEntity>,
        val actuals: List<ItemActualEntity>,
        val ledger: List<LedgerEntryEntity>,
        val installments: List<CardInstallmentEntity>,
        val scenarios: List<ScenarioEntity>,
        val checkIns: List<CheckInEntity>,
        val postedKeys: List<PostedKeyEntity>,
        val deferrals: List<DeferralEntity>,
        val statements: List<CardStatementEntity>,
        val generation: Long,
    )

    /** 任何一張表變動就通知一次（開始時也先通知一次）。 */
    private val tablesChanged: Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        db.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }.conflate()

    /**
     * 資料庫的一致快照：每次有表變動，就在同一個交易裡把所有表讀一遍（F11 第 2 點）。
     * 以前是每張表各自觀察再組合，還原後可能短暫出現「世代是新的、記帳還是舊的」的組合。
     */
    private val dbState: Flow<DbState> = tablesChanged
        .map {
            with(db.maintenanceDao()) {
                db.withTransaction {
                    DbState(
                        allAccounts(), allSnapshots(), allGroups(), allItems(), allAmounts(), allActuals(), allLedger(),
                        allInstallments(), allScenarios(), allCheckIns(), allPostedKeys(), allDeferrals(), allStatements(), generation() ?: 0L,
                    )
                }
            }
        }
        .shareIn(repositoryScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val snapshot: Flow<FinanceSnapshot> = combine(dbState, settingsRepository.settings, ::buildSnapshot)

    /** 快照與情境：由同一次讀出的資料庫內容產生，情境一定屬於快照的世代（試算畫面用）。 */
    val snapshotWithScenarios: Flow<SnapshotWithScenarios> = combine(dbState, settingsRepository.settings) { d, settings ->
        SnapshotWithScenarios(buildSnapshot(d, settings), d.scenarios.map { it.toModel() })
    }

    private fun buildSnapshot(d: DbState, settings: AppSettings): FinanceSnapshot {
        val entries = d.ledger.map { it.toModel() }

        // 帳戶餘額：最近一次校正＋之後的記帳
        val byAccount = d.snapshots.groupBy { it.accountId }
        val latest = byAccount.mapValues { (_, list) -> list.maxWith(compareBy({ it.epochDay }, { it.recordedAtMillis }, { it.id })) }
        val accounts = d.accounts.map { entity ->
            val model = entity.toModel()
            val snap = latest[entity.id]
            val mark = snap?.let { RecordMark(LocalDate.ofEpochDay(it.epochDay), it.recordedAtMillis) }
            val delta = entries
                .filter { mark == null || it.isAfter(mark) }
                .sumOf { BalanceRules.effect(it, model.id, model.kind) }
            model.copy(
                balance = (snap?.balance ?: 0L) + delta,
                balanceAsOf = mark?.date,
                balanceRecordedAt = mark?.recordedAt ?: 0L,
            )
        }
        val cardMarks = accounts.filter { it.kind == AccountKind.CREDIT_CARD && !it.archived }.associate { card ->
            card.id to byAccount[card.id].orEmpty().map { RecordMark(LocalDate.ofEpochDay(it.epochDay), it.recordedAtMillis) }
        }
        val fullCardReconcile = BalanceRules.fullCardReconcile(cardMarks)

        return FinanceSnapshot(
            today = time.today(),
            accounts = accounts,
            groups = d.groups.map { it.toModel() },
            items = d.items.map { it.toModel() },
            amountsByYear = d.amounts.groupBy { it.year }.mapValues { (_, rows) ->
                rows.groupBy { PlanLine(it.itemId, methodOf(it.method)) }.mapValues { (_, lineRows) ->
                    List(12) { m -> lineRows.firstOrNull { it.month == m + 1 }?.amount ?: 0L }
                }
            },
            actuals = d.actuals.map { it.toModel() },
            ledger = entries,
            installments = d.installments.map { it.toModel() },
            settings = settings,
            lastCheckIn = d.checkIns.maxWithOrNull(compareBy({ it.epochDay }, { it.id }))?.toModel(),
            unassignedCardSpending = BalanceRules.unassignedCardSpending(entries, fullCardReconcile),
            fullCardReconcile = fullCardReconcile,
            postedKeys = d.postedKeys.map { it.key }.toSet() + entries.mapNotNull { it.postingKey },
            deferrals = d.deferrals.map { it.toModel() },
            cardStatements = d.statements.map { it.toModel() },
            // 資料庫與設定的世代一致才有效；不一致表示資料正在更新，寫入會被拒絕。
            generation = if (d.generation == settings.dataGeneration) d.generation else FinanceSnapshot.NO_GENERATION,
        )
    }


    fun today(): LocalDate = time.today()

    // ---- 到期項目（R-DUE）：不會自動寫入，使用者點了才記下 ----

    /** 第一次開 App 時把到期項目的起算日設成今天：之前到期的款項視為已包含在輸入的餘額裡。 */
    suspend fun startDueTracking() = writing(null) { settingsRepository.startAutoPostingIfNeeded(time.today().toEpochDay()) }

    /**
     * 記下一個到期項目（同一個交易內寫入記帳與貸款剩餘期數）。
     * 回傳第一筆記帳的 id（復原用）；已經記過（識別碼重複，例如連點兩下）時不寫入，回傳 null。
     */
    suspend fun recordDue(record: DueRecord, generation: Long): Long? = writing(generation) {
        val keys = record.entries.mapNotNull { it.postingKey }
        val existing = actualDao.observeLedger().first().mapNotNull { it.postingKey }.toSet()
        if (keys.isEmpty() || keys.any { it in existing }) return@writing null
        val now = time.nowMillis()
        db.withTransaction {
            val ids = record.entries.map { actualDao.insertLedger(it.copy(createdAt = now).toEntity()) }
            record.loanRemaining?.let { (id, months) -> accountDao.setLoanRemainingMonths(id, months) }
            ids.first()
        }
    }

    /** 這個月沒有這筆：不記帳，只記下已經處理過，之後不再列出。 */
    suspend fun skipDue(key: String, generation: Long) = writing(generation) { actualDao.insertPostedKeys(listOf(PostedKeyEntity(key, time.today().toEpochDay()))) }

    // ---- 帳戶 ----

    suspend fun saveAccount(account: Account, balance: Money?, generation: Long, asOf: LocalDate = time.today()): Long = writing(generation) {
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
    }

    suspend fun setAccountArchived(id: Long, archived: Boolean, generation: Long) = writing(generation) { accountDao.setArchived(id, archived) }

    /** 校正帳戶餘額：寫入快照，不修改歷史紀錄。 */
    suspend fun recordBalances(balances: Map<Long, Money>, generation: Long, date: LocalDate = time.today()) = writing(generation) { db.withTransaction {
        val now = time.nowMillis()
        balances.forEach { (id, value) ->
            accountDao.insertSnapshot(BalanceSnapshotEntity(accountId = id, epochDay = date.toEpochDay(), balance = value, recordedAtMillis = now))
        }
    } }

    // ---- 計畫 ----

    suspend fun saveGroup(group: PlanGroup, generation: Long): Long = writing(generation) { saveGroupLocked(group) }

    private suspend fun saveGroupLocked(group: PlanGroup): Long {
        val entity = group.toEntity()
        val result = planDao.upsertGroup(entity)
        return if (entity.id == 0L) result else entity.id
    }

    /** 儲存項目與各年度、各支付方式的 12 個月金額（R-MIX-01：一列 = 項目 × 支付方式）。 */
    suspend fun saveItem(item: PlanItem, amountsByYear: Map<Int, Map<PaymentMethod?, List<Money>>>, generation: Long): Long =
        writing(generation) { saveItemLocked(item, amountsByYear) }

    private suspend fun saveItemLocked(item: PlanItem, amountsByYear: Map<Int, Map<PaymentMethod?, List<Money>>>): Long =
        db.withTransaction {
            val entity = item.toEntity()
            val result = planDao.upsertItem(entity)
            val id = if (entity.id == 0L) result else entity.id
            amountsByYear.forEach { (year, byMethod) ->
                planDao.deleteAmounts(id, year)
                planDao.upsertAmounts(
                    byMethod.flatMap { (method, months) ->
                        months.mapIndexedNotNull { index, amount ->
                            if (amount != 0L) PlanAmountEntity(id, year, index + 1, amount, method?.name.orEmpty()) else null
                        }
                    },
                )
            }
            id
        }

    /** 資料庫存的是列舉名稱；空字串代表沒有支付方式（收入、轉帳）。 */
    private fun methodOf(raw: String): PaymentMethod? = raw.takeIf { it.isNotEmpty() }?.let { PaymentMethod.valueOf(it) }

    /** 封存從本月起生效；之前月份的計畫與紀錄仍算在歷史報表裡（R-EDT-10）。 */
    suspend fun setItemArchived(id: Long, archived: Boolean, generation: Long) = writing(generation) {
        val today = time.today()
        planDao.setItemArchived(id, archived, if (archived) today.year * 12 + today.monthValue - 1 else null)
    }

    /**
     * 匯入年度計畫。[ImportMode.REPLACE_YEAR] 以檔案為準更新檔案裡出現的項目在該年度的金額，
     * 檔案裡沒有的項目保持不動；[ImportMode.ADD_ONLY] 只新增還不存在的項目。
     * 回傳實際寫入的項目數。
     */
    suspend fun importPlan(preview: ImportPreview, generation: Long, mode: ImportMode = ImportMode.REPLACE_YEAR): Int = writing(generation) {
        db.withTransaction {
            val existingGroups = planDao.observeGroups().first().map { it.toModel() }.toMutableList()
            val existingItems = planDao.observeItems().first().map { it.toModel() }
            var written = 0
            preview.items.forEach { entry ->
                val key = PlanImport.normalize(entry.groupName)
                val groupId = existingGroups.firstOrNull { PlanImport.normalize(it.name) == key }?.id
                    ?: saveGroupLocked(PlanGroup(name = entry.groupName, sortOrder = existingGroups.size + 1))
                        .also { id -> existingGroups += PlanGroup(id, entry.groupName, existingGroups.size + 1) }
                val existing = existingItems.firstOrNull {
                    PlanImport.normalize(it.name) == PlanImport.normalize(entry.item.name) && it.groupId == groupId
                }
                if (mode == ImportMode.ADD_ONLY && existing != null) return@forEach
                saveItemLocked(
                    entry.item.copy(
                        id = existing?.id ?: 0,
                        groupId = groupId,
                        sortOrder = existing?.sortOrder ?: 0,
                        extraRepayment = existing?.extraRepayment ?: false,
                    ),
                    mapOf(preview.year to entry.amounts),
                )
                written++
            }
            written
        }
    }

    // ---- 記帳與執行控管 ----

    /** 新增記帳並蓋上寫入時間。 */
    suspend fun addLedgerEntry(entry: LedgerEntry, generation: Long): Long = writing(generation) {
        actualDao.insertLedger(entry.copy(createdAt = time.nowMillis()).toEntity())
    }

    /**
     * 刪除記帳，並讓相關狀態保持一致（R-REC-EDIT-05、R-REC-EDIT-07）。
     * 要做什麼由 [Deletion.plan] 算出（可以單獨測試），這裡在同一個交易內照做：
     * 分期消費本身整筆取消；某一期分期只刪那一期；到期記下的同一組一起刪並回到清單、
     * 貸款期數加回；帳單校正整筆刪掉；到期確認撤銷「已完成」；延期款改回未付。
     */
    suspend fun deleteLedgerEntry(id: Long, generation: Long) = writing(generation) {
        val snapshot = snapshot.first()
        val entry = snapshot.ledger.firstOrNull { it.id == id } ?: return@writing
        val plan = Deletion.plan(snapshot, entry)
        db.withTransaction {
            plan.cancelInstallmentId?.let { installmentId ->
                actualDao.deleteLedgerOfInstallment(installmentId)
                actualDao.deletePostedKeysWithPrefix("${PostingKeys.INSTALLMENT_PRINCIPAL}$installmentId:")
                actualDao.deletePostedKeysWithPrefix("${PostingKeys.INSTALLMENT_FEE}$installmentId:")
                actualDao.deleteInstallment(installmentId)
            }
            plan.ledgerIds.forEach { actualDao.deleteLedger(it) }
            if (plan.ledgerKeys.isNotEmpty()) actualDao.deleteLedgerByKeys(plan.ledgerKeys)
            if (plan.postedKeys.isNotEmpty()) actualDao.deletePostedKeys(plan.postedKeys)
            plan.unsettleDeferralId?.let { actualDao.setDeferralSettled(it, false) }
            plan.reopenActual?.let { actualDao.deleteActual(it.itemId, it.year, it.month) }
            plan.addLoanMonthTo?.let { accountDao.addLoanRemainingMonth(it) }
            plan.removeStatement?.let { (cardId, ym) -> db.maintenanceDao().deleteStatement(cardId, ym.year, ym.monthValue) }
        }
    }

    /**
     * 帳單校正（R-CARD-23）：同一個交易內取代這一期前一次的差額、寫入新的差額與帳單，
     * 帳單包含這一期利息時把利息設成已處理。要寫什麼由 [BillCorrection.correct] 算出。
     */
    suspend fun saveBillCorrection(result: BillCorrection.Result, generation: Long) = writing(generation) { db.withTransaction {
        actualDao.deleteLedgerByKeys(listOf(result.replaceKey))
        result.entry?.let { actualDao.insertLedger(it.copy(createdAt = time.nowMillis()).toEntity()) }
        db.maintenanceDao().upsertStatement(result.statement.toEntity())
        result.coveredInterestKey?.let { actualDao.insertPostedKeys(listOf(PostedKeyEntity(it, time.today().toEpochDay()))) }
    } }

    /** 刪掉某一期的帳單校正：差額與帳單一起刪，校正時包含的利息回到本月到期。 */
    suspend fun deleteBillCorrection(cardId: Long, ym: java.time.YearMonth, generation: Long) = writing(generation) { db.withTransaction {
        val covered = db.maintenanceDao().allStatements().any { it.cardId == cardId && it.year == ym.year && it.month == ym.monthValue && it.coversInterest }
        actualDao.deleteLedgerByKeys(listOf(BillCorrection.key(cardId, ym)))
        if (covered) actualDao.deletePostedKeys(listOf(DueItems.cardInterestKey(cardId, ym)))
        db.maintenanceDao().deleteStatement(cardId, ym.year, ym.monthValue)
    } }

    /** 修改記帳；保留原本的寫入時間，讓它和餘額校正的先後關係不變。 */
    suspend fun updateLedgerEntry(entry: LedgerEntry, generation: Long) = writing(generation) { actualDao.updateLedger(entry.toEntity()) }

    /**
     * 補登找回的單據，取代之前對帳補的漏記差額（R-REC-03）：同一個交易內
     * 更新（或刪掉）差額、寫入沿用差額時間的明細，超過的部分照今天記。
     */
    suspend fun replaceMissed(missedId: Long, entry: LedgerEntry, generation: Long) = writing(generation) { db.withTransaction {
        val missed = actualDao.ledgerById(missedId)?.toModel() ?: run {
            actualDao.insertLedger(entry.copy(createdAt = time.nowMillis()).toEntity())
            return@withTransaction
        }
        val replacement = RecordRules.replaceMissed(missed, entry)
        replacement.remainingMissed?.let { actualDao.updateLedger(it.toEntity()) } ?: actualDao.deleteLedger(missed.id)
        actualDao.insertLedger(replacement.detail.copy(id = 0).toEntity())
        replacement.extra?.let { actualDao.insertLedger(it.copy(id = 0, createdAt = time.nowMillis()).toEntity()) }
    } }

    /**
     * 分期消費：一筆記帳（全額，用來算預算）＋一筆分期（各期入帳，用來算卡債與額度）。
     * 第一期預設落在下個月的繳款日所在半月。
     */
    suspend fun addInstallmentPurchase(entry: LedgerEntry, installment: CardInstallment, generation: Long): Long = writing(generation) {
        db.withTransaction {
            val now = time.nowMillis()
            val installmentId = actualDao.upsertInstallment(installment.copy(id = 0).toEntity())
            actualDao.insertLedger(entry.copy(installmentId = installmentId, createdAt = now).toEntity())
            installmentId
        }
    }

    suspend fun settleInstallment(id: Long, generation: Long) = writing(generation) { actualDao.setInstallmentSettled(id, true) }

    /** 整筆取消分期（記帳畫面的復原用）：消費、已入帳的各期本金與手續費、選過「這個月沒有」的標記一起刪。 */
    suspend fun deleteInstallment(id: Long, generation: Long) = writing(generation) { db.withTransaction {
        actualDao.deleteLedgerOfInstallment(id)
        actualDao.deletePostedKeysWithPrefix("${PostingKeys.INSTALLMENT_PRINCIPAL}$id:")
        actualDao.deletePostedKeysWithPrefix("${PostingKeys.INSTALLMENT_FEE}$id:")
        actualDao.deleteInstallment(id)
    } }

    /**
     * 本週檢查：一次寫入對帳產生的記帳、到期確認狀態、延期款與校正餘額。
     * 記帳與校正使用同一個寫入時間，所以校正餘額已經包含這些記帳，不會重複扣。
     */
    suspend fun recordCheckIn(
        result: CheckInResult,
        generation: Long,
        date: LocalDate = time.today(),
        note: String = "",
    ) = writing(generation) { db.withTransaction {
        val now = time.nowMillis()
        result.entries.forEach { entry ->
            actualDao.insertLedger(entry.copy(createdAt = now).toEntity())
        }
        if (result.actuals.isNotEmpty()) actualDao.upsert(result.actuals.map { it.toEntity() })
        result.deferrals.forEach { actualDao.upsertDeferral(it.toEntity()) }
        if (result.skippedKeys.isNotEmpty()) {
            actualDao.insertPostedKeys(result.skippedKeys.map { PostedKeyEntity(it, date.toEpochDay()) })
        }
        result.loanRemaining.forEach { (id, months) -> accountDao.setLoanRemainingMonths(id, months) }
        result.balances.forEach { (id, value) ->
            accountDao.insertSnapshot(
                BalanceSnapshotEntity(accountId = id, epochDay = date.toEpochDay(), balance = value, recordedAtMillis = now),
            )
        }
        actualDao.insertCheckIn(CheckInEntity(epochDay = date.toEpochDay(), note = note))
    } }

    suspend fun saveActual(actual: ItemActual, generation: Long) = writing(generation) { actualDao.upsert(listOf(actual.toEntity())) }

    // ---- 試算情境 ----

    suspend fun saveScenario(scenario: Scenario, generation: Long): Long = writing(generation) {
        val entity = scenario.toEntity()
        val result = scenarioDao.upsert(entity)
        if (entity.id == 0L) result else entity.id
    }

    suspend fun deleteScenario(id: Long, generation: Long) = writing(generation) { scenarioDao.delete(id) }

    // ---- 維護 ----

    /** 載入示意資料試用（會清掉現有資料）。金額為虛構。 */
    suspend fun installSample() = replacingAll {
        clearAllLocked()
        val next = db.withTransaction {
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
                            if (amount != 0L) PlanAmountEntity(line.itemId, year, index + 1, amount, line.method?.name.orEmpty()) else null
                        }
                    },
                )
            }
            SampleHousehold.septemberLedger.forEach { actualDao.insertLedger(it.toEntity()) }
            bumpGenerationInTransaction()
        }
        // 資料庫交易提交之後才寫設定的世代（交易失敗時設定不會先變）。
        settingsRepository.setDataGeneration(next)
        settingsRepository.setCashAccount(SampleHousehold.CASH)
        settingsRepository.setTransferAccount(SampleHousehold.BANK)
        settingsRepository.setPickCard(true)
        settingsRepository.setSafetyLevel(SampleHousehold.settings.safetyLevel)
        // 示意資料的餘額是「今天」的；從今天起才列出到期項目。
        settingsRepository.setAutoPostFrom(time.today().toEpochDay())
    }

    /** 備份檔已經寫出去之後才呼叫，用來提醒「多久沒備份」。 */
    suspend fun markBackedUp() = writing(null) { settingsRepository.setLastBackup(time.today().toEpochDay()) }

    suspend fun setOnboarded() = writing(null) { settingsRepository.setOnboarded(true) }

    /** 儲存設定畫面的所有欄位（和帳務寫入共用鎖，還原期間排隊）。 */
    suspend fun saveSettings(settings: AppSettings, generation: Long) = writing(generation) { settingsRepository.save(settings) }

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
                postedKeys = allPostedKeys(),
                deferrals = allDeferrals(),
                cardStatements = allStatements(),
                settings = SettingsBackup.of(settingsRepository.settings.first()),
            )
        }
    }

    private companion object {
        /** 快照要觀察的所有資料表（任何一張變動就整份重讀）。 */
        val TABLES = arrayOf(
            "accounts", "balance_snapshots", "plan_groups", "plan_items", "plan_amounts", "item_actuals", "ledger_entries",
            "card_installments", "scenarios", "check_ins", "posted_keys", "deferrals", "card_statements", "data_generation",
        )
    }

    // ---- 從備份還原（R-DATA-06，F11）：流程在 RestoreCoordinator，可以單獨測試中斷與失敗 ----

    /** 還原時資料庫換掉後的新世代；設定寫同一個值。 */
    private var pendingGeneration: Long? = null

    private val restore = RestoreCoordinator(
        journal = restoreJournal,
        target = object : RestoreTarget {
            override suspend fun export(): BackupFile = exportBackup()
            override suspend fun replaceDatabase(file: BackupFile) {
                pendingGeneration = this@FinanceRepository.replaceDatabase(file)
            }
            override suspend fun replaceSettings(file: BackupFile, autoPostFromFallback: Long?) {
                file.settings?.let { settingsRepository.save(it.toSettings(settingsRepository.settings.first())) }
                settingsRepository.setAutoPostFrom(file.settings?.autoPostFrom ?: autoPostFromFallback)
                pendingGeneration?.let { settingsRepository.setDataGeneration(it) }
            }
        },
        maintenance = maintenance,
        today = { time.today().toEpochDay() },
    )

    /** 還原狀態；不是 READY 時畫面不開放帳務操作。 */
    val restoreState: StateFlow<RestoreState> = restore.state

    /** 用備份取代目前所有資料（先寫復原紀錄，資料庫與設定都成功才標記完成、清紀錄）。在 App 層級執行，發起的畫面離開也會做完。 */
    suspend fun restoreBackup(file: BackupFile) = repositoryScope.async { restore.restore(file) }.await()

    /**
     * 開 App 時先呼叫：上次還原沒完成就放回還原前的資料。放回失敗時可以再呼叫重試。
     * 可以使用之後，把設定裡的世代對齊資料庫（見 [repairDataGeneration]）。
     */
    suspend fun recoverInterruptedRestore(): RestoreCoordinator.Recovery {
        val result = restore.recover()
        if (restore.state.value == RestoreState.READY) repairDataGeneration()
        return result
    }

    /** 整份替換沒有完成（例如設定寫不進去）時為 true：一般寫入全部擋住，畫面只提供「重試」（R-DATA-06 ⑪）。 */
    val maintenanceFailed: StateFlow<Boolean> = gate.maintenanceFailed

    /**
     * 把設定裡的世代對齊資料庫（開 App 時、維護失敗後按「重試」）。
     * 資料庫的世代只會在整份替換的交易裡加一，所以它才是資料真正的版本；對齊後，
     * 之前畫面帶著的舊世代或「沒有世代」仍然不符，照樣被拒絕，App 使用中對齊也安全。
     * 失敗時丟 [MaintenanceFailedException]，阻擋維持。
     */
    suspend fun repairDataGeneration() = replacingAll {
        val inDb = db.maintenanceDao().generation() ?: 0L
        if (settingsRepository.settings.first().dataGeneration != inDb) settingsRepository.setDataGeneration(inDb)
    }

    /** 資料庫整份換成備份的內容（同一個交易），世代加一；回傳新世代。 */
    private suspend fun replaceDatabase(file: BackupFile): Long = with(db.maintenanceDao()) {
        db.withTransaction {
            clearEverything()
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
            insertPostedKeyRows(file.postedKeys)
            insertDeferrals(file.deferrals)
            insertStatements(file.cardStatements)
            bumpGenerationInTransaction()
        }
    }

    private suspend fun clearEverything() = with(db.maintenanceDao()) {
        clearAccounts(); clearSnapshots(); clearGroups(); clearItems(); clearAmounts()
        clearActuals(); clearLedger(); clearScenarios(); clearCheckIns(); clearInstallments()
        clearPostedKeys(); clearDeferrals(); clearStatements()
    }

    suspend fun clearAll() = replacingAll { clearAllLocked() }

    /** 清除所有資料：世代加一，清除前畫面上的操作都會被拒絕。 */
    private suspend fun clearAllLocked() {
        val next = db.withTransaction {
            clearEverything()
            bumpGenerationInTransaction()
        }
        settingsRepository.setDataGeneration(next)
        settingsRepository.setAutoPostFrom(null)
    }
}
