// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.core.datastore.AppPasswordCipher
import net.drehtuer.podsilo.core.datastore.DataStoreSettingsRepository
import net.drehtuer.podsilo.core.datastore.KeystoreAppPasswordCipher
import net.drehtuer.podsilo.core.datastore.createSettingsDataStore
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import javax.inject.Singleton

/**
 * Settings storage. The `@Singleton` on the [DataStore] is load-bearing, not decorative: DataStore
 * permits at most one instance per file per process.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = createSettingsDataStore(context)

    /** The real Keystore binding — untested on the JVM by design (`docs/decisions/0010`). */
    @Provides
    @Singleton
    fun provideAppPasswordCipher(): AppPasswordCipher = KeystoreAppPasswordCipher()

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
        cipher: AppPasswordCipher,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore, cipher)
}
