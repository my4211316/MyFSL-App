package tw.myfsl.app.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FinanceSnapshot
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * 第三次複審（00f28f0）：
 * - 還原失敗後，排隊中的寫入一律拒絕（拿到鎖、動資料前再確認 READY）。
 * - 還原後，舊畫面帶著舊世代的刪除、修改、新增（含關聯 id）一律拒絕，不會動到 id 相同的另一筆資料；新世代的操作照常。
 * - 「已完成」標記無效（空白、截斷、內容錯誤、識別碼不符）時不當成完成、不清紀錄、不動資料。
 *
 * [FakeStore] 模擬資料層：資料庫與設定各記一份世代，整份替換時資料庫交易裡加一、設定再寫同一個值（同 FinanceRepository）。
 */
class WriteGateTest {

    @get:Rule val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "restore")

    private fun backup(vararg rows: Pair<Long, String>) = BackupFile(
        exportedAtMillis = 1,
        accounts = rows.map { (id, name) -> Account(id, name, AccountKind.BANK, balance = 1_000).toEntity() },
        settings = SettingsBackup(10_000, 24, 7, true, cardPostingDays = 5, autoPostFrom = 100),
    )

    /** 模擬的資料層：id → 名稱，外加資料庫與設定兩份世代。 */
    private class FakeStore(initial: BackupFile) : RestoreTarget {
        val rows = linkedMapOf<Long, String>().apply { initial.accounts.forEach { put(it.id, it.name) } }
        var dbGeneration = 0L
        var settingsGeneration = 0L
        var failSettings = false
        var failRollbackDatabase = false
        private var pending: Long? = null
        private var dbWrites = 0
        val restoreStarted = CompletableDeferred<Unit>()
        var holdDatabase: CompletableDeferred<Unit>? = null

        override suspend fun export() = BackupFile(
            exportedAtMillis = 1,
            accounts = rows.map { (id, name) -> Account(id, name, AccountKind.BANK, balance = 1_000).toEntity() },
            settings = SettingsBackup(10_000, 24, 7, true, cardPostingDays = 5, autoPostFrom = 100),
        )

        override suspend fun replaceDatabase(file: BackupFile) {
            dbWrites++
            restoreStarted.complete(Unit)
            holdDatabase?.await()
            if (dbWrites > 1 && failRollbackDatabase) throw IllegalStateException("放回失敗")
            rows.clear()
            file.accounts.forEach { rows[it.id] = it.name }
            dbGeneration += 1
            pending = dbGeneration
        }

        override suspend fun replaceSettings(file: BackupFile, autoPostFromFallback: Long?) {
            if (failSettings) { failSettings = false; throw IllegalStateException("設定寫入失敗") }
            pending?.let { settingsGeneration = it }
        }

        /** 同 FinanceRepository.currentGenerationLocked。 */
        fun current() = if (dbGeneration == settingsGeneration) dbGeneration else FinanceSnapshot.NO_GENERATION
    }

    private class Setup(val store: FakeStore, val coordinator: RestoreCoordinator, val gate: WriteGate, val notices: MutableList<String>)

    private fun setup(initial: BackupFile): Setup {
        val lock = Mutex()
        val store = FakeStore(initial)
        val coordinator = RestoreCoordinator(RestoreJournal(dir), store, lock) { 999L }
        val notices = Collections.synchronizedList(mutableListOf<String>())
        val gate = WriteGate(lock, { coordinator.state.value }, { store.current() }, { notices += it })
        return Setup(store, coordinator, gate, notices)
    }

    private suspend fun assertRejected(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("$message：應該被拒絕")
        } catch (_: WriteRejectedException) {
        }
    }

    // ---------- P1-1：還原失敗後，排隊的寫入一律拒絕 ----------

    @Test fun `開啟檢查還沒完成時的寫入被拒絕`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        assertEquals(RestoreState.CHECKING, s.coordinator.state.value)
        var wrote = false
        assertRejected("檢查中") { s.gate.run(null) { wrote = true } }
        assertTrue(!wrote)
        assertEquals(listOf(WriteGate.NOT_READY), s.notices.toList())
    }

    @Test fun `還原中排隊的寫入：還原失敗而且放回也失敗時，拿到鎖後被拒絕，一筆都沒寫`() = runBlocking {
        val s = setup(backup(1L to "舊銀行"))
        s.coordinator.recover()
        s.store.apply { failSettings = true; failRollbackDatabase = true; holdDatabase = CompletableDeferred() }
        val restoring = async(Dispatchers.Default) { runCatching { s.coordinator.restore(backup(1L to "新銀行")) } }
        s.store.restoreStarted.await()
        val generation = s.store.current()
        val wrote = Collections.synchronizedList(mutableListOf<String>())
        // 還原期間兩筆寫入排隊：一筆不帶世代（例如開始追蹤），一筆帶當時畫面的世代
        val queued = listOf(
            async(Dispatchers.Default) { runCatching { s.gate.run(null) { wrote += "不帶世代" } } },
            async(Dispatchers.Default) { runCatching { s.gate.run(generation) { wrote += "帶世代" } } },
        )
        delay(100)
        assertTrue("還原中不能寫", wrote.isEmpty())
        s.store.holdDatabase!!.complete(Unit)
        assertTrue(restoring.await().isFailure)
        assertEquals(RestoreState.RECOVERY_FAILED, s.coordinator.state.value)
        queued.forEach { assertTrue(it.await().exceptionOrNull() is WriteRejectedException) }
        assertTrue("還原失敗後排隊的寫入一筆都沒寫", wrote.isEmpty())
        assertNotNull(RestoreJournal(dir).pending())
    }

    @Test fun `還原中排隊的寫入：資料換好但「已完成」標記寫不進去時，同樣被拒絕`() = runBlocking {
        val s = setup(backup(1L to "舊銀行"))
        s.coordinator.recover()
        s.store.holdDatabase = CompletableDeferred()
        val restoring = async(Dispatchers.Default) { runCatching { s.coordinator.restore(backup(1L to "新銀行")) } }
        s.store.restoreStarted.await()
        // 紀錄寫好、資料庫正在換時，標記的位置被非空資料夾佔住
        File(dir, "restore-journal.done").mkdirs()
        File(dir, "restore-journal.done/blocker").writeText("x")
        var wrote = false
        val queued = async(Dispatchers.Default) { runCatching { s.gate.run(null) { wrote = true } } }
        delay(50)
        s.store.holdDatabase!!.complete(Unit)
        assertTrue(restoring.await().isFailure)
        assertEquals(RestoreState.RECOVERY_FAILED, s.coordinator.state.value)
        assertTrue(queued.await().exceptionOrNull() is WriteRejectedException)
        assertTrue(!wrote)
    }

    @Test fun `還原成功後，排隊的寫入若畫面世代已經過期也被拒絕；不帶世代的照常`() = runBlocking {
        val s = setup(backup(1L to "舊銀行"))
        s.coordinator.recover()
        s.store.holdDatabase = CompletableDeferred()
        val before = s.store.current()
        val restoring = launch(Dispatchers.Default) { s.coordinator.restore(backup(1L to "新銀行")) }
        s.store.restoreStarted.await()
        val stale = async(Dispatchers.Default) { runCatching { s.gate.run(before) { s.store.rows.remove(1L) } } }
        val plain = async(Dispatchers.Default) { runCatching { s.gate.run(null) { "ok" } } }
        delay(50)
        s.store.holdDatabase!!.complete(Unit)
        restoring.join()
        assertEquals(RestoreState.READY, s.coordinator.state.value)
        assertTrue(stale.await().exceptionOrNull() is WriteRejectedException)
        assertEquals("ok", plain.await().getOrThrow())
        assertEquals("新銀行", s.store.rows[1L])
    }

    // ---------- P1-2：同 id、不同內容的兩份備份 ----------

    @Test fun `還原後舊世代的刪除、修改、新增（含關聯 id）都被拒絕，新世代照常`() = runBlocking {
        val backupA = backup(1L to "A 的銀行", 2L to "A 的卡")
        val backupB = backup(1L to "B 的銀行", 2L to "B 的卡")
        val s = setup(backupA)
        s.coordinator.recover()
        s.coordinator.restore(backupA)
        val oldGeneration = s.store.current()
        assertEquals(mapOf(1L to "A 的銀行", 2L to "A 的卡"), s.store.rows)

        // 使用者在畫面 A 上開著編輯器，這時還原成 B（id 相同、內容不同）
        s.coordinator.restore(backupB)
        val newGeneration = s.store.current()
        assertTrue(newGeneration != oldGeneration)
        val afterRestore = s.store.rows.toMap()
        assertEquals(mapOf(1L to "B 的銀行", 2L to "B 的卡"), afterRestore)

        assertRejected("舊世代刪除") { s.gate.run(oldGeneration) { s.store.rows.remove(1L) } }
        assertRejected("舊世代修改") { s.gate.run(oldGeneration) { s.store.rows[2L] = "A 的卡（改名）" } }
        assertRejected("舊世代新增（關聯 id 1）") { s.gate.run(oldGeneration) { s.store.rows[3L] = "掛在 ${s.store.rows[1L]} 底下" } }
        assertRejected("還沒拿到世代的畫面") { s.gate.run(FinanceSnapshot.NO_GENERATION) { s.store.rows.clear() } }
        assertEquals("B 的資料完全沒被動到", afterRestore, s.store.rows)
        assertEquals(List(4) { WriteGate.STALE }, s.notices.toList())

        // 重新讀到新資料的畫面：同樣的操作都成功
        s.gate.run(newGeneration) { s.store.rows[2L] = "B 的卡（改名）" }
        s.gate.run(newGeneration) { s.store.rows[3L] = "掛在 ${s.store.rows[1L]} 底下" }
        s.gate.run(newGeneration) { s.store.rows.remove(1L) }
        assertEquals(mapOf(2L to "B 的卡（改名）", 3L to "掛在 B 的銀行 底下"), s.store.rows)
    }

    @Test fun `資料庫與設定的世代不一致時，帶世代和不帶世代的一般寫入都拒絕`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.coordinator.recover()
        s.store.dbGeneration = 5
        s.store.settingsGeneration = 4
        assertRejected("世代不一致（舊值）") { s.gate.run(4L) { s.store.rows.clear() } }
        assertRejected("世代不一致（新值）") { s.gate.run(5L) { s.store.rows.clear() } }
        assertRejected("世代不一致（不帶世代）") { s.gate.run(null) { s.store.rows.clear() } }
        assertEquals(1, s.store.rows.size)
        assertEquals(List(3) { WriteGate.INCONSISTENT }, s.notices.toList())
    }

    @Test fun `世代一致時不帶世代的寫入照常；不是 READY 時先以還原狀態拒絕`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        // 檢查中而且世代不一致：先回報還原狀態
        s.store.dbGeneration = 1
        assertRejected("檢查中") { s.gate.run(null) {} }
        assertRejected("檢查中的整份替換") { s.gate.replacingAll {} }
        assertEquals(List(2) { WriteGate.NOT_READY }, s.notices.toList())
        s.store.settingsGeneration = 1
        s.coordinator.recover()
        assertEquals("ok", s.gate.run(null) { "ok" })
        assertEquals("ok", s.gate.run(1L) { "ok" })
    }

    /** 同 FinanceRepository.clearAllLocked：資料庫交易裡清空並加一，之後寫設定的世代（可以模擬失敗）。 */
    private suspend fun clearAll(store: FakeStore, settingsFails: Boolean) {
        store.rows.clear()
        store.dbGeneration += 1
        if (settingsFails) throw java.io.IOException("設定寫入失敗")
        store.settingsGeneration = store.dbGeneration
    }

    @Test fun `清除資料時設定的世代沒寫進去：一般寫入全部拒絕，整份替換的維護流程可以完成對齊`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.coordinator.recover()
        val before = s.store.current()
        try {
            s.gate.replacingAll { clearAll(s.store, settingsFails = true) }
            fail()
        } catch (e: MaintenanceFailedException) {
            // 協程的例外還原可能再包一層，沿著原因找原本的 I/O 錯誤
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException })
        }
        // READY，但兩份世代不同：進入維護失敗，一般寫入（含不帶世代的開始追蹤、標記已備份、完成導覽）都不做
        assertEquals(RestoreState.READY, s.coordinator.state.value)
        assertTrue(s.gate.maintenanceFailed.value)
        assertEquals(FinanceSnapshot.NO_GENERATION, s.store.current())
        assertRejected("不帶世代") { s.gate.run(null) { s.store.rows[9L] = "不該寫入" } }
        assertRejected("舊世代") { s.gate.run(before) { s.store.rows[9L] = "不該寫入" } }
        assertTrue(s.store.rows.isEmpty())

        // 修復一：再清除一次（整份替換），結束時兩份世代一致
        s.gate.replacingAll { clearAll(s.store, settingsFails = false) }
        val repaired = s.store.current()
        assertTrue(repaired != FinanceSnapshot.NO_GENERATION && repaired != before)
        assertTrue(!s.gate.maintenanceFailed.value)
        s.gate.run(null) { s.store.rows[1L] = "新帳戶" }
        s.gate.run(repaired) { s.store.rows[2L] = "掛在新帳戶底下" }
        assertRejected("修復後舊世代仍然拒絕") { s.gate.run(before) { s.store.rows.remove(1L) } }
        assertEquals(mapOf(1L to "新帳戶", 2L to "掛在新帳戶底下"), s.store.rows)
    }

    /** 同 FinanceRepository.repairDataGeneration：設定對齊資料庫（可以模擬失敗）。 */
    private fun align(store: FakeStore, fails: Boolean = false) {
        if (fails) throw java.io.IOException("設定寫入失敗")
        store.settingsGeneration = store.dbGeneration
    }

    @Test fun `維護失敗：I-O 錯誤轉成維護失敗狀態而不是閃退；重試失敗維持阻擋，對齊成功才解除`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.coordinator.recover()
        val before = s.store.current()
        val error = runCatching { s.gate.replacingAll { clearAll(s.store, settingsFails = true) } }.exceptionOrNull()
        assertTrue("維護的 I/O 錯誤包成可預期的例外（畫面接住後顯示重試，不交給當機處理）", error is MaintenanceFailedException)
        assertTrue(s.gate.maintenanceFailed.value)
        assertTrue(WriteGate.MAINTENANCE_FAILED in s.notices)

        // 按「重試」但設定還是寫不進去：維持阻擋
        assertTrue(runCatching { s.gate.replacingAll { align(s.store, fails = true) } }.exceptionOrNull() is MaintenanceFailedException)
        assertTrue(s.gate.maintenanceFailed.value)
        assertRejected("重試失敗後") { s.gate.run(null) {} }

        // 兩份世代「看起來」一致（例如別的路徑寫進去了）也不解除：只有整份替換成功才解除
        s.store.settingsGeneration = s.store.dbGeneration
        assertRejected("沒有經過重試") { s.gate.run(null) {} }

        // 再按一次「重試」成功：解除，舊世代仍然拒絕
        s.store.settingsGeneration = before
        s.gate.replacingAll { align(s.store) }
        assertTrue(!s.gate.maintenanceFailed.value)
        assertEquals("ok", s.gate.run(null) { "ok" })
        assertRejected("清除前畫面的舊世代") { s.gate.run(before) {} }
    }

    @Test fun `維護在動資料前就失敗、世代仍一致：回報失敗但不進入阻擋`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.coordinator.recover()
        val error = runCatching { s.gate.replacingAll<Unit> { throw java.io.IOException("資料庫交易失敗") } }.exceptionOrNull()
        assertTrue(error is MaintenanceFailedException)
        assertTrue(!s.gate.maintenanceFailed.value)
        assertEquals(mapOf(1L to "銀行"), s.store.rows)
        assertEquals("ok", s.gate.run(0L) { "ok" })
    }

    @Test fun `維護開始後發起的畫面被關掉（取消）：照樣做完，不會留下半套`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.coordinator.recover()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            s.gate.replacingAll {
                started.complete(Unit)
                delay(100)
                clearAll(s.store, settingsFails = false)
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(s.store.rows.isEmpty())
        assertEquals(1L, s.store.current())
        assertTrue(!s.gate.maintenanceFailed.value)
    }

    @Test fun `開 App 時的世代對齊：設定對齊資料庫之後才開放一般寫入`() = runBlocking {
        val s = setup(backup(1L to "銀行"))
        s.store.dbGeneration = 3
        s.store.settingsGeneration = 2
        s.coordinator.recover()
        assertRejected("對齊前") { s.gate.run(null) {} }
        // 同 FinanceRepository.recoverInterruptedRestore：READY 之後以維護入口把設定對齊資料庫
        s.gate.replacingAll { if (s.store.settingsGeneration != s.store.dbGeneration) s.store.settingsGeneration = s.store.dbGeneration }
        assertEquals(3L, s.store.current())
        assertEquals("ok", s.gate.run(null) { "ok" })
        assertRejected("對齊前畫面的舊世代") { s.gate.run(2L) {} }
    }

    // ---------- P1-3：無效的「已完成」標記 ----------

    private fun invalidMarkerCase(content: (journalId: String) -> String) = runBlocking {
        val old = backup(1L to "舊銀行")
        val journal = RestoreJournal(dir)
        journal.begin(BackupCodec.encode(old))
        val id = File(dir, "restore-journal.json").readLines().first().split(' ')[2]
        File(dir, "restore-journal.done").writeText(content(id))
        assertEquals(RestoreJournal.Commit.INVALID, journal.commitState())

        val s = setup(backup(1L to "還原後新記的帳"))
        repeat(2) {
            assertEquals(RestoreCoordinator.Recovery.FAILED, s.coordinator.recover())
            assertEquals(RestoreState.RECOVERY_FAILED, s.coordinator.state.value)
            assertNotNull("紀錄保留", RestoreJournal(dir).pending())
            assertTrue("標記保留", File(dir, "restore-journal.done").exists())
            assertEquals("資料沒被放回", "還原後新記的帳", s.store.rows[1L])
            assertEquals(0L, s.store.dbGeneration)
        }
        // 不開放寫入
        try {
            s.gate.run(null) {}
            fail()
        } catch (_: WriteRejectedException) {
        }
    }

    @Test fun `標記空白：不當成完成`() = invalidMarkerCase { "" }

    @Test fun `標記截斷：不當成完成`() = invalidMarkerCase { "MYFSL-RESTORE-DONE 1" }

    @Test fun `標記截斷在識別碼中間：不當成完成`() = invalidMarkerCase { id -> "MYFSL-RESTORE-DONE 1 ${id.take(20)}" }

    @Test fun `標記內容錯誤：不當成完成`() = invalidMarkerCase { "committed" }

    @Test fun `標記版本錯誤：不當成完成`() = invalidMarkerCase { id -> "MYFSL-RESTORE-DONE 2 $id" }

    @Test fun `標記識別碼和紀錄不符：不當成完成`() = invalidMarkerCase { "MYFSL-RESTORE-DONE 1 ${UUID.randomUUID()}" }

    @Test fun `標記是資料夾：不當成完成`() = runBlocking {
        RestoreJournal(dir).begin(BackupCodec.encode(backup(1L to "舊銀行")))
        File(dir, "restore-journal.done").mkdirs()
        val s = setup(backup(1L to "還原後新記的帳"))
        assertEquals(RestoreCoordinator.Recovery.FAILED, s.coordinator.recover())
        assertNotNull(RestoreJournal(dir).pending())
        assertEquals("還原後新記的帳", s.store.rows[1L])
    }

    @Test fun `有效標記、清理失敗：不放回，照常開放`() = runBlocking {
        RestoreJournal(dir).apply { begin(BackupCodec.encode(backup(1L to "舊銀行"))); markCommitted() }
        val lock = Mutex()
        val store = FakeStore(backup(1L to "還原後新記的帳"))
        val coordinator = RestoreCoordinator(RestoreJournal(dir, delete = { false }), store, lock) { 999L }
        assertEquals(RestoreCoordinator.Recovery.NONE, coordinator.recover())
        assertEquals(RestoreState.READY, coordinator.state.value)
        assertEquals("還原後新記的帳", store.rows[1L])
        assertEquals(0L, store.dbGeneration)
        val gate = WriteGate(lock, { coordinator.state.value }, { store.current() })
        assertEquals("ok", gate.run(store.current()) { "ok" })
    }
}
