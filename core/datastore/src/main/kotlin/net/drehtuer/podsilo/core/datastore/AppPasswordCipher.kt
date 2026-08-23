// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

/**
 * Encrypts/decrypts the Nextcloud app password before it is written to DataStore — never plaintext
 * (CLAUDE.md §5). Abstracted behind an interface so the [DataStoreSettingsRepository] serialisation
 * logic is unit-testable with an in-memory fake, while the production [KeystoreAppPasswordCipher]
 * (Android Keystore, only meaningfully exercisable on a real device/emulator) stays out of the
 * Robolectric path. See `docs/architecture.adoc` §2.
 */
interface AppPasswordCipher {
    /** Returns an opaque, storable ciphertext string for [plaintext]. */
    fun encrypt(plaintext: String): String

    /** Inverse of [encrypt]. Throws if [ciphertext] can't be decrypted (e.g. the key was invalidated). */
    fun decrypt(ciphertext: String): String
}
