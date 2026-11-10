.PHONY: fmt lint test test-docker test-e2e test-docker-clean image-test coverage audit run dist docker

# Image used by the end-to-end tests (PortalImageTest).
SCRIPT_PORTAL_IMAGE ?= script-portal:test
export SCRIPT_PORTAL_IMAGE

GRADLE := ./gradlew

fmt:
	$(GRADLE) ktlintFormat

# ktlint, detekt and the module dependency rules.
lint:
	$(GRADLE) ktlintCheck detekt checkArchitecture

# Unit and in-process integration tests. No Docker needed.
test:
	$(GRADLE) test

# Everything, including tests that start containers (PostgreSQL, ClickHouse,
# the portal image). Requires Docker or Podman (DOCKER_HOST is honoured).
test-docker: image-test
	$(GRADLE) test -Pdocker

# Only the end-to-end checks of the portal image.
test-e2e: image-test
	$(GRADLE) :sql-portal:integration-tests:test -Pdocker --tests '*PortalImageTest'

image-test:
	docker build -t $(SCRIPT_PORTAL_IMAGE) .

# Removes containers left behind by a test run that was killed before cleanup.
test-docker-clean:
	docker ps -aq --filter label=org.sql-portal.integration-test=true | xargs -r docker rm -f

# Line coverage of all modules; fails below COVERAGE_MIN percent (default 70).
coverage:
	$(GRADLE) test jacocoAggregateReport jacocoCoverageVerification
	@echo "Report: build/reports/jacoco/jacocoAggregateReport/html/index.html"

# OWASP dependency check; set NVD_API_KEY to speed up the first run.
audit:
	$(GRADLE) dependencyCheckAnalyze

# APP_ENV selects the bundled environment (local, k8s, uat, prod); local by default.
APP_ENV ?= local
export APP_ENV

run:
	$(GRADLE) :sql-portal:deployment:run

# The distribution (bin/ and lib/) under sql-portal/deployment/build/install/script-portal.
dist:
	$(GRADLE) :sql-portal:deployment:installDist

docker:
	docker build -t script-portal:local .
