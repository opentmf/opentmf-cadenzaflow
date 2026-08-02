# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.3] - 2026-08-02

### Security
- Netty upgraded from 4.2.15.Final (Spring Boot 4.1.0 BOM) to 4.2.16.Final via a
  `netty-bom` import: fixes CVE-2026-59901, CVE-2026-55831, CVE-2026-55833,
  CVE-2026-56745 and CVE-2026-56819 (all HIGH, DoS in netty codecs). Only the
  `-aws` image is affected — Netty comes in through the AWS SDK's async HTTP
  client in the `aws-iam` profile. These CVEs blocked the 1.0.2 `-aws` image at
  the release Trivy gate, so no `1.0.2-aws` image exists; `-aws` consumers should
  move from `1.0.1-aws` (which contains the vulnerable Netty) to `1.0.3-aws`.

## [1.0.2] - 2026-08-02

No functional changes over 1.0.1. Re-release to restore the GitHub release page:
1.0.1 was published while GitHub's release immutability was enabled, which
permanently blocked attaching the Sonar/Trivy quality reports to (or recreating)
that release. The 1.0.1 artifacts themselves (Maven Central jar, signed GHCR
images) shipped correctly.

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
