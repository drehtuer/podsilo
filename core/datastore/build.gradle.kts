// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: Jetpack DataStore needs an Android Context. Empty until
// settings storage (Nextcloud URL, folder URI, sync interval) is built.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.drehtuer.podsilo.core.datastore"
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
