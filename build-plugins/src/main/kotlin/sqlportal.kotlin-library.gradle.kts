// Conventions for every Kotlin module: JVM 21 toolchain, Kotest on JUnit 5,
// ktlint, detekt and JaCoCo.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    jacoco
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.bundles.kotest)
    // KotlinLogging delegates to SLF4J on the JVM; without a provider it logs nothing in tests.
    testRuntimeOnly(libs.slf4j.api)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Container tests (Kotest tag "docker") start PostgreSQL, ClickHouse or the
    // portal image and need Docker or Podman. `-Pdocker` runs them too.
    systemProperty("kotest.tags", if (project.hasProperty("docker")) "" else "!docker")
    // Timestamps with time zone are rendered in the JVM zone; pin it for stable tests.
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
}
