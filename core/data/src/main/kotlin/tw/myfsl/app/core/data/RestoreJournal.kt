package tw.myfsl.app.core.data

import java.io.File
import java.io.FileOutputStream

/**
 * 從備份還原的復原紀錄（R-DATA-06，F11）。
 *
 * 還原前把目前的資料（完整備份 JSON）寫進 [dir]；資料庫與設定都寫完才刪。
 * 寫法：先寫暫存檔並同步到磁碟（fsync），再改名成正式檔；改名是原子動作，
 * 所以正式檔要不是完整的、要不就不存在。寫完讀回比對，確定可以讀才開始改資料。
 */
class RestoreJournal(private val dir: File) {

    private val file get() = File(dir, FILE)
    private val temp get() = File(dir, "$FILE.tmp")

    /** 開始還原：記下還原前的資料。回傳前已經同步到磁碟並讀回確認；失敗時丟例外，資料都還沒動。 */
    fun begin(previous: String) {
        dir.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(previous.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "無法寫入還原紀錄" }
        }
        check(pending() == previous) { "還原紀錄讀回來不一致" }
    }

    /** 上次還原沒有完成時，還原前的資料；沒有則為 null。寫到一半的暫存檔不算。 */
    fun pending(): String? = file.takeIf { it.exists() }?.readText(Charsets.UTF_8)

    /** 還原（或放回）完成，資料庫與設定都已經是一致的狀態。 */
    fun finish() {
        file.delete()
        temp.delete()
    }

    private companion object {
        const val FILE = "restore-journal.json"
    }
}
