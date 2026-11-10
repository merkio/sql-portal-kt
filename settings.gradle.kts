// Composite build: the convention plugins come from the included build
// `build-plugins`; library versions from gradle/libs.versions.toml.
pluginManagement {
    includeBuild("build-plugins")
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

// Datasources: the executor contract, and one module per database (dialect + executor).
include(":datasources:sql-executor")
include(":datasources:jdbc-executor")
include(":datasources:sqlite-executor")
include(":datasources:postgres-executor")
include(":datasources:clickhouse-executor")

// The application.
include(":sql-portal:domain")
include(":sql-portal:application")
include(":sql-portal:adapters")
include(":sql-portal:api")
include(":sql-portal:deployment")
include(":sql-portal:testing")
include(":sql-portal:integration-tests")
