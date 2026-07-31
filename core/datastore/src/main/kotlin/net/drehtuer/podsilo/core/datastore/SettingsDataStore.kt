// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/** File name of the settings DataStore. */
const val SETTINGS_DATASTORE_NAME: String = "podsilo_settings"

/**
 * Builds the production settings [DataStore]. Kept as a plain factory (not a `Context` property
 * delegate) so `:app`'s Hilt module can provide it as a singleton and tests can build one over a
 * temp file instead. There must be at most one DataStore instance per file per process.
 */
fun createSettingsDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) },
    )
