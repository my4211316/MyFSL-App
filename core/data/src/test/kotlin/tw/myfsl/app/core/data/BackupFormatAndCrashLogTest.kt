package tw.myfsl.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatAndCrashLogTest {

    @Test fun `當機紀錄：含版本、執行緒與錯誤，最多 60 行堆疊`() {
        val error = IllegalStateException("測試錯誤")
        val text = CrashLog.format("1.0.0", "main", error, 1_789_000_000_000)
        assertTrue(text.startsWith("MyFSL 1.0.0"))
        assertTrue(text.contains("執行緒：main"))
        assertTrue(text.contains("IllegalStateException: 測試錯誤"))
        assertTrue(text.lines().size <= 66)
    }

    @Test fun `備份格式欄位名稱固定：用字串寫死的舊備份也讀得回來`() {
        // 正式版會混淆程式碼；這份 JSON 模擬「1.0.0 匯出的備份」，欄位名稱改了就會讀不回來。
        val json = """
            {"app":"MyFSL","formatVersion":1,"exportedAtMillis":1789000000000,
             "accounts":[{"id":1,"name":"錢包","kind":"CASH","creditLimit":null,"paymentDueDay":null,"loanRatePercent":null,
               "loanRemainingMonths":null,"loanMethod":null,"loanPayAccountId":null,"loanPayDay":null,"loanOriginalPrincipal":null,
               "archived":false,"sortOrder":1}],
             "snapshots":[{"id":1,"accountId":1,"epochDay":20710,"balance":5000,"recordedAtMillis":1789000000000}],
             "ledger":[{"id":3,"epochDay":20710,"type":"EXPENSE","amount":120,"itemId":null,"method":"CASH","accountId":1,
               "toAccountId":null,"note":"早餐","source":"MANUAL","installmentId":null,"createdAtMillis":1789000000001}],
             "settings":{"safetyLevel":30000,"horizonMonths":24,"checkInDay":7,"pickCard":true,"cardPostingDays":5}}
        """.trimIndent()
        val result = BackupCodec.decode(json) as BackupReadResult.Ok
        assertEquals("錢包", result.file.accounts.single().name)
        assertEquals(120L, result.file.ledger.single().amount)
        assertEquals(BackupFile.FORMAT_VERSION, result.file.formatVersion)
    }
}
