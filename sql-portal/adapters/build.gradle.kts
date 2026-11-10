// Port implementations: the domain QueryExecutor over the datasources, LDAP, OIDC, grants service.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    api(projects.sqlPortal.domain)
    api(projects.common.tlsConfig)
    implementation(projects.datasources.sqlExecutor)
    implementation(projects.datasources.sqliteExecutor)
    implementation(projects.datasources.postgresExecutor)
    implementation(projects.datasources.clickhouseExecutor)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
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
