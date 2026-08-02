// SPDX-License-Identifier: GPL-3.0-or-later

// The shared Compose vocabulary: the icon allow-list and the spacing invariants that both feature
// modules and :app render against. It exists so those cannot drift between screens — the whole point
// of docs/UI.md §17 and §18 — and holds no state, no ports and no screens.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.drehtuer.podsilo.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // `api`, not `implementation`: PodsiloIcons returns painters built from this artifact's
    // drawables, so every consumer needs it on the compile classpath.
    api(libs.lucide.icons)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
