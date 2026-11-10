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

# Known vulnerabilities (HIGH, CRITICAL) in the jars of the distribution, with
# Trivy in a container: no account or API key needed. Reviewed exceptions go to
# config/trivyignore. `:z` lets SELinux hosts (Podman) share the files.
TRIVY_IMAGE ?= docker.io/aquasec/trivy:0.70.0
audit:
	$(GRADLE) :sql-portal:deployment:installDist
	docker run --rm \
	  -v $(CURDIR)/sql-portal/deployment/build/install/script-portal/lib:/app/lib:ro,z \
	  -v $(CURDIR)/config/trivyignore:/trivyignore:ro,z \
	  -v trivy-cache:/root/.cache \
	  $(TRIVY_IMAGE) rootfs --no-progress --scanners vuln --severity HIGH,CRITICAL \
	  --ignorefile /trivyignore --exit-code 1 /app/lib

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
