package tw.myfsl.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            checkInDay = prefs[CHECK_IN_DAY]?.let { DayOfWeek.of(it) } ?: defaults.checkInDay,
            safetyLevel = prefs[SAFETY_LEVEL] ?: defaults.safetyLevel,
            horizonMonths = prefs[HORIZON_MONTHS] ?: defaults.horizonMonths,
            onboarded = prefs[ONBOARDED] ?: defaults.onboarded,
            pickCard = prefs[PICK_CARD] ?: defaults.pickCard,
            cashAccountId = prefs[CASH_ACCOUNT],
            transferAccountId = prefs[TRANSFER_ACCOUNT],
            cardPostingDays = prefs[CARD_POSTING_DAYS] ?: defaults.cardPostingDays,
            lastBackupEpochDay = prefs[LAST_BACKUP_DAY],
            defaultCardId = prefs[DEFAULT_CARD],
            autoPostFrom = prefs[AUTO_POST_FROM],
            dataGeneration = prefs[DATA_GENERATION] ?: 0L,
            reminderDays = prefs[REMINDER_DAYS]?.let { text -> text.split(',').mapNotNull { it.trim().toIntOrNull() } } ?: defaults.reminderDays,
        )
    }

    // 寫入函式一律回傳 Unit，不把 DataStore 的 Preferences 型別外露給其他模組。

    suspend fun setCheckInDay(day: DayOfWeek) {
        dataStore.edit { it[CHECK_IN_DAY] = day.value }
    }

    suspend fun setSafetyLevel(amount: Money) {
        dataStore.edit { it[SAFETY_LEVEL] = amount }
    }

    suspend fun setHorizonMonths(months: Int) {
        dataStore.edit { it[HORIZON_MONTHS] = months }
    }

    suspend fun setOnboarded(value: Boolean) {
        dataStore.edit { it[ONBOARDED] = value }
    }

    suspend fun setPickCard(value: Boolean) {
        dataStore.edit { it[PICK_CARD] = value }
    }

    suspend fun setCashAccount(id: Long?) {
        dataStore.edit { if (id == null) it.remove(CASH_ACCOUNT) else it[CASH_ACCOUNT] = id }
    }

    suspend fun setTransferAccount(id: Long?) {
        dataStore.edit { if (id == null) it.remove(TRANSFER_ACCOUNT) else it[TRANSFER_ACCOUNT] = id }
    }

    suspend fun setCardPostingDays(days: Int) {
        dataStore.edit { it[CARD_POSTING_DAYS] = days }
    }

    suspend fun setLastBackup(epochDay: Long) {
        dataStore.edit { it[LAST_BACKUP_DAY] = epochDay }
    }

    /** 設定到期項目的起算日；已經設過就不改（只在第一次開始使用時設定）。 */
    suspend fun startAutoPostingIfNeeded(epochDay: Long) {
        dataStore.edit { if (it[AUTO_POST_FROM] == null) it[AUTO_POST_FROM] = epochDay }
    }

    /** 資料世代（和資料庫裡的一致時快照才有效）；只由資料層在整份替換資料時寫。 */
    suspend fun setDataGeneration(generation: Long) {
        dataStore.edit { it[DATA_GENERATION] = generation }
    }

    suspend fun setAutoPostFrom(epochDay: Long?) {
        dataStore.edit { if (epochDay == null) it.remove(AUTO_POST_FROM) else it[AUTO_POST_FROM] = epochDay }
    }

    /** 一次寫入設定畫面上的所有欄位。 */
    suspend fun save(settings: AppSettings) {
        dataStore.edit {
            it[SAFETY_LEVEL] = settings.safetyLevel
            it[HORIZON_MONTHS] = settings.horizonMonths
            it[CHECK_IN_DAY] = settings.checkInDay.value
            it[PICK_CARD] = settings.pickCard
            val cash = settings.cashAccountId
            if (cash == null) it.remove(CASH_ACCOUNT) else it[CASH_ACCOUNT] = cash
            val transfer = settings.transferAccountId
            if (transfer == null) it.remove(TRANSFER_ACCOUNT) else it[TRANSFER_ACCOUNT] = transfer
            it[CARD_POSTING_DAYS] = settings.cardPostingDays
            val card = settings.defaultCardId
            if (card == null) it.remove(DEFAULT_CARD) else it[DEFAULT_CARD] = card
            it[REMINDER_DAYS] = settings.reminderDays.joinToString(",")
        }
    }

    private companion object {
        val CHECK_IN_DAY = intPreferencesKey("check_in_day")
        val SAFETY_LEVEL = longPreferencesKey("safety_level")
        val HORIZON_MONTHS = intPreferencesKey("horizon_months")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val PICK_CARD = booleanPreferencesKey("pick_card")
        val CASH_ACCOUNT = longPreferencesKey("cash_account_id")
        val TRANSFER_ACCOUNT = longPreferencesKey("transfer_account_id")
        val CARD_POSTING_DAYS = intPreferencesKey("card_posting_days")
        val LAST_BACKUP_DAY = longPreferencesKey("last_backup_epoch_day")
        val DEFAULT_CARD = longPreferencesKey("default_card_id")
        val AUTO_POST_FROM = longPreferencesKey("auto_post_from_epoch_day")
        val DATA_GENERATION = longPreferencesKey("data_generation")
        val REMINDER_DAYS = stringPreferencesKey("reminder_days")
    }
}
