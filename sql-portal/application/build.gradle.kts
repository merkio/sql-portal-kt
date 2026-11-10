// Use cases over the domain ports.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.sqlPortal.domain)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(projects.sqlPortal.testing)
}
