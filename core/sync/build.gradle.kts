// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM per CLAUDE.md §5 — reconciliation logic must be plain-JVM testable.
// Empty until the sync step of the build order.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit4)
}
