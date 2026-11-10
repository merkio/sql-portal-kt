// In-process HTTP tests over the demo configuration, plus container tests
// (tag "docker", run with -Pdocker) against PostgreSQL, ClickHouse and the image.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    testImplementation(projects.sqlPortal.deployment)
    testImplementation(projects.sqlPortal.adapters)
    testImplementation(projects.sqlPortal.api)
    testImplementation(projects.sqlPortal.testing)
    testImplementation(projects.common.prometheusMetrics)
    testImplementation(libs.koin.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.java)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.clickhouse)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    // The tests read config/ and build the image from the repository root.
    systemProperty("sqlportal.repoRoot", rootDir.absolutePath)
    providers.environmentVariable("SCRIPT_PORTAL_IMAGE").orNull?.let { environment("SCRIPT_PORTAL_IMAGE", it) }
}
