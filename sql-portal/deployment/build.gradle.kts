// Composition root and the `script-portal` command: wires every part from the
// configuration with Koin, and owns the runtime classpath (JDBC drivers).
plugins {
    id("sqlportal.kotlin-library")
    application
}

dependencies {
    implementation(projects.sqlPortal.domain)
    implementation(projects.sqlPortal.application)
    implementation(projects.sqlPortal.adapters)
    implementation(projects.sqlPortal.api)
    implementation(projects.common.prometheusMetrics)

    implementation(libs.koin.core)
    implementation(libs.clikt)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // `-Pdatabases=postgres` (or sqlite, or both) packages only those drivers.
    val databases =
        providers
            .gradleProperty("databases")
            .get()
            .split(',')
            .map { it.trim().lowercase() }
    val all = "all" in databases
    if (all || "sqlite" in databases) runtimeOnly(libs.sqlite.jdbc)
    if (all || "postgres" in databases) runtimeOnly(libs.postgresql)

    testImplementation(projects.sqlPortal.testing)
    testRuntimeOnly(libs.sqlite.jdbc)
}

application {
    applicationName = "script-portal"
    mainClass.set("io.merkio.sqlportal.deployment.MainKt")
}
