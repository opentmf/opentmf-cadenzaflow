# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.1.4] - 2026-08-19

### Changed

- **The images now declare the management port (16000).** The actuator — the health
  probes and the `/actuator/prometheus` scrape endpoint — is served on
  `management.server.port`, never on `SERVER_PORT`, but only 8080 was `EXPOSE`d. `EXPOSE`
  is metadata: Kubernetes routes to `containerPort` regardless, so nothing was broken by
  its absence, but tooling that derives ports from the image could not see the one port a
  metrics scraper needs. Declared as a literal rather than through an environment
  variable, because a variable of that name would bind to the property and restate its
  own default.

## [1.1.3] - 2026-08-18

### Changed

- **CadenzaFlow engine upgraded from 1.2.1 to 1.2.2**, which fixes DMN/FEEL collection
  results being stamped with a Java type no consumer can load. When a DMN decision
  returned a FEEL list, the engine recorded the variable's `objectTypeName` as the FEEL
  engine's shaded Scala bridge class
  (`camundajar.impl.scala.collection.convert.JavaCollectionWrappers$SeqWrapper`). The JSON
  value was correct, so Cockpit and untyped readers saw nothing wrong — but that class is
  engine-internal, so every **typed** external-task client read of such a variable failed
  with `TASK/CLIENT-05002` ("Cannot construct java type from string"). FEEL list and
  context results are now materialized into `java.util.ArrayList` / `java.util.LinkedHashMap`
  before they enter the variable machinery, so `objectTypeName` always names a loadable
  class.

  The defect was inherited from upstream Camunda 7's FEEL-Scala shading and affected every
  DMN list result, so it is not specific to this distribution. BPMN authors who worked
  around it at the mapping boundary — typically `${JSON(x).mapTo("java.util.ArrayList")}`
  — no longer need to; those mappings stay harmless and can be retired at leisure.

- **SBOM attestations are now per architecture.** Previously one CycloneDX SBOM was
  generated from the multi-arch index and attested to that index — but Trivy resolves
  an index to a single child, so the SBOM described amd64 while claiming to cover the
  whole tag. An arm64 consumer verifying the attestation got an accurate-looking
  component list for an image they do not run. This is the same defect the Trivy HTML
  reports had before 1.1.1, and this is its remaining half. Each build leg now
  generates and attests its own architecture's SBOM against its by-digest image,
  where the subject and the contents cannot disagree.

  **Action required if you verify attestations:** they now hang off the
  per-architecture child digests rather than the index, so
  `cosign verify-attestation` against the *tag* no longer finds one. Resolve the tag
  to the child for your platform and verify that. Image *signatures* are unchanged —
  the index is still signed, because a signature asserts provenance about the thing
  you actually pull. Signing and attestation use cosign 2.6.1 (was 2.5.2); same major,
  so the signature format is unchanged.

## [1.1.2] - 2026-08-15

### Security

- Apache HttpComponents Core upgraded from 5.4.2 (Spring Boot 4.1.0 BOM) to 5.4.3:
  fixes CVE-2026-54399 (HIGH) in `httpcore5` and CVE-2026-54428 in `httpcore5-h2`,
  both denial of service via oversized HTTP/2 HPACK headers. It arrives through
  `cadenzaflow-keycloak-4` → `httpclient5`, so **every** image flavour was affected,
  not just one — all six build legs of the 1.1.1 release failed the Trivy gate on it,
  which is why 1.1.1 has no images. Consumers of the 1.1.1 jar from Maven Central
  carry the vulnerable version and should move to 1.1.2.

  Note for anyone overriding this themselves: `httpcore5.version` is the Spring Boot
  BOM's own property name, and this project imports that BOM rather than inheriting
  from it, so setting the property has no effect — the override has to be explicit
  `dependencyManagement` entries.

### Fixed

- **The `-aws` image shipped a mixed AWS SDK.** `sts` was pinned directly while
  `aws-msk-iam-auth` contributed `auth` from the same dependency depth, so Maven's
  nearest-wins resolved by declaration order and the image carried `auth` 2.44.12
  against `sdk-core`/`aws-core` 2.52.0. AWS does not support mixing v2 module
  versions, and `auth` is the module implementing the IRSA/WebIdentity credential
  providers that `sts` is there for — so the likely symptom was a `NoSuchMethodError`
  during credential resolution, on a code path that only runs inside EKS and that no
  test covers. The `aws` profile now imports `software.amazon.awssdk:bom` (2.53.1),
  which aligns all 31 SDK modules on one version; `sts` no longer carries its own
  `<version>`. Only the `-aws` flavour is affected.

## [1.1.1] - 2026-08-15

### Added

- **Credential masking now reaches message text**, not just field names. The masking
  rules shipped in `logback-masking.xml` gained the platform's three credential-class
  value rules: `key=value` secrets (`password=…`, `client_secret: …`), HTTP auth
  schemes (`Bearer …`, `Basic …`, scheme kept for triage), bare JWTs anywhere, and
  card numbers written in separated groups. Until now only *field names* were masked,
  so a secret that arrived inside prose went out in the clear — and prose is exactly
  where the engine puts them: identity-plugin and REST-client failures are logged
  verbatim, and a Keycloak admin-client error is a place a bearer token echoes. These
  four rules are byte-identical to the shared fragment in the DNMS service template
  and are not allowed to diverge per service.
- The **personal-data** rules stay deliberately different from that shared fragment,
  and each difference is now pinned by a test so it cannot be "aligned" away by
  accident: e-mail addresses in message text remain readable (the engine user id *is*
  the e-mail, so masking it would erase the actor from every authorization, task-claim
  and incident line), while IBANs are masked whole and unseparated 12+ digit runs are
  masked — both stricter than the platform set. The reasoning, and the one residual
  risk this leaves (a customer e-mail carried inside an external task worker's
  `errorMessage` stays readable), are recorded in the fragment itself.

### Changed

- **The container uid is now pinned to 1001** (`adduser --uid 1001`). This is the uid
  every released image has already run as — Ubuntu noble ships an `ubuntu` user at
  1000, so the previous unpinned `adduser` landed on 1001 — so nothing changes at
  runtime. What changes is that a deployment's `securityContext.runAsUser` can now
  name the value safely instead of depending on which users the base image happens to
  ship.

- **The Trivy report attached to a release is now one file per architecture**, named
  `trivy-report[-flavour]-amd64.html` / `-arm64.html` (was a single
  `trivy-report[-flavour].html`). The old single file was misleading rather than
  merely incomplete: Trivy resolves a multi-arch index to ONE child and, with no
  platform given, picks the runner's — so the report described amd64 while appearing
  to cover the tag, and an arm64 operator was reading about an image they do not run.
  Anything consuming these assets by name needs the new suffix. The CycloneDX SBOM
  attestation still describes amd64 only; that is the remaining half of the same
  problem.

- **Object process variables now serialize as JSON by default**
  (`cadenzaflow.bpm.default-serialization-format: application/json`, was the engine's
  own `application/x-java-serialized-object`). A Java-serialized variable is an opaque
  blob: invisible in Cockpit, unreadable to any consumer that is not Java — external
  task workers, dnms services — and a deserialization sink on the way back in. Spin's
  JSON dataformat was already on the classpath, so this only changes which one is
  picked when the caller names no format. Variables already stored in the old format
  stay readable, because the format is recorded per variable, so there is nothing to
  migrate. The one way this bites: a value that Java could serialize but Jackson
  cannot — no default constructor, an unsupported field type — now fails where it
  previously succeeded. Deployments that need the old behaviour back can set
  `CADENZAFLOW_BPM_DEFAULT_SERIALIZATION_FORMAT=application/x-java-serialized-object`.
  Deployments that already carry `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT` as an
  environment workaround can drop it once they are on 1.1.1 — the image now ships the
  same value.

## [1.1.0] - 2026-08-12

### Added

- **Microsoft Entra ID as an engine identity provider**, beside Keycloak and
  selected by `plugin.identity.provider`. Users and group memberships are read from
  the directory through Microsoft Graph on demand — no background synchronisation,
  so no sync lag and no stale local copy — and there is still no local user table.
  The engine mounts exactly one provider, which is its SPI contract, so a deployment
  has one directory and one set of user ids.
- **Multi-issuer token validation.** The REST API can accept tokens from several
  providers at once, each issuer's claims normalised onto one internal role
  vocabulary so endpoint rules are written once and never forked per provider. The
  concrete case is human callers on Entra ID and service-to-service callers on
  Keycloak, on the same endpoints. Expected audiences are validated where one issuer
  serves many applications, which Entra ID does. A trust set that is ambiguous or
  declares an issuer twice **fails the boot** rather than starting with an undefined
  trust set. Requires `openid-rbac-security` 2.3.0.
- **`deploy/entra/`** — a self-contained deployment package for environments where
  Entra ID is the only identity provider: from-scratch tenant setup, a ready-to-fill
  environment file, Kubernetes manifests, and a Compose file that runs the identical
  identity configuration against a throwaway database so an app registration can be
  proven on a laptop before it reaches a cluster.
- **Masked JSON logging in clusters.** Fields named like secrets or personal data
  (`password`, `client_secret`, `*token*`, `authorization`, `email`, `iban`,
  `msisdn`, …) become `****`, as do IBANs, German phone numbers and long digit runs
  found anywhere in a message. The rules live in `logback-masking.xml` **inside the
  jar**, so an operator-mounted Logback file gets identical masking with a one-line
  include. The encoder changed from Boot's built-in `StructuredLogEncoder` to
  `logstash-logback-encoder` for one reason: the built-in one has no masking hook.
  E-mail addresses inside message *text* are deliberately left readable — the engine
  user id **is** a mail address, so masking it would erase the actor from every
  authorization and task line.
- **An `-azure` image flavour** (`azure` Maven profile) carrying
  `azure-identity-extensions` for passwordless authentication to Azure Database for
  PostgreSQL through Entra ID tokens resolved by `DefaultAzureCredential`.
- **Engine metrics** on the management port beside the JVM ones: job and activity
  counters, active process instances and open incidents.
- **The component design card** (`docs/component-design-card.md`), splitting the
  documentation in two: the README is the self-contained front door for users and
  testers — use cases, requirements, validation rules, processing-logic cards with
  sequence diagrams, the access-control list and the data model — while the card
  carries the internal record, including every deviation from the DNMS engineering
  standard and its reasoning.

### Changed

- **Default history retention is now 30 days** (`P30D`, was `P92D`). This is only the
  fallback for process definitions that do not state their own retention — a
  definition that carries `camunda:historyTimeToLive`, or has one set at runtime via
  `PUT /process-definition/{id}/history-time-to-live`, is unaffected and may be longer
  or shorter. Deployments that relied on the old default and want it back should set it
  explicitly. Impact: finished instances and their history rows are removed by the
  nightly cleanup roughly two months sooner than before.

- **Breaking (configuration):** the webapp SSO client registration id is now the
  provider-neutral `oidc` (was `keycloak`). The id is user-visible in
  `/oauth2/authorization/{id}` and on Spring's generated login page, so an Entra
  deployment previously advertised "keycloak". Deployments overriding the
  registration via environment variables must rename
  `SPRING_SECURITY_OAUTH2_CLIENT_{REGISTRATION,PROVIDER}_KEYCLOAK_*` to
  `..._OIDC_*`. The redirect-URI path segment is unaffected — Spring resolves the
  registration from the `state` parameter — so existing identity-provider redirect
  URIs keep working.
- **Breaking (build):** the AWS Maven profile is renamed `aws-iam` → **`aws`**. The
  published `-aws` image tag is unchanged; only local invocations such as
  `-P docker,aws-iam` need updating.
- With exactly one client registration the generated "Login with OAuth 2.0" page is
  bypassed: `/login` and `/login?error` redirect straight to the provider instead of
  rendering a provider list and "Invalid credentials" — the state reached by
  navigating Back onto an already redeemed authorization code.
- **The coverage gate now fails the build** instead of warning, at 80% line,
  instruction and branch with zero missed classes. Getting there closed a real gap:
  the incident-logging handler had no branch coverage at all, and now has unit tests
  for each of its decisions.
- **The release pipeline is split by flavour AND architecture.** Previously one job
  per flavour built a multi-arch image and Trivy scanned only the amd64 side, so a
  never-scanned arm64 image shipped inside the published manifest. Now each
  (flavour, architecture) leg pushes an untagged by-digest image and is Trivy-gated
  on its own image; a per-flavour merge job assembles the tagged manifest list and
  signs **that index digest** with keyless cosign, attaching a CycloneDX SBOM
  attestation. The workflow file is renamed `docker-image-on-release.yml`.
- The ArchUnit suite now also enforces one logging facade, the `log` field name, and
  explicit names on request-binding annotations.

### Fixed

- **Annotation processing had silently stopped.** Java 23 disabled implicit
  annotation processing, so `spring-boot-configuration-processor` was on the
  classpath doing nothing — no error, no warning, a green build, and no
  `META-INF/spring-configuration-metadata.json`. Requesting it explicitly
  (`<proc>full</proc>`) restores IDE completion and inline documentation for the 50
  configuration properties across `plugin.identity.*` and `cadenzaflow.bpm.oauth2.*`.

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
