// Shared JDBC execution: reused plain connections, statement timeouts, error mapping.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.datasources.sqlExecutor)
    implementation(libs.kotlinx.coroutines.core)
}
