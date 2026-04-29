plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("com.google.devtools.ksp") version "2.3.6"
    kotlin("plugin.serialization") version "2.3.10"
    id("io.ktor.plugin") version "3.4.0"
}

group = "eu.darken"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.dagger:dagger:2.59.2")
    ksp("com.google.dagger:dagger-compiler:2.59.2")

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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.io.path.ExperimentalPathApi")
    }
}

// Synthetic data dir generator for the regression-synthetic-replay CI job. Runs against the
// test classpath so it can use SyntheticDataFixture + BlobFixtures. Pass the output dir (and
// optional account count) via -PsyntheticOutputDir / -PsyntheticAccountCount.
tasks.register("generateSyntheticData", JavaExec::class) {
    group = "regression"
    description = "Generate a synthetic data tree for the cross-version regression CI job"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eu.darken.octi.server.regression.SyntheticDataFixture")
    val outputDir = project.findProperty("syntheticOutputDir") as String?
        ?: layout.buildDirectory.dir("regression-synthetic").get().asFile.absolutePath
    val accountCount = project.findProperty("syntheticAccountCount") as String? ?: "500"
    args = listOf(outputDir, accountCount)
}

tasks.register("generateBuildInfo") {
    val outputFile = layout.buildDirectory.file("generated/buildinfo/BuildInfo.kt")
    outputs.file(outputFile)

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

        val outputDir = outputFile.get().asFile.parentFile
        outputDir.mkdirs()
        val content = """
            package eu.darken.octi.server

            object BuildInfo {
                const val GIT_SHA: String = "$gitSHA"
                const val GIT_DATE: String = "$gitDate"
            }
        """.trimIndent()
        outputFile.get().asFile.apply {
            if (!exists() || readText() != content) {
                writeText(content)
            }
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateBuildInfo")
}

tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn("generateBuildInfo")
}

sourceSets["main"].java.srcDir("build/generated/buildinfo")
