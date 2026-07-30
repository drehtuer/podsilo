// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: WorkManager + SAF file writing both need Android APIs (build
// order step 5, not yet built). Tag rewriting (this step) is plain-JVM logic
// against a java.io.File and needs neither — see docs/architecture.md §11 for
// why tagging happens in the app cache before the SAF copy.
plugins {
    alias(libs.plugins.android.library)
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
}

dependencies {
    implementation(libs.jaudiotagger)

    testImplementation(libs.junit4)
}
