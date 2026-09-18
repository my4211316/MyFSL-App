package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.sample.SampleHousehold

class ReleaseReadinessTest {

    private val sample = SampleHousehold.snapshot()

    @Test fun `備份提醒：只有示意資料不提醒`() {
        assertNull(PeriodOverviewCalculator.backupReminder(sample))
    }

    @Test fun `備份提醒：有自己記的資料、從沒備份過就提醒`() {
        val own = sample.copy(ledger = sample.ledger + sample.ledger.first().copy(id = 99, createdAt = 1_789_000_000_000))
        assertTrue(PeriodOverviewCalculator.backupReminder(own)!!.startsWith("還沒備份過"))
    }

    @Test fun `備份提醒：30 天內不提醒，超過就提醒`() {
        val own = sample.copy(accounts = sample.accounts.map { it.copy(balanceRecordedAt = 1_789_000_000_000) })
        val today = sample.today.toEpochDay()
        assertNull(PeriodOverviewCalculator.backupReminder(own.copy(settings = own.settings.copy(lastBackupEpochDay = today - 29))))
        assertEquals(
            "上次備份是 30 天前，建議再匯出一次",
            PeriodOverviewCalculator.backupReminder(own.copy(settings = own.settings.copy(lastBackupEpochDay = today - 30))),
        )
    }
}
