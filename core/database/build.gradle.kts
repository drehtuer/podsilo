// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: Room needs an Android Context. Empty until :core:database
// lands (Entities, DAOs, migrations — build order step 2).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.drehtuer.podsilo.core.database"
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
