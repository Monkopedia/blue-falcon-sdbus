plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)

    // libsystemd lives at /usr/lib on Arch and /usr/lib/<triple> on Debian /
    // Ubuntu (multiarch). Include both so CI (ubuntu-latest) and Arch hosts
    // both resolve the library. Nonexistent -L paths are silently ignored.
    linuxX64 {
        binaries.all {
            linkerOpts(
                "-L/usr/lib",
                "-L/usr/lib/x86_64-linux-gnu",
                "-lsystemd", "-lrt", "--allow-shlib-undefined",
            )
        }
    }
    linuxArm64 {
        binaries.all {
            linkerOpts(
                "-L/usr/lib",
                "-L/usr/lib/aarch64-linux-gnu",
                "-lsystemd", "-lrt", "--allow-shlib-undefined",
            )
        }
    }
    jvm()

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
        task.name == "jvmTest" || task.name == "nativeTest" || task.name == "allTests"
}.configureEach { enabled = runIntegration }
