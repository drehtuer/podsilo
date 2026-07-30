// SPDX-License-Identifier: GPL-3.0-or-later

// Android library (Retrofit client for the Nextcloud GPodder API). Empty
// until build order step 6.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.drehtuer.podsilo.core.gpodder"
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
