// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

/**
 * Deterministic in-memory stand-in for [KeystoreAppPasswordCipher] (whose real Android Keystore
 * backing can't run under the JVM test runner). The transform is intentionally trivial and
 * reversible; the point of the tests is the store/serialise plumbing, not the crypto — which is
 * verified on-device (docs/decisions/0010). The obvious non-identity `enc:` prefix lets a test
 * assert the value written to DataStore is *not* the plaintext password.
 */
class FakeAppPasswordCipher : AppPasswordCipher {
    override fun encrypt(plaintext: String): String = PREFIX + plaintext.reversed()

    override fun decrypt(ciphertext: String): String = ciphertext.removePrefix(PREFIX).reversed()

    companion object {
        const val PREFIX = "enc:"
    }
}
