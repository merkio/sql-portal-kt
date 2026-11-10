// Adds kotlinx.serialization to a Kotlin module.
plugins {
    id("sqlportal.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
