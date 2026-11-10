// Composition root and the entry point: loads the configuration
// (bundled default + environment + optional file), wires every part with Koin,
// and owns the runtime classpath (JDBC drivers).
plugins {
    id("sqlportal.kotlin-serialization")
    application
}

dependencies {
    implementation(projects.sqlPortal.domain)
    implementation(projects.sqlPortal.application)
    implementation(projects.sqlPortal.adapters)
    implementation(projects.sqlPortal.api)
    implementation(projects.common.prometheusMetrics)

    implementation(libs.koin.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.hocon)
    implementation(libs.typesafe.config)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(platform(libs.jackson.bom))

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

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

application {
    applicationName = "script-portal"
    mainClass.set("io.merkio.sqlportal.deployment.MainKt")
    // KotlinLogging would print its own banner to stdout before the first log line.
    applicationDefaultJvmArgs = listOf("-Dkotlin-logging.logStartupMessage=false")
}
