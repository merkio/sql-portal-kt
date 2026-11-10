// Prometheus registry with caller-supplied histogram buckets. No portal code.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(libs.micrometer.prometheus)
}
