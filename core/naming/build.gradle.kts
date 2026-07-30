// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM per CLAUDE.md §5/§10 — templates, sanitisation, and truncation must
// be testable without Android. Sanitisation, truncation, and hashing all use JDK
// stdlib only (java.text.Normalizer/BreakIterator, java.security.MessageDigest,
// java.time) — no new third-party dependency needed for this module.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit4)
}
