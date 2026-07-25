plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Pure JVM. No Android, no CameraX, no MediaPipe, no Room.
 *
 * Everything that decides something — geometry, readiness, phase machines, rep
 * counting, session lifecycle — must be testable here without a device.
 */
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
