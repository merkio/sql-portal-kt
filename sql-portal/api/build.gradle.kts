// Everything HTTP on Ktor: routes, handlers, sessions, views, middleware.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    api(projects.sqlPortal.application)
    implementation(projects.common.templateRender)

    api(libs.ktor.server.core)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.slf4j.api)
    api(libs.micrometer.core)

    testImplementation(projects.sqlPortal.testing)
    testImplementation(libs.ktor.server.test.host)
}
