import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
    jacoco
    alias(libs.plugins.dependency.check)
}

allprojects {
    group = "io.merkio.sqlportal"
    version = providers.gradleProperty("version").get()
}

// ---------------------------------------------------------------- coverage
// One report over every module; `jacocoCoverageVerification` fails below
// COVERAGE_MIN percent of lines (default 70), as in CI.

val coveredProjects =
    subprojects.filter { it.path !in setOf(":sql-portal:testing", ":sql-portal:integration-tests") }

val coverageMin = providers.environmentVariable("COVERAGE_MIN").orElse("70").map { it.toBigDecimal().movePointLeft(2) }

val coverageExecutionData =
    files(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") }).filter { it.exists() }

fun JacocoReportBase.coverageInputs() {
    dependsOn(subprojects.map { "${it.path}:test" })
    executionData(coverageExecutionData)
    sourceDirectories.from(coveredProjects.map { it.file("src/main/kotlin") })
    classDirectories.from(
        coveredProjects.map { p ->
            p.layout.buildDirectory.dir("classes/kotlin/main").map { dir ->
                // The CLI entry point is exercised by the container tests, not unit tests.
                fileTree(dir) { exclude("**/deployment/MainKt*", "**/deployment/Cli*", "**/deployment/Serve*") }
            }
        },
    )
}

val jacocoAggregateReport = tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Line coverage of all modules."
    coverageInputs()
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val jacocoCoverageVerification = tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "verification"
    description = "Fails when line coverage is below COVERAGE_MIN percent."
    coverageInputs()
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = coverageMin.get()
            }
        }
    }
}

// ------------------------------------------------------- dependency audit

dependencyCheck {
    // CI passes an NVD API key; without one the first run downloads slowly.
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
    failBuildOnCVSS = 7.0f
    suppressionFile = "config/dependency-check-suppressions.xml"
    scanConfigurations = listOf("runtimeClasspath")
}

// --------------------------------------------------- architecture rules
// The layer rules of docs/ARCHITECTURE.md, checked on the declared
// (non-test) dependencies of every module.

private val productionConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")

val dependencyGraph =
    provider {
        subprojects.associate { p ->
            p.path to
                p.configurations
                    .filter { it.name in productionConfigurations }
                    .flatMap { it.dependencies }
                    .map { d -> if (d is ProjectDependency) d.path else "${d.group}:${d.name}" }
                    .toSortedSet()
        }
    }

val checkArchitecture = tasks.register("checkArchitecture") {
    group = "verification"
    description = "Checks the module dependency rules of the clean architecture."
    val graph = dependencyGraph
    inputs.property("graph", graph)
    doLast {
        val deps: Map<String, Set<String>> = graph.get()
        val violations = mutableListOf<String>()

        fun internal(project: String) = deps.getValue(project).filter { it.startsWith(":") }.toSet()

        fun within(
            project: String,
            vararg allowed: String,
        ) {
            val extra = internal(project) - allowed.toSet()
            if (extra.isNotEmpty()) violations += "$project must not depend on $extra (allowed: ${allowed.toList()})"
        }

        fun forbid(
            project: String,
            vararg groups: String,
        ) {
            val found = deps.getValue(project).filter { d -> groups.any { d.startsWith("$it:") } }
            if (found.isNotEmpty()) violations += "$project must not use $found"
        }

        val infrastructure =
            arrayOf(
                "io.ktor", "com.zaxxer", "org.postgresql", "org.xerial", "com.unboundid", "com.nimbusds",
                "io.micrometer", "org.freemarker", "io.insert-koin", "ch.qos.logback", "com.typesafe",
                "org.bouncycastle", "org.slf4j", "org.jetbrains.kotlinx:kotlinx-coroutines",
            )

        // Domain: model and ports only.
        within(":sql-portal:domain", ":datasources:sql-executor")
        forbid(":sql-portal:domain", *infrastructure)
        // Application: use cases over the domain.
        within(":sql-portal:application", ":sql-portal:domain")
        forbid(":sql-portal:application", *infrastructure.filter { it != "org.slf4j" && !it.contains("coroutines") }.toTypedArray())
        // Adapters implement domain ports over the datasources; no HTTP server.
        within(
            ":sql-portal:adapters",
            ":sql-portal:domain",
            ":datasources:sql-executor",
            ":datasources:sqlite-dialect",
            ":datasources:postgres-dialect",
            ":datasources:clickhouse-dialect",
            ":common:tls-config",
        )
        forbid(":sql-portal:adapters", "io.ktor:ktor-server", "io.insert-koin", "org.freemarker")
        // The API knows use cases, not adapters or drivers.
        within(":sql-portal:api", ":sql-portal:domain", ":sql-portal:application", ":common:template-render")
        forbid(":sql-portal:api", "com.zaxxer", "org.postgresql", "org.xerial", "com.unboundid", "com.nimbusds", "io.insert-koin")
        // Fakes see only the domain.
        within(":sql-portal:testing", ":sql-portal:domain")
        // Datasources and shared libraries know nothing of the portal.
        within(":datasources:sql-executor")
        for (d in listOf("sqlite", "postgres", "clickhouse")) within(":datasources:$d-dialect", ":datasources:sql-executor")
        for (c in listOf("tls-config", "prometheus-metrics", "template-render")) within(":common:$c")
        // Deployment wires every layer, and nothing but tests depends on it or on the API.
        val wired = internal(":sql-portal:deployment")
        for (layer in listOf(":sql-portal:domain", ":sql-portal:application", ":sql-portal:adapters", ":sql-portal:api")) {
            if (layer !in wired) violations += ":sql-portal:deployment must wire $layer"
        }
        for ((project, projectDeps) in deps) {
            if (project != ":sql-portal:integration-tests" && ":sql-portal:deployment" in projectDeps) {
                violations += "$project must not depend on :sql-portal:deployment"
            }
            if (project !in setOf(":sql-portal:deployment", ":sql-portal:integration-tests") && ":sql-portal:api" in projectDeps) {
                violations += "$project must not depend on :sql-portal:api"
            }
            if (project !in setOf(":sql-portal:deployment", ":sql-portal:integration-tests") && ":sql-portal:testing" in projectDeps) {
                violations += "$project must use :sql-portal:testing only in tests"
            }
        }
        if (violations.isNotEmpty()) throw GradleException("Architecture rules violated:\n  " + violations.joinToString("\n  "))
        logger.lifecycle("Architecture rules hold for ${deps.size} modules.")
    }
}

tasks.check {
    dependsOn(checkArchitecture)
}
