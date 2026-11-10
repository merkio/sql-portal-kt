// Use cases over the domain ports.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.sqlPortal.domain)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(projects.sqlPortal.testing)
}
