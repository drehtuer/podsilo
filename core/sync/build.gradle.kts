// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM per CLAUDE.md §5 — reconciliation logic must be plain-JVM testable. Depends only on
// :core:model's ports/domain types (never Room or Retrofit directly — see docs/architecture.adoc §2);
// tests use hand-written in-memory fakes of those ports, not MockWebServer or a real database.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
