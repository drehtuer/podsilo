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
