plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

/**
 * Offline CLI: replay a recorded landmark trace through an exercise evaluator and
 * print the resulting event stream. This is how evaluator changes are reviewed
 * without a phone in hand.
 */
dependencies {
    implementation(project(":android-sensor:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.fliptrainer.tools.traceinspector.MainKt")
}
