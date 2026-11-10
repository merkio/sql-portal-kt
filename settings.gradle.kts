// Composite build: the convention plugins come from the included build
// `build-logic`; library versions from gradle/libs.versions.toml.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "sql-portal-kt"

// Shared libraries, independent of the portal.
include(":common:tls-config")
include(":common:prometheus-metrics")
include(":common:template-render")

// SQL dialects (no I/O).
include(":datasources:sql-executor")
include(":datasources:sqlite-dialect")
include(":datasources:postgres-dialect")
include(":datasources:clickhouse-dialect")

// The application.
include(":sql-portal:domain")
include(":sql-portal:application")
include(":sql-portal:adapters")
include(":sql-portal:api")
include(":sql-portal:deployment")
include(":sql-portal:testing")
include(":sql-portal:integration-tests")
