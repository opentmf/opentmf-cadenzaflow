# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.1] - 2026-08-02

### Added
- SonarCloud analysis in CI: every push/PR to `develop` builds, runs the full test
  suite and pushes the analysis (incl. JaCoCo coverage) to SonarCloud project
  `opentmf_opentmf-cadenzaflow`.
- Release quality reporting: the release image workflow aborts when the SonarCloud
  quality gate on `develop` is not `OK`, and attaches a SonarCloud mini report
  (`sonar-report.md`) plus per-image Trivy HTML reports (`trivy-report.html`,
  `trivy-report-aws.html`) to the GitHub Release page.
- Trivy gate in the release workflow: fixable HIGH/CRITICAL findings fail the image
  build before anything is pushed. Accepted CVEs live in `.trivyignore`, honored by
  both the local `docker` profile scan and the release workflow.

## [1.0.0] - 2026-07-31

### Added
- Initial version of `opentmf-cadenzaflow`.
- Embeds CadenzaFlow 1.2.1 (the maintained Camunda 7 fork) via its Spring Boot 4
  starters (`cadenzaflow-bpm-spring-boot-starter-rest-4` / `-webapp-4`), on
  Spring Boot 4.1.0 / Spring Framework 7 / Java 25.
- Keycloak identity integration via `cadenzaflow-keycloak-4`; API security via
  OpenTMF `openid-rbac-security` 2.2.0 (Spring Boot 4 line).
- Context-closing GraalJS script engine, OAuth2/OIDC SSO for the
  Cockpit/Tasklist/Admin/Welcome webapps incl. SSO-logout plugins, Spin JSON
  plugin, built-in incident-logging engine plugin, nightly history cleanup defaults.
- The `docker` profile Trivy-scans the freshly built image (via the official Trivy
  container, no local install needed); any HIGH or CRITICAL finding fails the build.
