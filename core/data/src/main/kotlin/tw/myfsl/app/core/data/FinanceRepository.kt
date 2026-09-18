package tw.myfsl.app.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        val postedKeys: Set<String>,
        val deferrals: List<Deferral>,
    )

    private data class AccountPart(
        val accounts: List<Account>,
        /** 最近一次「全部卡片一起對帳」的時點。 */
        val fullCardReconcile: RecordMark?,
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
        actualDao.observePostedKeys(),
        actualDao.observeDeferrals(),
    ) { actuals, ledger, installments, keys, deferrals ->
        val entries = ledger.map { it.toModel() }
        ActualPart(
            actuals = actuals.map { it.toModel() },
            ledger = entries,
            installments = installments.map { it.toModel() },
            postedKeys = keys.toSet() + entries.mapNotNull { it.postingKey },
            deferrals = deferrals.map { it.toModel() },
        )
    }

    private val accountPart: Flow<AccountPart> = combine(
        accountDao.observeAll(),
        accountDao.observeSnapshots(),
        actualDao.observeLedger(),
    ) { accounts, snapshots, ledger ->
        val byAccount = snapshots.groupBy { it.accountId }
        val latest = byAccount.mapValues { (_, list) -> list.maxWith(compareBy({ it.epochDay }, { it.recordedAtMillis }, { it.id })) }
        val entries = ledger.map { it.toModel() }
        val models = accounts.map { entity ->
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
        val cardMarks = models.filter { it.kind == AccountKind.CREDIT_CARD && !it.archived }.associate { card ->
            card.id to byAccount[card.id].orEmpty().map { RecordMark(LocalDate.ofEpochDay(it.epochDay), it.recordedAtMillis) }
        }
        AccountPart(models, BalanceRules.fullCardReconcile(cardMarks))
    }

    val snapshot: Flow<FinanceSnapshot> = combine(
        accountPart,
        planPart,
        actualPart,
        settingsRepository.settings,
        actualDao.observeLatestCheckIn(),
    ) { accountPart, plan, actual, settings, checkIn ->
        FinanceSnapshot(
            today = time.today(),
            accounts = accountPart.accounts,
            groups = plan.groups,
            items = plan.items,
            amountsByYear = plan.amounts,
            actuals = actual.actuals,
            ledger = actual.ledger,
            installments = actual.installments,
            settings = settings,
            lastCheckIn = checkIn?.toModel(),
            unassignedCardSpending = BalanceRules.unassignedCardSpending(actual.ledger, accountPart.fullCardReconcile),
            postedKeys = actual.postedKeys,
            deferrals = actual.deferrals,
        )
    }

    val scenarios: Flow<List<Scenario>> = scenarioDao.observeAll().map { list -> list.map { it.toModel() } }

    fun today(): LocalDate = time.today()

    // ---- 到期項目（R-DUE）：不會自動寫入，使用者點了才記下 ----

    private val dueLock = Mutex()

    /** 第一次開 App 時把到期項目的起算日設成今天：之前到期的款項視為已包含在輸入的餘額裡。 */
    suspend fun startDueTracking() = settingsRepository.startAutoPostingIfNeeded(time.today().toEpochDay())

    /**
     * 記下一個到期項目（同一個交易內寫入記帳、貸款剩餘期數與清掉的既有卡循）。
     * 回傳第一筆記帳的 id（復原用）；已經記過（識別碼重複，例如連點兩下）時不寫入，回傳 null。
     */
    suspend fun recordDue(record: DueRecord): Long? = dueLock.withLock {
        val keys = record.entries.mapNotNull { it.postingKey }
        val existing = actualDao.observeLedger().first().mapNotNull { it.postingKey }.toSet()
        if (keys.isEmpty() || keys.any { it in existing }) return@withLock null
        val now = time.nowMillis()
        db.withTransaction {
            val ids = record.entries.map { actualDao.insertLedger(it.copy(createdAt = now).toEntity()) }
            record.loanRemaining?.let { (id, months) -> accountDao.setLoanRemainingMonths(id, months) }
            record.cardTerms?.let { (id, terms) -> accountDao.setCardRevolvingBalance(id, terms.revolvingBalance) }
            ids.first()
        }
    }

    /** 這個月沒有這筆：不記帳，只記下已經處理過，之後不再列出。 */
    suspend fun skipDue(key: String) = actualDao.insertPostedKeys(listOf(PostedKeyEntity(key, time.today().toEpochDay())))

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

    /** 封存從本月起生效；之前月份的計畫與紀錄仍算在歷史報表裡（R-EDT-10）。 */
    suspend fun setItemArchived(id: Long, archived: Boolean) {
        val today = time.today()
        planDao.setItemArchived(id, archived, if (archived) today.year * 12 + today.monthValue - 1 else null)
    }

    /**
     * 匯入年度計畫。[ImportMode.REPLACE_YEAR] 以檔案為準更新檔案裡出現的項目在該年度的金額，
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

    // ---- 記帳與執行控管 ----

    /** 新增記帳並蓋上寫入時間。 */
    suspend fun addLedgerEntry(entry: LedgerEntry): Long =
        actualDao.insertLedger(entry.copy(createdAt = time.nowMillis()).toEntity())

    /**
     * 刪除記帳，並讓相關狀態保持一致（R-REC-EDIT-07）：
     * - 到期確認的補記：同一列同一月沒有其他確認補記時，撤銷「已完成」，下次檢查會再問。
     * - 延期款的付款：延期款改回未付清。
     * - 到期記下的（R-DUE）：同一組的記帳一起刪（貸款的本金與利息、分期同一期的本金與手續費），
     *   回到本月到期清單；貸款剩餘期數加回一期。
     */
    suspend fun deleteLedgerEntry(id: Long) = db.withTransaction {
        val entry = actualDao.ledgerById(id)?.toModel() ?: return@withTransaction
        actualDao.deleteLedger(id)
        val key = entry.postingKey
        when {
            key != null && key.startsWith(PostingKeys.DEFERRAL) ->
                key.removePrefix(PostingKeys.DEFERRAL).toLongOrNull()?.let { actualDao.setDeferralSettled(it, false) }

            entry.source == EntrySource.CONFIRMED && entry.itemId != null -> {
                val itemId = entry.itemId ?: return@withTransaction
                val others = actualDao.observeLedger().first().map { it.toModel() }.any {
                    it.id != id && it.source == EntrySource.CONFIRMED && it.postingKey == null &&
                        it.itemId == itemId && it.method == entry.method &&
                        it.date.year == entry.date.year && it.date.monthValue == entry.date.monthValue
                }
                if (!others) actualDao.deleteActual(itemId, entry.method.toColumn(), entry.date.year, entry.date.monthValue)
            }

            key != null -> {
                val group = DueItems.groupKeys(key)
                actualDao.deleteLedgerByKeys(group)
                actualDao.deletePostedKeys(group)
                DueItems.loanIdOf(key)?.let { accountDao.addLoanRemainingMonth(it) }
            }
        }
    }

    /** 修改記帳；保留原本的寫入時間，讓它和餘額校正的先後關係不變。 */
    suspend fun updateLedgerEntry(entry: LedgerEntry) = actualDao.updateLedger(entry.toEntity())

    /**
     * 補登找回的單據，取代之前對帳補的漏記差額（R-REC-03）：同一個交易內
     * 更新（或刪掉）差額、寫入沿用差額時間的明細，超過的部分照今天記。
     */
    suspend fun replaceMissed(missedId: Long, entry: LedgerEntry) = db.withTransaction {
        val missed = actualDao.ledgerById(missedId)?.toModel() ?: run {
            actualDao.insertLedger(entry.copy(createdAt = time.nowMillis()).toEntity())
            return@withTransaction
        }
        val replacement = RecordRules.replaceMissed(missed, entry)
        replacement.remainingMissed?.let { actualDao.updateLedger(it.toEntity()) } ?: actualDao.deleteLedger(missed.id)
        actualDao.insertLedger(replacement.detail.copy(id = 0).toEntity())
        replacement.extra?.let { actualDao.insertLedger(it.copy(id = 0, createdAt = time.nowMillis()).toEntity()) }
    }

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

    /** 刪掉一筆分期消費：分期本身與它的消費、已入帳的各期一起刪。 */
    suspend fun deleteInstallment(id: Long) = db.withTransaction {
        actualDao.deleteLedgerOfInstallment(id)
        actualDao.deleteInstallment(id)
    }

    /**
     * 本週檢查：一次寫入對帳產生的記帳、到期確認狀態、延期款與校正餘額。
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
        result.deferrals.forEach { actualDao.upsertDeferral(it.toEntity()) }
        if (result.skippedKeys.isNotEmpty()) {
            actualDao.insertPostedKeys(result.skippedKeys.map { PostedKeyEntity(it, date.toEpochDay()) })
        }
        result.loanRemaining.forEach { (id, months) -> accountDao.setLoanRemainingMonths(id, months) }
        result.cardTerms.forEach { (id, terms) -> accountDao.setCardRevolvingBalance(id, terms.revolvingBalance) }
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
        // 示意資料的餘額是「今天」的；從今天起才列出到期項目。
        settingsRepository.setAutoPostFrom(time.today().toEpochDay())
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
                postedKeys = allPostedKeys(),
                deferrals = allDeferrals(),
                settings = SettingsBackup.of(settingsRepository.settings.first()),
            )
        }
    }

    /**
     * 用備份取代目前所有資料（R-DATA-06）。
     * 資料庫在同一個交易裡先清空再寫入；設定在資料庫之後寫。設定寫入失敗時，
     * 用還原前自動做的備份把資料庫與設定都放回去，不會留下「資料是新的、設定是舊的」。
     */
    suspend fun restoreBackup(file: BackupFile) {
        val previous = exportBackup()
        replaceDatabase(file)
        try {
            file.settings?.let { settingsRepository.save(it.toSettings(settingsRepository.settings.first())) }
            settingsRepository.setAutoPostFrom(file.settings?.autoPostFrom ?: time.today().toEpochDay())
        } catch (e: Exception) {
            replaceDatabase(previous)
            previous.settings?.let { settingsRepository.save(it.toSettings(settingsRepository.settings.first())) }
            settingsRepository.setAutoPostFrom(previous.settings?.autoPostFrom)
            throw e
        }
    }

    private suspend fun replaceDatabase(file: BackupFile) = with(db.maintenanceDao()) {
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
        }
    }

    private suspend fun clearEverything() = with(db.maintenanceDao()) {
        clearAccounts(); clearSnapshots(); clearGroups(); clearItems(); clearAmounts()
        clearActuals(); clearLedger(); clearScenarios(); clearCheckIns(); clearInstallments()
        clearPostedKeys(); clearDeferrals()
    }

    suspend fun clearAll() {
        db.withTransaction { clearEverything() }
        settingsRepository.setAutoPostFrom(null)
    }
}
