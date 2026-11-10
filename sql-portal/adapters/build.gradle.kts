// Port implementations: SQL executors, LDAP, OIDC, grants service, configuration.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    api(projects.sqlPortal.domain)
    api(projects.common.tlsConfig)
    implementation(projects.datasources.sqlExecutor)
    implementation(projects.datasources.sqliteDialect)
    implementation(projects.datasources.postgresDialect)
    implementation(projects.datasources.clickhouseDialect)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    api(libs.typesafe.config)
    implementation(libs.hikari)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.unboundid.ldapsdk)
    implementation(libs.nimbus.jose.jwt)

    // JDBC drivers are runtime choices of the deployment; the tests need them.
    testRuntimeOnly(libs.sqlite.jdbc)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
}
