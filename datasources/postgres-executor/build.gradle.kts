// PostgreSQL: dialect and JDBC executor. The driver is a runtime choice of the application.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.datasources.sqlExecutor)
    api(projects.common.tlsConfig)
    implementation(projects.datasources.jdbcExecutor)

    testRuntimeOnly(libs.postgresql)
}
