// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: WorkManager, the SAF write, and the foreground-service notification all need
// Android APIs. The parts that don't — the HTTP download into the app cache, the tag rewriting, and
// the pipeline that sequences them — are plain-JVM classes sitting behind the DownloadTarget port
// (docs/decisions/0011), so they are unit-testable with MockWebServer and a temp directory, with no
// emulator. See docs/architecture.md §8/§11 for why tagging must happen before the SAF copy.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "net.drehtuer.podsilo.core.download"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:naming"))
    implementation(libs.jaudiotagger)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.documentfile)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
