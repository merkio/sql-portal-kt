// ClickHouse: dialect and executor over the HTTP interface (JDK HTTP client).
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    api(projects.datasources.sqlExecutor)
    api(projects.common.tlsConfig)
    implementation(libs.kotlinx.coroutines.core)
}
