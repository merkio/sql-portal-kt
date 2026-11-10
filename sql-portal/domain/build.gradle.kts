// Model and ports. No web, database or identity-provider libraries.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    implementation(projects.datasources.sqlExecutor)
}
