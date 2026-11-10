plugins {
    base
}

allprojects {
    group = "io.merkio.sqlportal"
    version = providers.gradleProperty("version").get()
}
