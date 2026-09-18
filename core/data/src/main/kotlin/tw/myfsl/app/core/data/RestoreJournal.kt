package tw.myfsl.app.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * 從備份還原的復原紀錄（R-DATA-06，F11）。
 *
 * - 紀錄檔：第一行 `MYFSL-RESTORE 1 <操作識別碼>`，之後是還原前的完整資料（備份 JSON）。
 * - 「已完成」標記：內容 `MYFSL-RESTORE-DONE 1 <同一個操作識別碼>`。只有「是一般檔案、格式正確、識別碼和紀錄相同」才算有效。
 *   資料夾、空白、截斷、內容錯誤、識別碼不符、讀不到，一律視為**無效**：不能當成完成、不能清掉紀錄，由流程停在可重試的狀態。
 * - 寫入一律先寫暫存檔、同步到磁碟、改名，再讀回確認；改名是原子動作，所以不會留下寫一半的正式檔。
 * - 刪除一律確認檔案真的不在了，刪不掉丟 [IOException]。
 *
 * [delete] 只給測試用來模擬刪不掉的情況。
 */
class RestoreJournal(
    private val dir: File,
    private val delete: (File) -> Boolean = { it.delete() },
) {
    /** 「已完成」標記的狀態。 */
    enum class Commit { NONE, VALID, INVALID }

    private val file get() = File(dir, FILE)
    private val temp get() = File(dir, "$FILE.tmp")
    private val committed get() = File(dir, COMMITTED)
    private val committedTemp get() = File(dir, "$COMMITTED.tmp")

    /** 開始還原：記下還原前的資料。回傳前已經同步到磁碟並讀回確認；失敗時丟例外，資料都還沒動。 */
    fun begin(previous: String) {
        dir.mkdirs()
        // 上次留下的標記一定要先清掉，否則這次沒完成時會被誤判成已完成。
        deleteOrThrow(committed)
        deleteOrThrow(committedTemp)
        val text = "$HEADER ${UUID.randomUUID()}\n$previous"
        publish(text, temp, file)
        if (pending() != previous) throw IOException("還原紀錄讀回來不一致")
    }

    /**
     * 上次還原沒有完成時，還原前的資料；沒有紀錄時為 null。
     * 紀錄存在但讀不到或格式不對時丟 [IOException]（不能當成沒有紀錄）。
     */
    fun pending(): String? = read()?.second

    /** 「已完成」標記是否有效（見類別說明）。讀取過程的錯誤一律回傳 [Commit.INVALID]。 */
    fun commitState(): Commit {
        val marker = committed
        if (!marker.exists()) return Commit.NONE
        return try {
            if (!marker.isFile) return Commit.INVALID
            val parts = marker.readText(Charsets.UTF_8).split(' ')
            if (parts.size != 3 || parts[0] != DONE || parts[1] != VERSION || !isOperationId(parts[2])) return Commit.INVALID
            val journalId = if (file.exists()) read()?.first else null
            // 紀錄還在時，標記必須屬於這一次的紀錄；紀錄已經清掉時，只剩標記本身要清理。
            if (journalId != null && journalId != parts[2]) Commit.INVALID else Commit.VALID
        } catch (e: IOException) {
            Commit.INVALID
        }
    }

    /** 資料庫與設定都已經一致時呼叫：寫入屬於這份紀錄的「已完成」標記；寫不進去或讀回不對丟 [IOException]。 */
    fun markCommitted() {
        val id = read()?.first ?: throw IOException("沒有還原紀錄，無法標記完成")
        publish("$DONE $VERSION $id", committedTemp, committed)
        if (commitState() != Commit.VALID) throw IOException("還原完成標記讀回來不正確")
    }

    /** 清掉紀錄與標記（先清紀錄、最後清標記）；任何一個刪不掉都丟 [IOException]。 */
    fun finish() {
        deleteOrThrow(file)
        deleteOrThrow(temp)
        deleteOrThrow(committedTemp)
        deleteOrThrow(committed)
    }

    /** (操作識別碼, 還原前的資料)；沒有紀錄為 null。 */
    private fun read(): Pair<String, String>? {
        val f = file
        if (!f.exists()) return null
        if (!f.isFile) throw IOException("還原紀錄不是檔案：${f.path}")
        val text = f.readText(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        val header = if (newline >= 0) text.substring(0, newline).split(' ') else emptyList()
        if (header.size != 3 || "${header[0]} ${header[1]}" != HEADER || !isOperationId(header[2])) {
            throw IOException("還原紀錄格式不對")
        }
        return header[2] to text.substring(newline + 1)
    }

    private fun isOperationId(value: String) = runCatching { UUID.fromString(value) }.isSuccess

    private fun publish(text: String, tempFile: File, target: File) {
        dir.mkdirs()
        FileOutputStream(tempFile).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tempFile.renameTo(target)) {
            if (target.exists() && target.isFile) deleteOrThrow(target)
            if (!tempFile.renameTo(target)) throw IOException("無法寫入：${target.path}")
        }
    }

    private fun deleteOrThrow(f: File) {
        if (f.exists() && !delete(f) && f.exists()) throw IOException("無法刪除還原紀錄：${f.path}")
    }

    private companion object {
        const val FILE = "restore-journal.json"
        const val COMMITTED = "restore-journal.done"
        const val VERSION = "1"
        const val HEADER = "MYFSL-RESTORE $VERSION"
        const val DONE = "MYFSL-RESTORE-DONE"
    }
}
