// Postgres dialect on the sql-executor contract. No I/O.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.datasources.sqlExecutor)
}
