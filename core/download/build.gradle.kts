// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: WorkManager + SAF file writing both need Android APIs.
// Empty until build order step 5.
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
    testImplementation(libs.junit4)
}
