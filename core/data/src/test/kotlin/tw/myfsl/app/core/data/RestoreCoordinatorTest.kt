package tw.myfsl.app.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import java.io.File

/**
 * 還原流程（R-DATA-06，F11）：用假的資料庫與設定模擬「寫到一半被關掉」「放回失敗」「重新開啟」。
 * 「被關掉」用不是 Exception 的 [ProcessKilled] 模擬：流程裡的 catch 抓不到，就跟程式被系統殺掉一樣，後面的程式都不會執行。
 */
class RestoreCoordinatorTest {

    @get:Rule val folder = TemporaryFolder()

    private class ProcessKilled : Throwable()

    private fun backup(name: String, safety: Long, autoPostFrom: Long?) = BackupFile(
        exportedAtMillis = 1,
        accounts = listOf(Account(1, name, AccountKind.BANK, balance = 1_000).toEntity()),
        settings = SettingsBackup(safety, 24, 7, true, cardPostingDays = 5, autoPostFrom = autoPostFrom),
    )

    private val old = backup("舊銀行", 10_000, 100)
    private val new = backup("新銀行", 50_000, 200)

    /** 假的資料庫與設定；可以指定在第幾次寫入時出錯或「被關掉」。 */
    private inner class FakeTarget(var db: BackupFile, var settings: SettingsBackup?, var autoPostFrom: Long?) : RestoreTarget {
        val calls = mutableListOf<String>()
        var failDatabaseTimes = 0
        var failSettingsTimes = 0
        var killInDatabase = false
        var killInSettings = false
        var journalAtFirstDbWrite: String? = null
        private val journal = RestoreJournal(dir)

        override suspend fun export() = db.copy(settings = settings?.copy(autoPostFrom = autoPostFrom))

        override suspend fun replaceDatabase(file: BackupFile) {
            if (calls.none { it.startsWith("db") }) journalAtFirstDbWrite = journal.pending()
            calls += "db:${file.accounts.single().name}"
            if (killInDatabase) { killInDatabase = false; throw ProcessKilled() }
            if (failDatabaseTimes > 0) { failDatabaseTimes--; throw IllegalStateException("資料庫寫入失敗") }
            db = file.copy(settings = null)
        }

        override suspend fun replaceSettings(file: BackupFile, autoPostFromFallback: Long?) {
            calls += "settings:${file.settings?.safetyLevel}"
            if (killInSettings) { killInSettings = false; throw ProcessKilled() }
            if (failSettingsTimes > 0) { failSettingsTimes--; throw IllegalStateException("設定寫入失敗") }
            settings = file.settings?.copy(autoPostFrom = null)
            autoPostFrom = file.settings?.autoPostFrom ?: autoPostFromFallback
        }

        val bankName get() = db.accounts.single().name
    }

    private val dir get() = File(folder.root, "restore")

    private fun target() = FakeTarget(old.copy(settings = null), old.settings!!.copy(autoPostFrom = null), 100)

    /** 每次建立新的實例＝重新開 App（只共用磁碟上的紀錄）。 */
    private fun coordinator(target: RestoreTarget) = RestoreCoordinator(RestoreJournal(dir), target) { 999L }

    @Test fun `還原成功：改資料庫前紀錄已經寫好，資料庫與設定都完成才清掉紀錄`() = runBlocking {
        val target = target()
        val c = coordinator(target)
        assertEquals(RestoreCoordinator.Recovery.NONE, c.recover())
        c.restore(new)
        val journal = checkNotNull(target.journalAtFirstDbWrite) { "改資料庫前紀錄應該已經寫好" }
        val saved = (BackupCodec.decode(journal) as BackupReadResult.Ok).file
        assertEquals("紀錄裡是還原前的資料", "舊銀行", saved.accounts.single().name)
        assertEquals(10_000L, saved.settings!!.safetyLevel)
        assertEquals(listOf("db:新銀行", "settings:50000"), target.calls)
        assertEquals("新銀行", target.bankName)
        assertEquals(200L, target.autoPostFrom)
        assertNull("都完成才清掉", RestoreJournal(dir).pending())
        assertEquals(RestoreState.READY, c.state.value)
    }

    @Test fun `設定寫入失敗：馬上放回資料庫與設定，清掉紀錄，錯誤照樣丟出`() = runBlocking {
        val target = target().apply { failSettingsTimes = 1 }
        val c = coordinator(target)
        c.recover()
        try {
            c.restore(new)
            fail("應該丟出錯誤")
        } catch (e: IllegalStateException) {
            assertEquals("設定寫入失敗", e.message)
        }
        assertEquals(listOf("db:新銀行", "settings:50000", "db:舊銀行", "settings:10000"), target.calls)
        assertEquals("舊銀行", target.bankName)
        assertEquals(10_000L, target.settings!!.safetyLevel)
        assertEquals(100L, target.autoPostFrom)
        assertNull(RestoreJournal(dir).pending())
        assertEquals(RestoreState.READY, c.state.value)
    }

    @Test fun `資料庫換完、設定還沒寫就被關掉：重新開啟時先放回，放回前不開放操作`() = runBlocking {
        val target = target().apply { killInSettings = true }
        val first = coordinator(target)
        first.recover()
        try {
            first.restore(new)
            fail("應該被關掉")
        } catch (_: ProcessKilled) {
        }
        assertEquals("資料庫已經是新的、設定還是舊的", "新銀行", target.bankName)
        assertEquals(10_000L, target.settings!!.safetyLevel)
        assertNotNull("紀錄留在磁碟上", RestoreJournal(dir).pending())

        // 重新開 App：新的實例，放回完成前狀態是 CHECKING
        val second = coordinator(target)
        assertEquals(RestoreState.CHECKING, second.state.value)
        assertEquals(RestoreCoordinator.Recovery.RECOVERED, second.recover())
        assertEquals("舊銀行", target.bankName)
        assertEquals(10_000L, target.settings!!.safetyLevel)
        assertEquals(100L, target.autoPostFrom)
        assertNull(RestoreJournal(dir).pending())
        assertEquals(RestoreState.READY, second.state.value)
    }

    @Test fun `放回時又失敗或又被關掉：紀錄保留，狀態不開放操作，重試可以放回`() = runBlocking {
        val target = target().apply { killInDatabase = true }
        val first = coordinator(target)
        first.recover()
        try {
            first.restore(new)
        } catch (_: ProcessKilled) {
        }
        assertNotNull(RestoreJournal(dir).pending())

        // 第二次開：放回時資料庫寫入失敗
        target.failDatabaseTimes = 1
        val second = coordinator(target)
        assertEquals(RestoreCoordinator.Recovery.FAILED, second.recover())
        assertEquals(RestoreState.RECOVERY_FAILED, second.state.value)
        assertNotNull("放回失敗，紀錄保留", RestoreJournal(dir).pending())

        // 第三次開：放回途中又被關掉
        target.killInSettings = true
        val third = coordinator(target)
        try {
            third.recover()
        } catch (_: ProcessKilled) {
        }
        assertNotNull("又中斷，紀錄仍保留", RestoreJournal(dir).pending())

        // 按「重試」（或再開一次）：放回成功
        val fourth = coordinator(target)
        assertEquals(RestoreCoordinator.Recovery.RECOVERED, fourth.recover())
        assertEquals("舊銀行", target.bankName)
        assertEquals(10_000L, target.settings!!.safetyLevel)
        assertNull(RestoreJournal(dir).pending())
        assertEquals(RestoreState.READY, fourth.state.value)
    }

    @Test fun `還原失敗而且放回也失敗：錯誤丟出、紀錄保留、不開放操作；重試放回`() = runBlocking {
        val target = target().apply { failSettingsTimes = 1 }
        // 設定寫入失敗 → 放回時資料庫又失敗
        val failing = object : RestoreTarget by target {
            var dbCalls = 0
            override suspend fun replaceDatabase(file: BackupFile) {
                dbCalls++
                if (dbCalls == 2) throw IllegalStateException("放回失敗")
                target.replaceDatabase(file)
            }
        }
        val c2 = coordinator(failing)
        c2.recover()
        try {
            c2.restore(new)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("設定寫入失敗", e.message)
        }
        assertEquals(RestoreState.RECOVERY_FAILED, c2.state.value)
        assertNotNull(RestoreJournal(dir).pending())
        assertEquals(RestoreCoordinator.Recovery.RECOVERED, c2.recover())
        assertEquals("舊銀行", target.bankName)
        assertEquals(RestoreState.READY, c2.state.value)
    }

    @Test fun `紀錄寫不進去：資料完全不動`() = runBlocking {
        folder.newFile("restore") // 同名檔案讓資料夾建不起來
        val target = target()
        val c = coordinator(target)
        c.recover()
        try {
            c.restore(new)
            fail()
        } catch (_: java.io.IOException) {
        }
        assertTrue("資料庫與設定都沒被呼叫", target.calls.isEmpty())
        assertEquals("舊銀行", target.bankName)
        assertEquals(RestoreState.READY, c.state.value)
    }

    @Test fun `上次的還原還沒放回時，不能開始新的還原`() = runBlocking {
        RestoreJournal(dir).begin(BackupCodec.encode(old))
        val target = target()
        val c = coordinator(target)
        try {
            c.restore(new)
            fail()
        } catch (_: IllegalStateException) {
        }
        assertTrue(target.calls.isEmpty())
    }
}
