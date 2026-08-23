// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [AppPasswordCipher] backed by the Android Keystore: an AES-256/GCM key that never
 * leaves the TEE/StrongBox, so the app password's plaintext is only ever materialised in memory,
 * never persisted (CLAUDE.md §5). The stored form is Base64 of `[12-byte IV][GCM ciphertext+tag]`.
 *
 * Not covered by the module's Robolectric tests — Robolectric has no real `AndroidKeyStore`
 * provider, so this class is verified on a device/emulator (Tier 4b instrumented). The serialise/
 * store/read plumbing around it is what the JVM tests exercise, via a fake cipher. See
 * `docs/architecture.adoc` §2.
 */
class KeystoreAppPasswordCipher : AppPasswordCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_LENGTH_BYTES)
        val payload = bytes.copyOfRange(IV_LENGTH_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "podsilo_nextcloud_app_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_LENGTH_BYTES = 12
    }
}
