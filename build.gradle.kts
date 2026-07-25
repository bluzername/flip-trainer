plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * Shared configuration for every pure JVM module.
 *
 * Bytecode target is 17 so the same artifacts can be consumed by the Android
 * application module without a second compilation path.
 */
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(
                rootProject.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findVersion("ktlint-tool")
                    .get()
                    .requiredVersion,
            )
            filter {
                exclude { it.file.path.contains("/generated/") }
                exclude { it.file.path.contains("/build/") }
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

/** Everything a session-end verification run needs, in one command. */
tasks.register("verify") {
    group = "verification"
    description = "Build, lint and test every module that can be built in this environment."
    dependsOn(
        provider {
            subprojects
                .filter { it.plugins.hasPlugin("org.jetbrains.kotlin.jvm") }
                .map { "${it.path}:check" }
        },
    )
}
