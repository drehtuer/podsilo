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

    // ktlint-gradle 14.2.0 does not treat a *removed* Kotlin file as an input change, so
    // `runKtlintCheckOver<X>SourceSet` stays UP-TO-DATE, its stale
    // `build/intermediates/ktLint/*_errors.bin` survives, and `ktlint<X>SourceSetCheck` keeps
    // failing on a file that is no longer on disk — through a daemon restart, a `--rerun`, and
    // deleting the report by hand. Reproduced from a clean state in `src/main`, `src/test` and
    // `src/androidTest` alike; 14.2.0 is the current release, so there is no upgrade to wait for.
    //
    // This adds the one thing the plugin's snapshot is missing: the *list of files that exist*. It
    // is deliberately coarse — every Kotlin path under the module's `src`, shared by every ktlint
    // task in that module — because the value only changes when a file is added or renamed or
    // deleted, and the first two already invalidate the task correctly. So the practical cost is a
    // directory walk per task, and what it buys is that the third does too.
    //
    // The alternative was `outputs.upToDateWhen { false }`, measured at 0.9s -> ~13s for every
    // `ktlintCheck`. Paying that on every invocation to fix a rare case is the wrong trade; this
    // pays nothing on the common one.
    tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
        inputs
            .property("ktlintSourceFileList") {
                projectDir
                    .resolve("src")
                    .walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "kts") }
                    .map { it.relativeTo(projectDir).invariantSeparatorsPath }
                    .sorted()
                    .joinToString("\n")
            }.optional(true)
    }
}
