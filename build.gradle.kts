// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// ktlint + detekt apply to every module, including the still-empty stubs, so
// `./gradlew ktlintCheck detekt` (CLAUDE.md §7/§8) has something to check as
// soon as source lands in any of them.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))

        // detekt's default roots are `src/main` + `src/test` only, so until this line the device
        // tests under `src/androidTest/` — the ones CI cannot run either (`.github/workflows/ci.yml`)
        // — were the only Kotlin in the repository with no complexity or style checking at all. An
        // over-length line in one of them passed `./gradlew ktlintCheck detekt` and had to be found
        // by hand, which is what put this in `docs/backlog.md`.
        //
        // ktlint needs no equivalent: its Android plugin registers the `androidTest` source set
        // itself (`runKtlintCheckOverAndroidTestSourceSet`), which is why only detekt is named here.
        source.from(files("src/androidTest/kotlin", "src/androidTest/java"))
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            xml.required.set(false)
            txt.required.set(false)
        }
    }
}
