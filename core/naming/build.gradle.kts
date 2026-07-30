// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM per CLAUDE.md §5/§10 — templates, sanitisation, and truncation must
// be testable without Android. Empty until the naming step of the build order.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit4)
}
