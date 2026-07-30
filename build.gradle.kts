// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
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
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            xml.required.set(false)
            txt.required.set(false)
        }
    }
}
