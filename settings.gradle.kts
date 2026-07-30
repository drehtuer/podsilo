// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for com.github.Adonai:jaudiotagger (Android-compatible fork, not on Maven
        // Central) — see docs/decisions/0006. JitPack builds straight from GitHub source,
        // a different trust model than a registry-reviewed release; don't add other
        // dependencies through it without the same scrutiny.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "podsilo"

include(":app")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:feed")
include(":core:naming")
include(":core:download")
include(":core:gpodder")
include(":core:sync")
include(":feature:episodes")
include(":feature:settings")
