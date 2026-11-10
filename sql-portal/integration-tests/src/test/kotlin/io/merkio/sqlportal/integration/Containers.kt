/*
 * Containers for the tests tagged "docker" (run with `./gradlew test -Pdocker`).
 * They work with Docker or Podman (DOCKER_HOST is honoured). Every container
 * carries the label org.sql-portal.integration-test=true and is removed by its
 * spec, also when a test fails; `make test-docker-clean` removes leftovers of
 * killed runs.
 */
package io.merkio.sqlportal.integration

import io.kotest.core.Tag
import org.testcontainers.containers.GenericContainer

val Docker = Tag("docker")

const val TEST_LABEL = "org.sql-portal.integration-test"

fun <T : GenericContainer<*>> T.labelled(): T = apply { withLabel(TEST_LABEL, "true") }

/** The portal image under test. */
val portalImage: String = System.getenv("SCRIPT_PORTAL_IMAGE") ?: "script-portal:test"
