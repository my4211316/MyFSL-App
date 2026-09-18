package tw.myfsl.app.core.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewInvalidMarkerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun retryWithoutRemovingInvalidMarkerMustStayBlockedAndKeepJournal() = runBlocking {
        val dir = folder.newFolder("restore")
        val old = BackupFile(exportedAtMillis = 1)
        val replacement = BackupFile(exportedAtMillis = 2)
        val journal = RestoreJournal(dir)
        val target = object : RestoreTarget {
            override suspend fun export() = old
            override suspend fun replaceDatabase(file: BackupFile) {}
            override suspend fun replaceSettings(file: BackupFile, autoPostFromFallback: Long?) {
                if (file.exportedAtMillis == 2L) {
                    val marker = File(dir, "restore-journal.done")
                    check(marker.mkdirs())
                    File(marker, "blocker").writeText("x")
                }
            }
        }
        val coordinator = RestoreCoordinator(journal, target) { 999L }
        assertEquals(RestoreCoordinator.Recovery.NONE, coordinator.recover())
        try {
            coordinator.restore(replacement)
            fail("Marker write must fail")
        } catch (_: IOException) {}
        assertEquals(RestoreState.RECOVERY_FAILED, coordinator.state.value)
        assertNotNull(journal.pending())

        // User presses retry without any external filesystem repair.
        val result = coordinator.recover()
        val pending = journal.pending()
        assertTrue(
            "Expected blocked recovery with journal retained; actual result=$result, state=${coordinator.state.value}, journalPresent=${pending != null}",
            result == RestoreCoordinator.Recovery.FAILED &&
                coordinator.state.value == RestoreState.RECOVERY_FAILED && pending != null,
        )
    }
}
