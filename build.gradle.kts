plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("com.google.devtools.ksp") version "2.3.5"
    kotlin("plugin.serialization") version "2.3.10"
    id("io.ktor.plugin") version "3.4.0"
}

group = "eu.darken"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.dagger:dagger:2.59.1")
    ksp("com.google.dagger:dagger-compiler:2.59.1")

    val ktorVersion = "3.4.0"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-body-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-auto-head-response:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    implementation("ch.qos.logback:logback-classic:1.5.29")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("eu.darken.octi.server.App")
}

tasks.register("generateBuildInfo") {
    doLast {
        val gitSHA = try {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            print("gitSHA error: $e")
            "?"
        }

        val gitDate = try {
            providers.exec {
                commandLine("git", "show", "-s", "--format=%ci", gitSHA)
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            print("gitDate error: $e")
            "?"
        }

        val outputDir = File(layout.buildDirectory.asFile.get(), "generated/buildinfo")
        outputDir.mkdirs()
        File(outputDir, "BuildInfo.kt").apply {
            writeText(
                """
                    package eu.darken.octi.server
        
                    object BuildInfo {
                        const val GIT_SHA: String = "$gitSHA"
                        const val GIT_DATE: String = "$gitDate"
                    }
                """.trimIndent()
            )
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateBuildInfo")
}

sourceSets["main"].java.srcDir("build/generated/buildinfo")