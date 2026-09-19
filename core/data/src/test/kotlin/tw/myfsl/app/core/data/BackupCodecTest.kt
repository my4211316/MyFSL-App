package tw.myfsl.app.core.data

import tw.myfsl.app.core.data.db.PlanAmountEntity
import tw.myfsl.app.core.data.db.PostedKeyEntity
import tw.myfsl.app.core.data.db.toColumn
import tw.myfsl.app.core.data.db.toEntity
import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.Deferral
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BackupCodecTest {

    private val sample = BackupFile(
        exportedAtMillis = 1_789_000_000_000,
        accounts = SampleHousehold.accounts.map {
            if (it.id == SampleHousehold.CARD_A) it.copy(card = it.card!!.copy(revolvingRatePercent = 14.88)) else it
        }.map { it.toEntity() },
        groups = SampleHousehold.groups.map { it.toEntity() },
        items = SampleHousehold.items.map { it.toEntity() },
        amounts = SampleHousehold.yearlyPlan.flatMap { (line, months) ->
            months.mapIndexedNotNull { i, v -> if (v != 0L) PlanAmountEntity(line.itemId, line.method.toColumn(), 2026, i + 1, v) else null }
        },
        ledger = SampleHousehold.septemberLedger.map { it.toEntity() },
        scenarios = listOf(
            Scenario(7, "整合", LocalDate.of(2026, 9, 1), listOf(ScenarioChange.StopItem(SampleHousehold.TRIP, 48_700)), "備註").toEntity(),
        ),
        settings = SettingsBackup.of(
            SampleHousehold.settings.copy(
                checkInDay = DayOfWeek.FRIDAY,
                defaultCardId = SampleHousehold.CARD_B,
                autoPostFrom = LocalDate.of(2026, 9, 1).toEpochDay(),
            ),
        ),
        // 選了「這個月沒有」的到期項目與延期款也要備份，還原後才不會再列出或重複記
        postedKeys = listOf(PostedKeyEntity("plan:101:-:2026-09:15", 20_711), PostedKeyEntity("cardpay:3:2026-09", 20_711)),
        deferrals = listOf(Deferral(4, SampleHousehold.SUBSIDY, null, 2026, 9, 2026, 10, 5_000).toEntity()),
        // 帳單校正（R-CARD-23）也要備份
        cardStatements = listOf(CardStatement(SampleHousehold.CARD_A, 2026, 9, 52_000, 3_000, coversInterest = true).toEntity()),
    )

    @Test fun `匯出再讀回完全一樣（含 id、繳款方式、帳單、情境與設定）`() {
        val text = BackupCodec.encode(sample)
        val result = BackupCodec.decode(text) as BackupReadResult.Ok
        assertEquals(sample, result.file)
        result.file.accounts.first { it.id == SampleHousehold.CARD_A }.run {
            assertTrue(cardSchedule)
            assertEquals(14.88, cardRatePercent!!, 0.0)
            assertEquals(1, cardStatementDay)
        }
        assertEquals(52_000L, result.file.cardStatements.single().amount)
        assertEquals(DayOfWeek.FRIDAY, result.file.settings!!.toSettings(SampleHousehold.settings).checkInDay)
        assertEquals(sample.accounts.size, result.summary.accounts)
        assertEquals(9, result.summary.ledger)
        assertTrue(result.summary.warnings.isEmpty())
    }

    @Test fun `Excel 存過的 BOM 與未知欄位不影響讀取`() {
        val text = "﻿" + BackupCodec.encode(sample).replaceFirst("{", "{\"futureField\":1,")
        assertTrue(BackupCodec.decode(text) is BackupReadResult.Ok)
    }

    @Test fun `錯誤：不是 JSON、別的 App、較新版本、重複 id`() {
        assertEquals("這不是 MyFSL 的備份檔，或檔案已損壞", (BackupCodec.decode("群組,項目") as BackupReadResult.Error).message)
        assertEquals("這不是 MyFSL 的備份檔", (BackupCodec.decode(BackupCodec.encode(sample.copy(app = "Other"))) as BackupReadResult.Error).message)
        val newer = BackupCodec.decode(BackupCodec.encode(sample.copy(formatVersion = 99))) as BackupReadResult.Error
        assertTrue(newer.message.contains("較新版本"))
        val dup = BackupCodec.decode(BackupCodec.encode(sample.copy(ledger = sample.ledger + sample.ledger.first()))) as BackupReadResult.Error
        assertTrue(dup.message.contains("記帳"))
    }

    @Test fun `對不上的資料：提醒，計畫金額與校正略過`() {
        val broken = sample.copy(
            items = sample.items.filter { it.id != SampleHousehold.LIVING },
        )
        val result = BackupCodec.decode(BackupCodec.encode(broken)) as BackupReadResult.Ok
        assertTrue(result.summary.warnings.any { it.contains("記帳") })
        assertTrue(result.summary.warnings.any { it.contains("計畫金額") })
        assertTrue(result.file.amounts.none { it.itemId == SampleHousehold.LIVING })
    }

    @Test fun `延期款的項目不存在：提醒並略過；識別碼照樣保留`() {
        val broken = sample.copy(items = sample.items.filter { it.id != SampleHousehold.SUBSIDY })
        val result = BackupCodec.decode(BackupCodec.encode(broken)) as BackupReadResult.Ok
        assertTrue(result.summary.warnings.contains("1 筆延期款的項目不存在，會被略過"))
        assertTrue(result.file.deferrals.isEmpty())
        assertEquals(2, result.file.postedKeys.size)
        assertTrue("有對不上的資料時提供救援還原", result.summary.needsRescue)
    }
}
