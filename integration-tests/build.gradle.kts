plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)

    linuxX64 {
        binaries.all {
            linkerOpts("-L/usr/lib", "-lsystemd", "-lrt", "--allow-shlib-undefined")
        }
    }
    linuxArm64 {
        binaries.all {
            linkerOpts("-lsystemd", "-lrt", "--allow-shlib-undefined")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":engine"))
                implementation(libs.blue.falcon.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }
}

// Integration tests require a live BF-Test peripheral (see README), so only
// execute them when explicitly opted in with -PrunIntegrationTests=true. The
// link step still runs as part of `build` so CI catches API breakage.
val runIntegration = providers.gradleProperty("runIntegrationTests").orNull == "true"
tasks.matching { task ->
    task.name == "linuxX64Test" || task.name == "linuxArm64Test" ||
        task.name == "nativeTest" || task.name == "allTests"
}.configureEach { enabled = runIntegration }
