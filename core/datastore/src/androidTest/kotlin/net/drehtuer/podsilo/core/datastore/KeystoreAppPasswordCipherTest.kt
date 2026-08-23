// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * `KeystoreAppPasswordCipher` on a real Android Keystore — the thing `architecture.adoc` §2 says can
 * only be checked here.
 *
 * Robolectric has no `AndroidKeyStore` provider, so until this existed the class had **never
 * executed**: `dev-environment.adoc` listed it as "never run", and the whole guarantee that the
 * Nextcloud app password is never persisted in plaintext (CLAUDE.md §5) rested on code nothing had
 * ever called.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAppPasswordCipherTest {
    private val cipher = KeystoreAppPasswordCipher()

    /** A realistic Nextcloud app password: 5 groups of 5, mixed case. */
    private val appPassword = "aBcDe-FgHiJ-kLmNo-PqRsT-uVwXy"

    @Test
    fun roundTripsAnAppPassword() {
        val encrypted = cipher.encrypt(appPassword)

        assertEquals(appPassword, cipher.decrypt(encrypted))
    }

    @Test
    fun theStoredFormNeverContainsThePlaintext() {
        // The point of the whole class. If this fails, the password is on disk in the clear.
        val encrypted = cipher.encrypt(appPassword)

        assertFalse("the ciphertext contains the plaintext", encrypted.contains(appPassword))
        assertNotEquals(appPassword, encrypted)
    }

    @Test
    fun encryptingTwiceProducesDifferentCiphertexts() {
        // GCM uses a fresh IV per encryption. Identical output for identical input would mean a
        // reused IV, which is the one thing GCM must never do — it leaks plaintext relationships.
        val first = cipher.encrypt(appPassword)
        val second = cipher.encrypt(appPassword)

        assertNotEquals("the IV is being reused", first, second)
        assertEquals(appPassword, cipher.decrypt(first))
        assertEquals(appPassword, cipher.decrypt(second))
    }

    @Test
    fun aSecondInstanceDecryptsWhatTheFirstWrote() {
        // What actually happens in the app: encrypt during S5's login, decrypt in a later process
        // when SyncWorker runs. If the key were per-instance rather than in the Keystore, sync
        // would fail after every restart with no obvious cause.
        val encrypted = cipher.encrypt(appPassword)

        assertEquals(appPassword, KeystoreAppPasswordCipher().decrypt(encrypted))
    }

    @Test
    fun theKeyLivesInTheAndroidKeystore() {
        cipher.encrypt(appPassword)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "the key is not in the Android Keystore, so it is not hardware-backed",
            keyStore.containsAlias("podsilo_nextcloud_app_password"),
        )
    }

    @Test
    fun handlesNonAsciiAndEmptyInput() {
        // The password comes from a server and is not ours to constrain; UTF-8 must survive.
        assertEquals("pässwörd-mit-ümlauten", cipher.decrypt(cipher.encrypt("pässwörd-mit-ümlauten")))
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }
}
