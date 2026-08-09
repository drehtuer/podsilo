// SPDX-License-Identifier: GPL-3.0-or-later

// Imported rather than fully qualified: inside a build script `java` resolves to Gradle's own
// `java { }` extension, so `Instant` does not mean what it says.
import com.android.build.api.variant.impl.VariantOutputImpl
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The build number, derived from the commit count rather than kept by hand.
 *
 * `git rev-list --count HEAD` only ever grows, needs no state outside the repository, and gives the
 * same answer on CI and on a laptop for the same commit — which a CI-run-number would not. A shallow
 * clone or a tarball has no history, hence the fallback; CI checks out with `fetch-depth: 0` so the
 * real number is used there.
 */
fun gitCommitCount(): Int =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText
            .get()
            .trim()
            .toInt()
    }.getOrDefault(1)

fun gitShortSha(): String =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText
            .get()
            .trim()
    }.getOrDefault("unknown")

/**
 * The build's own timestamp, so *"is this the build I just made?"* is answerable from the About
 * screen. UTC and minute-resolution: the point is identifying a build, not timing it.
 *
 * This does change on every configuration, which would defeat the build cache if it were used
 * widely. It is used in exactly one generated constant, so what it costs is regenerating
 * `BuildConfig` — and being unable to tell two builds apart was costing more.
 */
val buildTimestamp: String =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

android {
    namespace = "net.drehtuer.podsilo"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.drehtuer.podsilo"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = gitCommitCount()
        versionName = "0.2.1"

        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
    }

    /**
     * Release signing, when the key is available.
     *
     * The keystore is **never** in the repository (CLAUDE.md §9). It comes from `keystore.properties`
     * locally or the matching environment variables on CI, and when neither is present the release
     * variant simply builds unsigned rather than failing — an unsigned release APK is still a useful
     * artifact to inspect, it just cannot be installed. `docs/dev-environment.md` §9 has the
     * `keytool` invocation and the CI secret names.
     */
    signingConfigs {
        val keystoreProperties =
            file("../keystore.properties").takeIf { it.exists() }?.let { propertiesFile ->
                Properties().apply { propertiesFile.inputStream().use { load(it) } }
            }

        // `isNotBlank`, not `!= null`: an unset GitHub secret arrives as an empty string rather than
        // an absent variable, so `System.getenv` returns "" and `file("")` fails the whole build with
        // "Cannot convert '' to File". Every unsigned CI run would break instead of building unsigned.
        fun setting(
            key: String,
            env: String,
        ): String? = (keystoreProperties?.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

        // Relative paths resolve against the repository root, so one `keystore.properties` works from
        // the dev container and the host alike — they do not agree on absolute paths.
        val storeFile = setting("storeFile", "PODSILO_KEYSTORE_FILE")?.let { rootProject.file(it) }
        when {
            storeFile == null -> logger.info("No release keystore configured; the release build will be unsigned.")
            !storeFile.exists() ->
                logger.warn("Release keystore ${storeFile.path} does not exist — the release build will be UNSIGNED.")
            else ->
                create("release") {
                    this.storeFile = storeFile
                    storePassword = setting("storePassword", "PODSILO_KEYSTORE_PASSWORD")
                    keyAlias = setting("keyAlias", "PODSILO_KEY_ALIAS")
                    // keytool lets a key share the store's password, and leaving `keyPassword` empty
                    // is how that is expressed. Passing "" instead would be rejected as a wrong one.
                    keyPassword = setting("keyPassword", "PODSILO_KEY_PASSWORD") ?: storePassword

                    // Stated rather than inherited. v1 (JAR signing) is dead weight at minSdk 33 —
                    // it only matters below Android 7 — and it is the scheme that puts a
                    // META-INF/*.RSA in the zip, so leaving it off is also why "is this APK signed?"
                    // cannot be answered by looking for that file. v3 carries the key-rotation
                    // lineage, which is worth having before a key is ever rotated, not after.
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                }
        }
    }

    buildTypes {
        release {
            // A real release build: R8 strips unused code, renames what is left, and drops the
            // debug metadata. Previously `assembleRelease` produced something that differed from
            // the debug APK only by name, which is what "there is no release build" meant.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // Left installable alongside nothing else: no applicationIdSuffix, deliberately. A
            // suffix would make the debug build a *different app*, and the SAF folder grant and the
            // episode ledger both belong to an application id — changing it would silently orphan
            // the author's downloads and login on the next install.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Names the APKs `podsilo-<version>.apk` and `podsilo-<version>-debug.apk`.
 *
 * AGP's default is `app-debug.apk` — the module name, which says nothing about which build it is.
 * A release asset called `app-debug.apk` is actively misleading, and two downloaded builds of
 * different versions are indistinguishable in a downloads folder.
 *
 * `VariantOutputImpl` is an AGP internal, which is unfortunate and deliberate: the public
 * `Variant.outputs` exposes the version fields but not the file name, and the old
 * `applicationVariants` DSL that used to own this is gone. The alternative is a copy task that
 * duplicates every APK under a second name. If a future AGP promotes `outputFileName`, this drops
 * to the public API unchanged.
 */
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "podsilo-${android.defaultConfig.versionName}$suffix.apk",
            )
        }
    }
}

dependencies {
    implementation(libs.coil.network.okhttp)
    // :app is the only module that sees every adapter: it binds each :core:model port to its
    // implementation (docs/architecture.md §2's ports-and-adapters rule).
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:download"))
    implementation(project(":core:feed"))
    implementation(project(":core:gpodder"))
    implementation(project(":core:naming"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":feature:episodes"))
    implementation(project(":feature:settings"))

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // Named directly in this module's Hilt @Provides signatures (Room.databaseBuilder,
    // DataStore<Preferences>), which the core modules keep as `implementation` details.
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Pinned: Compose UI tests pull Espresso transitively, and the version they pulled predates
    // Android 16+ — it calls InputManager.getInstance(), which no longer exists, so every Compose
    // instrumented test died in onIdle() before running a line (docs/journal.md, 2026-08-08).
    androidTestImplementation(libs.androidx.test.espresso.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
