package tw.myfsl.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 還原中斷後可以放回（F11）：紀錄在完成前一直留在磁碟上，另一個實例（重新開 App）也讀得到。 */
class RestoreJournalTest {

    @get:Rule val folder = TemporaryFolder()

    @Test fun `完成前紀錄留在磁碟上，重新開啟讀得到；完成後清掉`() {
        val dir = File(folder.root, "restore")
        val journal = RestoreJournal(dir)
        assertNull(journal.pending())
        journal.begin("""{"previous":1}""")
        // 模擬程式被關掉：用新的實例讀
        assertEquals("""{"previous":1}""", RestoreJournal(dir).pending())
        RestoreJournal(dir).finish()
        assertNull(journal.pending())
        assertEquals("沒有殘留的暫存檔", 0, dir.listFiles()!!.size)
    }

    @Test fun `寫到一半的暫存檔不算紀錄`() {
        val dir = File(folder.root, "restore").apply { mkdirs() }
        File(dir, "restore-journal.json.tmp").writeText("{")
        assertNull(RestoreJournal(dir).pending())
    }
}
