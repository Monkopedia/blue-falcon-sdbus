plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sdbus)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(17)

    linuxX64()
    linuxArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.blue.falcon.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        linuxMain {
            dependencies {
                implementation(libs.sdbus.kotlin)
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }
}

sdbus {
    sources.srcDirs("src/dbus")
    outputs.add("linuxMain")
    generateProxies = true
    generateAdapters = true
    outputPackage = "com.monkopedia.bluefalcon.sdbus.bluez"
}

// Workaround: sdbus-kotlin 0.4.2 plugin doesn't wire its generators as inputs
// to the Kotlin native compile tasks, which Gradle 9 flags as an error.
listOf("compileKotlinLinuxX64", "compileKotlinLinuxArm64").forEach { compileTask ->
    tasks.named(compileTask) { dependsOn("generateSdbusWrappers") }
}
