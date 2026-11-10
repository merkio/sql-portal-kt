plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    // Makes the type-safe `libs` accessor usable inside the convention plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
