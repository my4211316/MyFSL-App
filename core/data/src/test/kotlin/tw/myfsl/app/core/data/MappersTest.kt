package tw.myfsl.app.core.data

import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.data.db.toModel
import tw.myfsl.app.core.data.db.toPaymentMethod
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MappersTest {

    @Test fun `帳戶（含貸款條件與原始金額）來回轉換不失真`() {
        SampleHousehold.accounts.forEach { account ->
            // balance 與 balanceAsOf 由 repository 另外計算，不存在 entity 中
            assertEquals(account.copy(balance = 0, balanceAsOf = null), account.toEntity().toModel())
        }
    }

    @Test fun `項目、實際數字、記帳來回轉換`() {
        SampleHousehold.items.forEach { assertEquals(it, it.toEntity().toModel()) }
        val actual = ItemActual(1, 2026, 9, ActualStatus.POSTPONED, LocalDate.of(2026, 9, 14))
        assertEquals(actual, actual.toEntity().toModel())
        val entry = LedgerEntry(
            7, LocalDate.of(2026, 9, 13), FlowType.EXPENSE, 800, 503, PaymentMethod.CREDIT_CARD,
            null, null, "加油", EntrySource.MISSED, createdAt = 1_757_830_123_456,
        )
        assertEquals(entry, entry.toEntity().toModel())
        assertEquals(1_757_830_123_456, entry.toEntity().createdAtMillis)
        assertEquals("MISSED", entry.toEntity().source)
        assertEquals(EntrySource.MANUAL, entry.toEntity().copy(source = "MANUAL").toModel().source)
        assertNull("".toPaymentMethod())
        assertNull(null.toPaymentMethod())
    }

    @Test fun `情境的所有變動類型可以存成 JSON 再讀回`() {
        val scenario = Scenario(
            id = 3,
            name = "整合卡債",
            createdOn = LocalDate.of(2026, 9, 10),
            changes = listOf(
                ScenarioChange.AdjustItems(listOf(501, 502), -20.0, 48_689),
                ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, 48_690, 1, 1, Half.SECOND),
                ScenarioChange.PayOffDebts(listOf(3, 4), 1, 48_690, stopScheduledPayments = false),
                ScenarioChange.ChangeMethod(listOf(501), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, 48_690),
                ScenarioChange.OneOff("修車", 48_700, FlowType.EXPENSE, 20_000, method = PaymentMethod.CREDIT_CARD),
                ScenarioChange.StopItem(601, 48_700),
            ),
            note = "示意",
        )
        assertEquals(scenario, scenario.toEntity().toModel())
    }

    @Test fun `情境 JSON 壞掉時讀成沒有變動，不會當機`() {
        val broken = Scenario(name = "x", createdOn = LocalDate.of(2026, 1, 1), changes = emptyList()).toEntity().copy(changesJson = "{oops")
        assertEquals(emptyList<ScenarioChange>(), broken.toModel().changes)
    }
}
