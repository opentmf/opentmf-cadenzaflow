# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Initial version of `opentmf-cadenza-flow`, derived from `opentmf-camunda7` 24.0.7-SNAPSHOT.
- Embeds CadenzaFlow 1.2.0 (the maintained Camunda 7 fork) via its Spring Boot 4
  starters (`cadenzaflow-bpm-spring-boot-starter-rest-4` / `-webapp-4`), on
  Spring Boot 4.0.7 / Spring Framework 7 / Java 25.
- Keycloak identity integration via `cadenzaflow-keycloak`; API security via
  OpenTMF `openid-rbac-security` 2.1.0 (Spring Boot 4 line).
- Carried over from opentmf-camunda7: context-closing GraalJS script engine
  (polyglot context leak fix), OAuth2/OIDC SSO for the Cockpit/Tasklist/Admin/Welcome
  webapps incl. SSO-logout plugins, Spin JSON plugin, nightly history cleanup defaults.

### Changed
- Java package renamed `org.opentmf.camunda` → `org.opentmf.cadenzaflow`; Maven
  coordinates are now `org.opentmf.cadenzaflow:opentmf-cadenza-flow`.
- Engine configuration prefix follows the fork: `camunda.bpm.*` → `cadenzaflow.bpm.*`
  (file renamed to `config-cadenzaflow.yml`); default context path `/camunda/v7` →
  `/cadenzaflow/v1`; default schema/user `camunda7` → `cadenzaflow`.
- `camunda7-incident-logger` library replaced by a built-in incident-logging engine
  plugin (`org.opentmf.cadenzaflow.config.incident`): the 2.x (Spring Boot 4) line of
  the old library is built against CIB seven, which is binary-incompatible with
  CadenzaFlow's renamed packages, and the logic is two small CadenzaFlow-specific
  classes - not worth a separate library. Incidents without an execution id (e.g.
  from process instance version migrations) are not logged.
