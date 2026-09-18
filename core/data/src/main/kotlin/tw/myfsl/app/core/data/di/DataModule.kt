package tw.myfsl.app.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import tw.myfsl.app.core.data.TimeProvider
import tw.myfsl.app.core.data.db.AppDatabase
import tw.myfsl.app.core.data.db.Migrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "myfsl.db")
            // 升級時保留資料；只有降版（裝回舊版 App）才重建。
            .addMigrations(*Migrations.all)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    @Provides
    fun provideTimeProvider(): TimeProvider = object : TimeProvider {
        override fun today(): LocalDate = LocalDate.now()
        override fun nowMillis(): Long = System.currentTimeMillis()
    }
}
