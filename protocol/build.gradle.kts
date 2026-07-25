plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Wire protocol: `.proto` definitions, generated message classes, pairing crypto,
 * authenticated envelopes, framing, and the acknowledgement/replay engine.
 *
 * Pure JVM so the whole handshake and replay story is unit-testable without BLE.
 * Populated in Session 9.
 */
dependencies {
    implementation(project(":android-sensor:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
