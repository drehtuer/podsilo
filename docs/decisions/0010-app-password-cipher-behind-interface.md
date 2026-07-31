# 0010 — App-password encryption behind an `AppPasswordCipher` interface

## Status

Accepted. Introduced while building `:core:datastore` (Tier 4a).

## Context

CLAUDE.md §5 and `docs/architecture.md` §2 require the Nextcloud app password to be stored
"via `EncryptedSharedPreferences` or DataStore + Keystore-backed encryption — never plaintext."
We store settings in Jetpack DataStore (Preferences) already, so the app password becomes one more
value — but unlike the folder URI or sync interval, it must be encrypted at rest.

Two forces pull against each other:

1. **The encryption must use the Android Keystore** to be worth anything — a key that never leaves
   the TEE, so a rooted-filesystem dump doesn't yield the password. That is an Android-runtime API.
2. **The store/serialise/read plumbing should be unit-testable** on the plain JVM runner, like the
   rest of `:core:datastore` (which needs no Robolectric — DataStore-Preferences runs over a plain
   temp file). Robolectric has no real `AndroidKeyStore` provider, so a cipher that calls the
   Keystore directly can't be exercised there, and folding it into the repository would drag the
   whole settings surface onto the device to test.

We also rejected `androidx.security:security-crypto` (`EncryptedSharedPreferences`): it is
deprecated by Jetpack, and it would reintroduce a SharedPreferences store next to the DataStore we
already use — exactly the split CLAUDE.md §3 tells us to avoid.

## Decision

Put encryption behind a one-method-pair interface,
[`AppPasswordCipher`](../../core/datastore/src/main/kotlin/net/drehtuer/podsilo/core/datastore/AppPasswordCipher.kt)
(`encrypt`/`decrypt` over `String`), and inject it into `DataStoreSettingsRepository`.

- Production:
  [`KeystoreAppPasswordCipher`](../../core/datastore/src/main/kotlin/net/drehtuer/podsilo/core/datastore/KeystoreAppPasswordCipher.kt)
  — AES-256/GCM with a non-exportable `AndroidKeyStore` key; the stored form is Base64 of
  `[12-byte IV][ciphertext+tag]`.
- Tests: `FakeAppPasswordCipher`, a trivial reversible transform. The repository tests then cover
  the parts that actually have bugs — that the password is written as ciphertext and never as
  plaintext, that credentials round-trip, that clearing removes all three fields, and that a cipher
  which *fails* to decrypt (an invalidated key) degrades to "no credentials" instead of crashing
  (CLAUDE.md §11 resilience) — not the AES maths itself, which is the JDK provider's job.

## Consequences

- **`KeystoreAppPasswordCipher` itself is not covered by the JVM tests.** It needs an instrumented
  test on a real device/emulator (Tier 4b) to prove the Keystore round-trip. Stated plainly here
  and in the module so it isn't mistaken for tested code.
- `:app`'s Hilt graph (Tier 4c) binds `AppPasswordCipher` → `KeystoreAppPasswordCipher` and
  provides the singleton `DataStore` via `createSettingsDataStore(context)`.
- The decrypted password is only ever materialised inside `SettingsRepository.nextcloudCredentials()`
  at the point of a sync pass, never held in a long-lived `Flow` — which is why that one accessor is
  a `suspend` read rather than an observable, unlike every other setting.
