# Component Design Card — opentmf-cadenzaflow

The internal design record. Its companion is [../README.md](../README.md), which is
the self-contained front door for users and testers: the use cases, requirements,
validation rules, processing-logic cards, ACL and data model live **there**, and
this card links into them rather than restating them.

Both documents are readable without access to any other repository.

---

## 1. Component Overview

A BPMN workflow engine service: the CadenzaFlow community edition BPM platform (the
maintained Camunda 7 fork) packaged as a Spring Boot 4 microservice, with
token-based API security, OIDC single sign-on for the web UIs, and a directory-backed
identity provider in place of a local user store.

Provides:

- the engine REST API under `/engine-rest` (deployments, definitions, instances,
  tasks, variables, history, batches);
- the Cockpit / Tasklist / Admin / Welcome web UIs behind SSO;
- an external-task surface for worker services;
- Prometheus metrics, health probes and incident logging on a separate management
  port.

Positioning: it is the **process-state owner** for whatever platform embeds it.
Business work runs in worker services, not here. It is the successor of
`opentmf-camunda7` and is schema-compatible with one.

---

## 2. Component Description Card

| Property | Value |
|---|---|
| Name | `opentmf-cadenzaflow` (groupId `org.opentmf.cadenzaflow`) |
| Provided API Specification(s) | [`docs/openapi.yaml`](openapi.yaml) — the service's own surface (security model, whitelisted paths, management endpoints). The engine REST API under `/engine-rest` is the upstream CadenzaFlow contract and is not duplicated. See [§11.1](#111-api-contract-specification) |
| Consumed API(s) | The identity provider's directory API: Microsoft Graph (`User.Read.All`, `GroupMember.Read.All`) or the Keycloak Admin REST API. The providers' OIDC endpoints (authorize, token, JWKS, userinfo, end-session) |
| Database Dependency | PostgreSQL. The engine owns the schema (`ACT_*`) and creates/patches it at startup. The service defines no tables of its own — [README §7](../README.md#7-data) |
| Component handled Workflow(s) | n.a. as *shipped content* — the service ships no BPMN. It **executes** the models its operators deploy, which is the point of it |
| Other Dependencies | `openid-rbac-security` (token validation, RBAC), `cadenzaflow-keycloak-4` / `cadenzaflow-entra-identity-4` (identity providers), GraalJS (script tasks), Spin (large variables) |
| Supported Events | n.a. — no message broker. Its event surface is the external-task protocol over REST ([README §6.3](../README.md#63-uc-10--uc-13--a-worker-runs-an-external-task)) |

---

## 3. (UC) Use Cases Summary

See [README §4](../README.md#4-use-cases): web-service driven (§4.1), event driven
(§4.2), workflow driven (§4.3), browser driven (§4.4), out of scope (§4.5).

---

## 4. (FR), (BVR)

See [README §5](../README.md#5-requirements-and-validation-rules): functional
requirements (§5.1), business validation rules with their PASCAL short names and
error responses (§5.2), out of scope (§5.3).

---

## 5. Workflow / Process Design

**5.1 Main:** n.a. — the component *is* the workflow runtime; it ships no process
definitions of its own, so there is nothing to diagram here. The runtime
interactions that would otherwise live in this section (how an instance advances
through an external task, how history is cleaned) are the sequence flows in
[README §6](../README.md#6-processing-logic).

**5.2 Sub:** n.a., same reason.

---

## 6–9. Processing Logic

| § | Scope | Where |
|---|---|---|
| 6 | Web-service use cases | [README §6.1](../README.md#61-uc-05--uc-07--a-rest-call-is-authorized) — authorization of a REST call, with its sequence diagram |
| 7 | Event use cases | [README §6.3](../README.md#63-uc-10--uc-13--a-worker-runs-an-external-task) — the external-task protocol |
| 8 | Workflow tasks | [README §6.4](../README.md#64-uc-22--history-cleanup) — history cleanup, the only workflow-task-like job the service owns. Script-task execution is described in [README §9.6](../README.md#96-javascript-script-tasks) |
| 9 | Common referenced functionality | [README §6.2](../README.md#62-uc-30--uc-31--a-person-signs-in-to-cockpit) — SSO login and directory resolution; [README §6.5](../README.md#65-common--startup-trust-validation) — startup trust validation |

---

## 10. (NFR)

### 10.1 CONF

Configuration is environment variables only; no configuration is compiled in that
cannot be overridden. The full deployer-facing reference is
[README §8](../README.md#8-configuration-reference). Fragment layout is
[§11.6](#116-application-config-file).

### 10.2 SEC / ACL

**10.2.1 Security requirements**

| Req. No | Requirement |
|---|---|
| SEC-01 | No hand-written security filter chain — the security posture is declarative configuration, reviewable as data |
| SEC-02 | Unmatched application-port paths are DENIED by default; opening a path is an explicit act |
| SEC-03 | The management port is governed by its own rules and is never published through an Ingress |
| SEC-04 | An ambiguous or duplicated trust declaration fails the boot rather than starting with an undefined trust set (BVR-09) |
| SEC-05 | Expected audiences are validated wherever one issuer serves many applications (Entra) |
| SEC-06 | Attribution comes from the validated token, never from a request field |
| SEC-07 | Secrets arrive as ordinary properties from the deployer; none are committed, and CI runs a secret scan on every push |
| SEC-08 | Cluster logs are masked at the source ([README §9.2](../README.md#92-logs)) |

**10.2.2 ACL**

The human-readable projection of `config-security.yml` is
[README §3.3](../README.md#33-access-control-list). Administrators, via the engine's
own authorization layer and the configured administrator group, reach every engine
resource.

### 10.3 Performance

| Req. No | Aspect | Value |
|---|---|---|
| PERF-01 | Directory reads | Lazy and cached; no background synchronization, so startup does not depend on directory size |
| PERF-02 | Worker polling | Long polling, so idle workers cost a parked connection rather than a request loop |
| PERF-03 | JWKS retrieval | Cached per issuer; token validation performs no network call in steady state |
| PERF-04 | Script tasks | Interpreted (a GraalVM JDK would be needed for JIT); each context is closed after use, so heap stays flat under script load |

### 10.4 Scalability

Stateless pods over a shared database; every pod is one engine cluster member.
Horizontal scaling is adding pods. The API/job-executor split
([README §9.5](../README.md#95-scaling-separate-job-execution-from-api-traffic))
scales interactive traffic and background work independently. The binding
constraint is the database connection budget, not CPU.

### 10.5 Reliability and availability

Job acquisition goes through the database, so pods never duplicate work and a lost
pod's jobs are re-acquired by others. Failed jobs follow the retry cycle and end in
an incident, which is logged (FR-06) and counted
(`cadenzaflow.engine.incidents.open`). Graceful shutdown is on. Liveness and
readiness are separate probes.

### 10.6 O&AM

Prometheus metrics and health on port 16000; runtime log-level changes through
`/actuator/loggers`; effective configuration through `/actuator/env` behind the
`admin` role. Operational procedures — history cleanup, backfilling removal times,
reading incidents — are [README §9](../README.md#9-operating-the-service).

### 10.7 Portability

Runs on any Kubernetes with a PostgreSQL. Cloud-specific credentials are isolated
in the `aws` and `azure` build profiles and their image flavours; the base image
carries no cloud jars, and cloud infrastructure auth is independent of which
identity provider is configured.

### 10.8 Out of scope

Rate limiting, quotas, multi-tenancy isolation beyond engine authorization, and
message-broker integration. See [README §10](../README.md#10-out-of-scope).

---

## 11. Component Design Specification Details

### 11.1 API Contract Specification

[`docs/openapi.yaml`](openapi.yaml) (OpenAPI 3.1), hand-maintained, versioned
independently of the project version. It documents the service's own surface; the
engine REST API beneath `/engine-rest` belongs to the upstream platform.

### 11.2 Workflow Diagram(s)

n.a. — no `.bpmn` files are shipped ([§5](#5-workflow--process-design)).

### 11.3 Database Tables

The ERD and the two facts that matter when reading the schema (`ACT_RU_*` vs
`ACT_HI_*`; `REMOVAL_TIME_` drives cleanup) are
[README §7](../README.md#7-data).

The service issues no DDL of its own: `cadenzaflow.bpm.database.schema-update`
lets the engine create and patch its tables. There is therefore no migration tool
in this repo, and no per-table column catalogue here — the tables are the upstream
platform's contract, not this component's design surface.

### 11.4 INPUT Files

n.a. — no file ingestion. The only file-shaped inputs are BPMN/DMN deployments,
which arrive over the REST API.

### 11.5 OUTPUT Files

n.a. — no file production.

### 11.6 Application Config File

`application.yml` holds Spring-owned blocks only and imports the rest:

| Fragment | Owns |
|---|---|
| `application.yml` | datasource, server/context path, management port, graceful shutdown |
| `config-cadenzaflow.yml` | engine behaviour (`cadenzaflow.bpm.*`), identity-provider selection and settings, the webapp OIDC client registration, the webapp content-security-policy |
| `config-security.yml` | `opentmf.security.*` — token validation, whitelists, per-endpoint roles, the separate management-port block |
| `logback-spring.xml` | log layout: readable console locally, masked JSON in a cluster |
| `logback-masking.xml` | the masked `JSON` appender, in its own file so an operator-mounted logback config gets identical masking with a one-line include |

Every block carries a WHY comment; the fragments are meant to read as design
documents.

---

## 12. Sample API Payloads

Worked request/response examples — obtaining a token from either provider, starting
an instance, the external-task exchange — are
[README §3.2](../README.md#32-getting-a-token-and-calling-the-api) and
[README §6.3](../README.md#63-uc-10--uc-13--a-worker-runs-an-external-task).

---

## 13. Other

### 13.1 Module layout

Single module. The application is thin by design — the engine is a dependency, not
something this repo re-implements.

```
org.opentmf.cadenzaflow
  OpenTmfCadenzaFlowApplication
  config/
    CadenzaFlowConfiguration, JerseyConfig
    KeycloakIdentityProvider, EntraIdentityProvider   # the two identity adapters
    incident/    # the incident-logging engine plugin
    metrics/     # EngineMetricsBridge - engine counters as Micrometer meters
    script/      # the context-closing GraalJS engine
    sso/         # OAuth2 login, token filter, SSO-logout webapp plugins
```

There is no `controller`/`service`/`repository` triad: the service exposes no REST
resources of its own and owns no entities. The one architectural boundary worth
enforcing is that provider-specific identity libraries stay inside the two adapters
in `config` — an ArchUnit rule enforces exactly that, so the rest of the code cannot
quietly become Keycloak-only or Entra-only.

### 13.2 Build, profiles and CI

| Profile | Purpose |
|---|---|
| `release` | source + javadoc jars, GPG signing, Central publishing |
| `repackage` | fat jar |
| `docker` | local image on `package`, Trivy scan immediately after (any HIGH/CRITICAL fails), image removed on `clean` |
| `sonar` | scan against a developer's local SonarQube |
| `mutation` | PIT mutation testing on demand, unit tests only |
| `aws` / `azure` | cloud runtime jars for the `-aws` / `-azure` image flavours |

Gates: JaCoCo 80/80/80 with zero missed classes, halting the build; ArchUnit in
`mvn test`; gitleaks on every push; SonarCloud on every push and as an aborting gate
on release.

The release pipeline builds a **flavour × architecture** matrix
(base/aws/azure × amd64/arm64). Each leg pushes an untagged by-digest image and is
Trivy-gated on *its own* image; a per-flavour merge job assembles the tagged
manifest list and then signs **that index digest** keylessly with cosign and
attaches a CycloneDX SBOM attestation. The arm64 leg builds QEMU-emulated on an
amd64 runner; going native is a matrix runner-label change.

Third-party actions are pinned by full commit SHA — a mutable tag would let a
compromised action release run with the workflow's `packages: write` and
`id-token: write` tokens, which would make the rest of this posture decorative.

### 13.3 Deviations from the DNMS engineering standard

The service is deployed by the DNMS platform and follows its engineering standard
where the standard applies. It is **not** a `dnms-*` service — it is an OpenTMF
product with its own repository, release cadence and public users — so a number of
rules are inapplicable by construction and a few are deliberate exceptions. Both
kinds are recorded here rather than left as silent gaps.

**Inapplicable by construction**

| Rule area | Why it does not apply |
|---|---|
| groupId / package `com.dnext.dnms.*`, `dnms-*` artifact naming | Different product, different organization: `org.opentmf.cadenzaflow` |
| Repo under `pia-team`, `develop` default branch | Public repo under `opentmf`, default branch `main` |
| Layered package structure, contract-first controllers, DTO and exception families, `@RestControllerAdvice` | The service exposes no REST resources of its own; the API is the embedded engine's |
| Liquibase, JPA/QueryDSL, entity and DB-design conventions | It owns no schema — the engine creates and patches its tables |
| Kafka, transactional outbox, JSLT transformations | No broker, no dual writes, no mappings |
| "Never embed a Camunda engine — external-task client only" | This *is* the engine that rule points other services at |
| `opentmf-versions` BOM, PIA Nexus distribution, `ci/settings.xml` | Published to Maven Central, and it consumes the CadenzaFlow platform BOM instead |
| Logbook (`logbook-spring-boot-starter`) for HTTP wire logging | The engine ships its own request/response logging (Jersey's `LoggingFeature`), off at INFO and raised to DEBUG for troubleshooting. A second wire logger over the same traffic would duplicate it, not extend it — see the note under deliberate exceptions |
| Lombok, `@Slf4j` | No Lombok; loggers are explicit SLF4J fields, and the ArchUnit rule enforces the `log` name that `@Slf4j` would have produced |

**Deliberate exceptions**

| Rule | Deviation | Why |
|---|---|---|
| Coverage gate 90/90/90 | **80/80/80**, zero missed classes, halting | The code is adapter and configuration glue around a large third-party engine; the last ten points would be bought with tests that assert framework wiring rather than behaviour. Zero missed classes is kept — every class is exercised |
| Surefire/Failsafe `reuseForks=true` | Failsafe runs `reuseForks=false` | Each IT boots its own engine, which registers a JVM-global MBean; cached contexts in a shared fork collide. A comment in the pom records this |
| `junit-platform.properties` lifecycle `per_class` | Removed | It broke four tests that assume a fresh instance. The file records the reason |
| Roles `dnms-read` / `dnms-write` / `dnms-admin` | Ships `reader` / `writer` / `admin` | A product consumed outside this platform cannot ship a platform's role vocabulary. The names are configuration, and the DNMS deployment maps them onto the platform's — that mapping lives in `dnms-deploy`'s service values, which is where a platform vocabulary belongs |
| Diagrams as PlantUML | **Mermaid** | The README's audience reads it on GitHub, which renders Mermaid natively and PlantUML not at all. Confluence copy-paste compatibility applies to this card, which carries no diagrams |
| Micrometer tracing over OTLP to a local collector | **Not adopted** | It would put an OTLP exporter, and its error noise, into every deployment of a product that mostly runs where no collector exists. The metrics half of the observability standard *is* adopted, including logical engine meters. Revisit when a deployment asks for spans; the DNMS reference service has not adopted it either |
| `spring.threads.virtual.enabled: true` | **Not adopted** | An engine-wide threading change to a third-party BPM runtime, unproven under its job executor and its ThreadLocal-based engine context. It is a runtime property, so a deployment can enable and measure it without a rebuild |
| Config keys carry no provider name | `KEYCLOAK_URL_AUTH` names Keycloak but is the browser-facing authorization host for **any** provider | Renaming it would break every existing deployment's configuration for a cosmetic gain. Documented at the point of use in [README §8.6](../README.md#86-web-ui-single-sign-on) |
| Spring Boot inherited through `<parent>` (the estate revised *away* from BOM import) | **BOM import**, no parent | Verified to make no difference to the build output here: the two things the parent would contribute that change bytecode or packaging — `-parameters` and the `@`-only resource-filtering delimiters — are already the state this pom produces (`-parameters` OFF is what the §5 explicit-binding-name rule requires, and the delimiters are declared explicitly). What the parent would add is convenience this pom pays for in a few explicit lines: plugin versions and the repackage wiring. Two real costs, accepted: `versions:display-parent-updates` has nothing to report, and a BOM-managed version override needs an explicit pin rather than a property (which is what the PostgreSQL CVE override does). Revisit if the OpenTMF product line moves to the parent |
| Logbook + `secure-filter` masking of HTTP wire logs | Not adopted | The engine already logs request/response through Jersey, **off by default**, so there is no always-on wire log to mask — which is the risk Logbook's `secure-filter` exists to cover. Partial coverage remains when an operator raises it to DEBUG: those lines pass through the masked JSON encoder, so the value-regex masks (IBAN, phone, long digit runs) apply to dumped bodies, while the field-name masks do not, because the body is message text rather than JSON structure. The operational rule is therefore: **DEBUG wire logging is a troubleshooting mode, not a production setting** |
| Custom 401/403 `ProblemDetail` bodies (two consumer beans) | Not adopted — security errors keep the library's RFC 6750 defaults | The callers of this API are engine REST clients and external-task workers, which key on the status code and the `WWW-Authenticate` challenge, not on a problem document. Adding the beans would change the error body for every existing integration to buy consistency with services this one does not sit beside. The status codes are the contract, and they are specified in [README §5.2](../README.md#52-business-validation-rules) |

### 13.4 PIT baseline (2026-08-12)

First real mutation scores, recorded so the next survivor review has something to
compare against rather than starting from nothing. Run on the 1.1.0 candidate with
the on-demand `mutation` profile; no threshold is enforced - PIT is an instrument,
not a gate.

| Scope | Line coverage | Mutation coverage |
|---|---|---|
| **Overall** | 304/381 (80%) | **103/151 killed (68%)**, test strength 84% |
| `config.metrics` | 20/20 | 4/4 |
| `config.sso.plugin.*` | 3/3 each | 2/2 each |
| `config.incident` | 31/33 | 5/7 |
| `config.script` | 137/139 | 53/69 (77%) |
| `config.sso` | 104/169 | 33/62 (53%) |
| `config` | 0/8 | 0/1 |

Two things this says that line coverage does not. **28 mutations had no test
coverage at all**, so the 94.8% reported by JaCoCo is flattering in places. And
`config.sso` - the security-critical package, holding token filtering, logout and
the OAuth2 wiring - is the weakest at 53%: its lines are executed by tests that do
not assert on the resulting behaviour. Reviewed and accepted for 1.1.0 (product
owner, 2026-08-12); it is the obvious place to spend the next testing effort.

Two mechanical notes for whoever runs this next:

- **The `mutation` profile sets `targetTests` but no `targetClasses`**, so PIT
  mutates everything including configuration and wiring. That is why the run
  produced repeated `Minion exited abnormally due to TIMED_OUT` - it drives tests
  that boot an in-memory engine and GraalJS contexts. The standard asks for
  `targetClasses` scoped to the logic packages; scoping it here is awkward because
  nearly every class lives under `config`.
- **Before re-judging a survivor after strengthening a test, delete the history
  file** (`$TEMP/<coords>_pitest_history.bin`). It is keyed on mutated-class
  hashes only, so a better test does not invalidate the entry and the survivor
  keeps reporting as SURVIVED.

### 13.4 Decision history

| Date | Decision |
|---|---|
| 2026-07-31 | 1.0.0 released: the CadenzaFlow platform on Spring Boot 4 / Java 25, replacing `opentmf-camunda7` |
| 2026-08-02 | 1.0.1 – 1.0.3 released from `develop`: SonarCloud in CI with release quality reporting, a Trivy gate on the release images, and a Netty override closing five HIGH CVEs that had blocked the `-aws` image |
| 2026-08-05 | Multi-issuer token validation adopted, so one deployment can serve two providers on the same endpoints |
| 2026-08-07 | Entra ID identity provider added beside Keycloak, selected by one property, with the engine still mounting exactly one |
| 2026-08-08 | The webapp SSO registration id made provider-neutral (`oidc`): it is user-visible, and `keycloak` advertised the wrong provider on an Entra deployment |
| 2026-08-11 | Adapted to the DNMS engineering standard where applicable: coverage gate made halting, ArchUnit suite extended, masked JSON logging, split release pipeline with per-architecture Trivy gating and signing of the merged index, and this two-artifact documentation split |
| 2026-08-11 | Annotation processing requested explicitly (`<proc>full</proc>`). Java 23 disabled implicit processing, so `spring-boot-configuration-processor` had been silently skipped and no configuration metadata was produced; 50 properties across four groups are generated again |
| 2026-08-11 | Deviation review with the product owner: the BOM-import build, the absent Logbook wire logging and the RFC 6750 security-error bodies are ratified as they stand; the annotation-processing gap was a defect and was fixed |
| 2026-08-12 | 1.1.0 release readiness: SonarCloud at zero findings, Trivy clean on all three image flavours with the images actually built, and the first PIT baseline recorded (§13.4) |
| 2026-08-11 | Targeted at **1.1.0**. `main` and `develop` diverged at the 1.0.0 groundwork commit: the 1.0.x line was released from `develop` while this work continued on `main`, so the two must be reconciled before the release. The build fixes and the Netty CVE override from the released line were adopted verbatim here to keep that reconciliation conflict-free |
| 2026-08-11 | Deployment into the DNMS stack takes a **per-service environment commons file** (`envs/<env>/values-common-opentmf-cadenzaflow.yaml`) rather than an exemption inside the shared chart. The shared chart pins one identity provider per environment and this component validates two, which are mutually exclusive settings; a chart-level allowlist was built first and then withdrawn, so the platform chart stays byte-identical while the team decides how it wants to handle components it deploys but does not own |

---

## Document Management

### Definitions, acronyms and abbreviations

| Term | Meaning |
|---|---|
| ACL | Access control list |
| BPMN | Business Process Model and Notation — the modelling standard the engine executes |
| BVR | Business validation rule |
| DMN | Decision Model and Notation |
| External task | A BPMN service task whose work is performed by an outside worker over REST |
| FR | Functional requirement |
| Identity provider | The directory of users and groups (here: Keycloak or Entra ID) |
| Issuer | The authority that mints and signs a token; identified by the `iss` claim |
| JWKS | JSON Web Key Set — an issuer's published signing keys |
| NFR | Non-functional requirement |
| OIDC | OpenID Connect |
| SSO | Single sign-on |
| TTL | Time to live — here, how long history is retained |
| UC | Use case |

### Special conventions

- Requirement ids are `FR-nn` / `BVR-nn`; use-case ids are `UC-nn` with a PASCAL
  short name. Both are stable: an id is retired, never reused.
- Configuration is quoted in environment-variable form, because that is how it is
  actually set.
- Diagrams are Mermaid — see the deviation in [§13.3](#133-deviations-from-the-dnms-engineering-standard).

### References

- [1] [README.md](../README.md) — the user and tester front door
- [2] [docs/openapi.yaml](openapi.yaml) — the API contract
- [3] [docs/identity-provider-analysis.md](identity-provider-analysis.md) — the option analysis behind the identity design
- [4] [docs/mixed-identity-provider-recipes.md](mixed-identity-provider-recipes.md) — configurations for mixed-provider deployments
- [5] [deploy/entra/](../deploy/entra/) — the complete deployment package for an Entra-ID-only environment: setup guide, environment file and Kubernetes manifests
- [6] [CHANGELOG.md](../CHANGELOG.md)

### Obsoleted documents

- `docs/entra-identity-plugin-plan.md` and
  `docs/identity-provider-implementation-plan.md` — delivered; kept as the record
  of what was planned, superseded by the shipped implementation.

### Document history

Git is authoritative. This table records the milestones a reader outside the
repository would look for.

| Date | Change |
|---|---|
| 2026-08-11 | Card created; documentation split into the user/tester README and this design record |
