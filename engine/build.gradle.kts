plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sdbus)
    alias(libs.plugins.vanniktech.maven.publish)
    signing
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

// Workaround: the sdbus-kotlin plugin wires its generator as an input to the
// Kotlin compile tasks (fixed in 0.4.3, see sdbus-kotlin#7), but the KMP
// aggregate `sourcesJar` used by vanniktech-maven-publish still consumes the
// generated sources without a declared dependency. Gradle 9 rejects that.
tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }
    .configureEach { dependsOn("generateSdbusWrappers") }

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = "blue-falcon-sdbus",
        version = project.version.toString(),
    )

    pom {
        name.set("Blue Falcon sdbus Engine")
        description.set(
            "Linux BlueZ engine for the Blue Falcon BLE library, " +
                "implemented on top of sdbus-kotlin."
        )
        url.set("https://github.com/Monkopedia/blue-falcon-sdbus")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("Monkopedia")
                name.set("Jason Monk")
                email.set("monkopedia@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/Monkopedia/blue-falcon-sdbus")
            connection.set("scm:git:git://github.com/Monkopedia/blue-falcon-sdbus.git")
            developerConnection.set(
                "scm:git:ssh://git@github.com/Monkopedia/blue-falcon-sdbus.git"
            )
        }
    }
}

signing {
    setRequired {
        gradle.taskGraph.allTasks.any {
            it.name.contains("publishTo") && it.name.contains("MavenCentral")
        }
    }
}
