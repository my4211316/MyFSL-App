package tw.myfsl.app.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class ReviewRecoveryFailureTest {
    @get:Rule val folder=TemporaryFolder()
    private fun target()=object:RestoreTarget {
        override suspend fun export()=BackupFile(exportedAtMillis=1)
        override suspend fun replaceDatabase(file:BackupFile) { error("must not write") }
        override suspend fun replaceSettings(file:BackupFile,autoPostFromFallback:Long?) { error("must not write") }
    }
    @Test fun unreadableJournalMustExposeRecoveryFailureInsteadOfEndlessChecking()=runBlocking {
        val dir=folder.newFolder("restore")
        // Deterministic file-read error, as with an unreadable journal path.
        File(dir,"restore-journal.json").mkdir()
        val c=RestoreCoordinator(RestoreJournal(dir),target()){999L}
        try { c.recover() } catch(_:Exception) { }
        assertEquals("Startup must offer retry on journal read failure",RestoreState.RECOVERY_FAILED,c.state.value)
    }
    @Test fun failedJournalDeletionMustNotBeReportedAsSuccessfulCleanup() {
        val dir=folder.newFolder("cleanup")
        val path=File(dir,"restore-journal.json")
        path.mkdir()
        File(path,"blocker").writeText("prevents deletion")
        var signaled=false
        try { RestoreJournal(dir).finish() } catch(_:Exception) { signaled=true }
        assertTrue("finish must signal a failed delete so coordinator cannot return READY",signaled)
    }
}
