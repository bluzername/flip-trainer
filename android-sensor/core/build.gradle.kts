plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Pure JVM. No Android, no CameraX, no MediaPipe, no Room.
 *
 * Everything that decides something — geometry, readiness, phase machines, rep counting, session
 * lifecycle — must be testable here without a device.
 *
 * The exercise definitions live at the repository root (plan §8) and are copied onto the classpath
 * from there. One copy, not two: a duplicated definition is a definition that will drift.
 */
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("exercise-definitions")) {
        into("exercise-definitions")
        include("*.json")
    }
}

tasks.withType<Test>().configureEach {
    // Golden traces and expected events live at the repository root (plan §8), not inside the
    // module, so tests are told where to find them rather than guessing from a working directory.
    systemProperty("fliptrainer.fixtures", rootProject.file("test-fixtures").absolutePath)
    // ./gradlew :android-sensor:core:test -Dfliptrainer.updateGoldens=true
    System.getProperty("fliptrainer.updateGoldens")?.let { systemProperty("fliptrainer.updateGoldens", it) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
