// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM per CLAUDE.md §5 — domain types and repository/client port interfaces must be
// Android-free so :core:sync (and every test in this module) is plain-JVM testable (Tier 1).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
