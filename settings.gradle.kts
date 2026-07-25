/**
 * Flip Trainer build.
 *
 * The Android application module is only included when an Android SDK is actually
 * configured. Every piece of decision logic — pose geometry, readiness, exercise
 * evaluators, session lifecycle, protocol, pairing crypto — lives in pure JVM modules
 * that build and test without an SDK, an emulator, or a device.
 *
 * See docs/DECISIONS.md, ADR-0001.
 *
 * `pluginManagement` is hoisted and evaluated before the script body, so it cannot see
 * script-level declarations. The SDK probe is therefore duplicated rather than shared.
 */

pluginManagement {
    fun androidSdkDir(): String? =
        sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            rootDir
                .resolve("local.properties")
                .takeIf { it.isFile }
                ?.inputStream()
                ?.use { java.util.Properties().apply { load(it) }.getProperty("sdk.dir") },
        ).filterNotNull().firstOrNull { it.isNotBlank() && File(it).isDirectory }

    repositories {
        if (androidSdkDir() != null) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx.*")
                    includeGroupByRegex("com\\.google\\.mediapipe.*")
                    includeGroupByRegex("com\\.google\\.testing.*")
                }
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val androidSdkDir: String? =
    sequenceOf(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        rootDir
            .resolve("local.properties")
            .takeIf { it.isFile }
            ?.inputStream()
            ?.use { java.util.Properties().apply { load(it) }.getProperty("sdk.dir") },
    ).filterNotNull().firstOrNull { it.isNotBlank() && File(it).isDirectory }

val androidEnabled = androidSdkDir != null

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (androidEnabled) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx.*")
                    includeGroupByRegex("com\\.google\\.mediapipe.*")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "flip-trainer"

include(":android-sensor:core")
include(":protocol")
include(":tools:trace-inspector")

if (androidEnabled) {
    include(":android-sensor:app")
}

gradle.rootProject {
    if (androidEnabled) {
        logger.lifecycle("Android SDK at $androidSdkDir — :android-sensor:app INCLUDED.")
    } else {
        logger.lifecycle(
            "No Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties sdk.dir) — " +
                ":android-sensor:app EXCLUDED. Pure JVM modules still build and test.",
        )
    }
}
