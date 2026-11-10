// In-memory fakes of the ports. Used only as a test dependency.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    api(projects.sqlPortal.domain)
}
