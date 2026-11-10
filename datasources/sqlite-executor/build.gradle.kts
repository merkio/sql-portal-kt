// SQLite: dialect and JDBC executor. The driver is a runtime choice of the application.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.datasources.sqlExecutor)
    implementation(projects.datasources.jdbcExecutor)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.sqlite.jdbc)
}
