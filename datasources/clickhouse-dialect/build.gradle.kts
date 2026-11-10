// ClickHouse dialect: typed server parameters, JSONCompact decoding, endpoints. No I/O.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    api(projects.datasources.sqlExecutor)
}
