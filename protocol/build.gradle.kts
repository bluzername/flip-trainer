plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(project(":android-sensor:core"))
    implementation(libs.protobuf.javalite)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("fliptrainer.fixtures", rootProject.file("test-fixtures").absolutePath)
    System.getProperty("fliptrainer.updateGoldens")?.let { systemProperty("fliptrainer.updateGoldens", it) }
}

protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") { option("lite") }
            }
        }
    }
}
