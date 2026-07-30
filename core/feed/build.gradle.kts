// SPDX-License-Identifier: GPL-3.0-or-later

// Android library (wraps Stalla for feed parsing). Empty until build order
// step 3.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.drehtuer.podsilo.core.feed"
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
