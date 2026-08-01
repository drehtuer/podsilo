// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM, not com.android.library — resolves docs/architecture.md §12 open decision #3.
// Nothing in this module's job (Retrofit/OkHttp client, DTOs, DTO<->domain mapping) touches an
// Android API, so a JVM module compiles the "no Android" property in rather than relying on
// review to catch a stray import. Its MockWebServer tests then run on the plain `test` task with
// no Robolectric and no Android test runner. See docs/decisions/0007.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
