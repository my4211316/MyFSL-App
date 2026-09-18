package tw.myfsl.app.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 從備份還原的復原紀錄（R-DATA-06，F11）。
 *
 * - [begin]：還原前把目前的資料（完整備份 JSON）寫進紀錄檔。先寫暫存檔並同步到磁碟（fsync）再改名，
 *   改名是原子動作，所以紀錄檔要不是完整的、要不就不存在；寫完讀回比對。
 * - [markCommitted]：資料庫與設定都已經是一致的狀態（還原成功，或已經放回）時，另外寫一個「已完成」標記檔。
 *   有這個標記時，留下來的紀錄檔只是待清理，**不能**再拿來放回（否則會蓋掉還原後新記的帳）。
 * - [finish]：清掉紀錄與標記；確認檔案真的不在了，刪不掉就丟 [IOException]（不當成已清掉）。
 *
 * 讀寫失敗一律丟例外，由 [RestoreCoordinator] 轉成「放回失敗、可重試」的狀態。
 */
class RestoreJournal(private val dir: File) {

    private val file get() = File(dir, FILE)
    private val temp get() = File(dir, "$FILE.tmp")
    private val committed get() = File(dir, COMMITTED)

    /** 開始還原：記下還原前的資料。回傳前已經同步到磁碟並讀回確認；失敗時丟例外，資料都還沒動。 */
    fun begin(previous: String) {
        dir.mkdirs()
        // 上次留下的「已完成」標記一定要先清掉，否則這次沒完成時會被誤判成已完成。
        deleteOrThrow(committed)
        writeDurably(previous, temp)
        if (!temp.renameTo(file)) {
            deleteOrThrow(file)
            if (!temp.renameTo(file)) throw IOException("無法寫入還原紀錄")
        }
        if (pending() != previous) throw IOException("還原紀錄讀回來不一致")
    }

    /** 上次還原沒有完成時，還原前的資料；沒有則為 null。寫到一半的暫存檔不算。讀不到丟 [IOException]。 */
    fun pending(): String? = file.takeIf { it.exists() }?.let {
        if (!it.isFile) throw IOException("還原紀錄不是檔案：${it.path}")
        it.readText(Charsets.UTF_8)
    }

    /** 資料庫與設定都已經一致：之後只需要清理，不能再放回。 */
    fun isCommitted(): Boolean = committed.exists()

    /** 寫入「已完成」標記（同步到磁碟並確認存在）；寫不進去丟 [IOException]。 */
    fun markCommitted() {
        dir.mkdirs()
        writeDurably("committed", committed)
        if (!committed.isFile) throw IOException("無法寫入還原完成標記")
    }

    /** 清掉紀錄與標記（先清紀錄、最後清標記）；任何一個刪不掉都丟 [IOException]。 */
    fun finish() {
        deleteOrThrow(file)
        deleteOrThrow(temp)
        deleteOrThrow(committed)
    }

    private fun writeDurably(text: String, target: File) {
        FileOutputStream(target).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    private fun deleteOrThrow(f: File) {
        if (f.exists() && !f.delete() && f.exists()) throw IOException("無法刪除還原紀錄：${f.path}")
    }

    private companion object {
        const val FILE = "restore-journal.json"
        const val COMMITTED = "restore-journal.done"
    }
}
