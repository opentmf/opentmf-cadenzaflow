# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.1] - 2026-09-03

### Security

- **The images no longer carry CVE-2026-65182.** Embedded Tomcat moves from 11.0.24
  (what the Spring Boot 4.1.1 BOM pins, and what 1.2.0 and 1.1.5 ship) to 11.0.25,
  which closes three CRITICAL findings in `tomcat-embed-core` — CVE-2026-65182 among
  them, a security-constraint bypass. No Boot GA pins the fixed line yet, so the pom
  overrides the BOM with explicit `dependencyManagement` entries for the three
  `tomcat-embed-*` artifacts, declared before the BOM import; a `tomcat.version`
  property alone would be silently ignored under a BOM import. The override is
  marked for removal once a Boot GA manages 11.0.25 or later. A deployment on 1.2.0
  or 1.1.5 should move to this release; nothing else changes.

## [1.2.0] - 2026-09-02

### Added

- **An operator can now see what is broken under one business flow, without the
  numbers lying about how badly.** `GET /engine-rest/extensions/incident/groups?rootProcessDefinitionKey=…`
  reports the active incidents of that root BPMN **and everything it called**, any
  depth, all deployed versions, grouped by called BPMN × task × incident type with
  counts, first/last occurrence and a sample message. The engine copies each incident
  into every ancestor process instance; the report counts only originating incidents,
  so a failure counts once instead of once per call-tree level — the flaw that makes
  the stock per-definition statistics endpoint misleading on any process that uses
  call activities. This retires reading incident spread out of Cockpit one definition
  at a time. A half-open time window (`incidentTimestampAfter` inclusive,
  `incidentTimestampBefore` exclusive, on the originating incident's raise time)
  narrows the report to e.g. "what broke since the 14:00 deploy" - windows chain
  without gaps or double counts. Mounted inside the engine REST application, so the existing
  `/engine-rest/**` GET rule covers it (`reader`/`writer`/`admin`) with no ACL
  addition for the report; the path is this service's addition, not upstream
  contract.

- **A whole group of failures can be retried with one call instead of one Cockpit
  click per incident.** `POST /engine-rest/extensions/incident/retry` takes a group's
  `selector` verbatim from the report plus a retry count, re-resolves it against the
  live incident table (scoped to the group's caller — the same child BPMN reached
  through another call activity is a different group — and optionally narrowed to
  one definition version, one tenant, and/or one half-open time window — a report queried with a time filter echoes that
  window into its selectors, so a retry from a filtered view touches exactly the
  displayed slice), and hands the job / external-task ids to the engine's
  set-retries batch API in chunks (`CADENZAFLOW_INCIDENTS_RETRY_CHUNK_SIZE`, default
  20000, one engine batch per chunk). Setting retries is also what resolves the
  incidents and their propagated copies. Because the selector is scoped to the root
  definition's call tree, retrying a shared child BPMN under one root leaves the same
  child's incidents under other parents untouched — which no engine-side
  retry-by-query can express. Every request writes one INFO audit line naming the
  caller, selector, incident count and batch ids. Covered by the stock
  `/engine-rest/**` POST rule (`writer`/`admin`), and additionally shipped with a
  dedicated ACL rule for exactly this path — listed before the wildcards, since
  rule matching is first-match-wins — so a deployment can require a stronger role
  for bulk retry alone without touching the wildcard rules.

- **Incidents are now browsable the way the rest of the estate pages.**
  `GET /engine-rest/extensions/incident` lists individual active incidents with the TMF-630
  paging contract — `offset`/`limit`, `X-Total-Count`/`Content-Range`, pagination
  `Link` header, 200/206/416, allowlisted `sort`, `fields=` selection — implemented on
  the tmf630-toolkit's core (called programmatically; the toolkit's Spring-MVC-only
  autoconfigure module cannot see requests Jersey serves under `/engine-rest`). Rows
  add what the stock query cannot say: definition key/name/version, activity name,
  and an `originating` flag separating real failures from the copies the engine
  propagates into ancestor instances. Its timestamp range uses the same half-open
  contract as the grouped report (after inclusive, before exclusive) - deliberately
  unlike the stock `/incident` filter's exclusive after, bridged internally with a
  one-millisecond shift. Same inherited `/engine-rest/**` GET roles.

- **The whole API surface is one published document now.** `docs/openapi.yaml`
  unions every endpoint of the embedded engine's REST API (from the engine's own
  generated spec artifact, rebased under `/engine-rest`) with this service's
  additions and the deployed bearer-JWT security model — an open-source consumer
  sees the complete picture at once instead of chasing the upstream docs per
  version. The file is generated by `ci/merge-openapi.py` from the exact embedded
  engine version and is regenerated, never edited; the service's own surface is
  authored in the source fragment `ci/openapi-service.yaml` (which replaces the
  previous hand-maintained `docs/openapi.yaml`).

### Changed

- **Dependency refresh, including the security library's new major.**
  `openid-rbac-security` 2.3.0 → 3.0.0: denied requests on a Spring-MVC-served path
  now answer 405 with an `Allow` header when the path exists but not for that HTTP
  method (`opentmf.security.unmatched-method-response` restores the old always-403).
  Inert for the engine surface — Jersey-served `/engine-rest/**` paths are invisible
  to the method resolver, so every existing 401/403 answer there is unchanged
  (re-verified by the full IT suite). The only Spring-MVC-served paths are the
  actuator endpoints, where an authenticated request with an unserved method now
  answers 405 instead of 403. Also: CadenzaFlow
  engine 1.2.2 → 1.2.3, Spring Boot 4.1.0 → 4.1.1 — whose BOM now manages the fixed
  postgresql/netty/httpcore5 versions itself, so the three CVE override pins are
  deleted from the pom — GraalJS 25.3.4.1, and the aws-flavour SDK pair.

### Fixed

- **The verification recipes in README §8.9 could not succeed against two of our own
  releases, and left the tag unpinned.** Two separate problems, both ours:

  **1.1.0 and 1.1.3 are signed under a branch identity.** Both were rebuilt through the
  documented recovery path — a `workflow_dispatch` from `develop` — so their signing
  certificate records `...docker-image-on-release.yml@refs/heads/develop` rather than
  `@refs/tags/<version>`. The published recipe is tag-anchored, so it rejects them.
  Nothing is mis-signed and the identity is accurate; the recipe simply described only
  the releases produced the ordinary way. §8.9 now carries a per-release table saying
  exactly which command works against which release, and notes that a deployment policy
  requiring a tag-anchored identity will refuse those two — which is usually the
  behaviour you want rather than something to work around.

  **The identity was not pinned to a version.** It ended `@refs/tags/` with nothing
  after it, which accepts *any* tag of this workflow. The recipes now pin the version
  and anchor the end, with a note on why: a placeholder you must substitute fails
  **closed**, while a trailing wildcard fails **open**. The examples also moved from
  1.1.4 to 1.1.5, since 1.1.4's attestations do not exist.

  Both recipes were verified by extracting the regexp from the README verbatim and
  running it — it passes 1.1.5 and correctly rejects 1.1.3. The table covers the whole
  published history rather than starting at 1.1.0: every release back to 1.0.0 is
  signed and tag-anchored, so a failing `cosign verify` here means something genuinely
  wrong rather than a release predating signing.

## [1.1.5] - 2026-08-19

### Fixed

- **SBOM attestations were never reachable on a published image.** Since 1.1.3, every
  release generated a per-architecture CycloneDX SBOM and reported `cosign attest` as
  successful — but `cosign verify-attestation` fails with `no matching attestations`
  against a published image, whether you target the tag or resolve it to the child
  for your platform. Confirmed across 1.1.3, 1.1.4 and 1.1.4-aws: **not one published
  `linux/amd64` or `linux/arm64` child carried an attestation.**

  The cause is that buildx's own attestations were left enabled. With them on, a
  push-by-digest build publishes an *index wrapper* — the platform manifest plus an
  attestation manifest — and the digest the build step reports is that wrapper. cosign
  attested the wrapper; the merge job then assembled the release index from the
  wrapper's **children**, so the digest that actually shipped had nothing attached and
  the wrapper was discarded as an untagged orphan. The attestation succeeded against
  the wrong subject every time, which is why no workflow ever failed.

  Buildx provenance and SBOM generation are now off, so a build leg publishes a plain
  single-platform manifest and the attestation lands on the digest that ships.
  Verified against a throwaway registry: with provenance on, the pushed digest is an
  OCI *index* with two children; with it off, an OCI *manifest* with none.

  **What was and was not affected.** Image signatures were always correct — the
  cosign signature on the release index verifies, and nothing was mis-signed. What
  was missing is the SBOM's verifiable binding to the image. The SBOM *contents* have
  been attached to the GitHub Release as `sbom[-flavour]-<arch>.cdx.json` since 1.1.4,
  so nothing was unavailable, only unverifiable. **Consumers who tried to follow our
  documented verification steps on 1.1.3 or 1.1.4 got an error**, which those releases'
  notes and README §8.9 wrongly presented as a working procedure; both are corrected.

## [1.1.4] - 2026-08-19

### Fixed

- **`/actuator/env` masked every value, for everyone.** The endpoint is reachable by
  callers holding the `admin` role, but whether it shows real values or `******` is a
  *second*, independent decision made by Spring Boot through
  `management.endpoint.env.roles`. That list named `write`,
  `ENTERPRISE-GUI/ADMIN_ALL` and `ENTERPRISE-API/ADMIN_ALL` — none of which is a role
  this service issues — so no caller could ever satisfy it and the endpoint returned
  200 with the whole configuration masked. It now names `admin`, matching the role
  that governs access. Role names here are compared against the caller's authorities
  exactly as the token spells them, with no `ROLE_` prefix added, so a deployment that
  renames the roles (§3.4) must rename them in both places.

- **The authorization sequence diagram in README §6.1 did not render.** A semicolon
  inside a message label is a statement separator in Mermaid, so the diagram failed to
  parse and showed as an error instead of a picture. Every diagram in the README is
  now checked with the Mermaid parser.

- **A pod running the job executor could starve its own job acquisition.** The job
  executor runs up to `max-pool-size` job threads *plus* a separate acquisition
  thread, and each holds a database connection while it works — 11 consumers against
  a connection pool of 10. Under load the acquisition thread could be left waiting on
  the pool it feeds. The pool is now larger than the job pool by a wide margin, and
  both the configuration reference and §9.6 state the constraint so it survives future
  retuning.

- **A cosmetic API call could abort a green release.** The release workflow fetched
  the SonarCloud quality gate and the metrics for its summary report in one shell
  step under `set -euo pipefail`. The metrics call is decorative, but a 5xx from it —
  or a token that had lost a permission on that endpoint — failed the step *before*
  the already-fetched gate was evaluated, skipping all six image builds of a release
  that was passing. Gate and report are now separate steps: the gate is read and
  published as a step output, the report renders with `continue-on-error`, and
  enforcement happens last so a genuinely red gate still prints the conditions that
  failed.

- **The manifest-merge step could hand an empty tag list to Docker.** `set -e` does
  not catch an empty `$( )` expansion, so a metadata-action run that produced no tags
  — exactly what happened on 1.1.3's first release run — reached `buildx imagetools
  create` as if nothing were wrong and died with an unhelpful "can't push with no
  tags specified". The step now checks both the tag list and the downloaded
  per-architecture digests before calling Docker, and prints the offending payload
  when either is empty.

### Changed

- **Defaults now target real load rather than a demo: roughly 100 external tasks in
  flight.** `spring.datasource.hikari.maximum-pool-size` 10 → **30** (with
  `minimum-idle` 2 → 5), job executor 3/10/3 → **5/12/10** with
  `max-jobs-per-acquisition` 3 → **10**, and the fetch-and-lock registration queue
  200 → **1000** so a whole worker fleet reconnecting after a restart is not answered
  with HTTP 500. Nothing needs to be set to reach that scale now; §9.6 documents how
  to go further and, more importantly, which knob actually moves throughput.

  **Check your database budget before scaling out.** The pool figure is *per pod*, so
  three loaded pods now reach 90 connections and PostgreSQL ships `max_connections`
  at 100. Deployments that run several pods against a small database should raise
  the server limit or lower `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`.

- **Both architectures now build on native runners; QEMU is gone from the pipeline.**
  The arm64 leg was emulated on an amd64 runner because hosted arm runners are free
  only for public repositories — a constraint inherited from sibling projects that
  are private. **This repository is public**, so it never applied here. Emulated
  arm64 Maven builds are several times slower and were the long pole of every
  release. Runners are also pinned to a dated label rather than `ubuntu-latest`, so a
  GitHub image rollover cannot change the build substrate under a release without a
  commit.

- **Per-architecture SBOMs are attached to the GitHub Release** as
  `sbom[-flavour]-<arch>.cdx.json`, alongside the Trivy reports. The signed
  attestation on each by-digest image remains authoritative; these are the copies
  readable without cosign, so an auditor need not pull an image to see a component
  list.

- **New README §8.9, "Verifying an image."** The `cosign verify` and
  `cosign verify-attestation` recipes, with the certificate identity regexp that
  makes verification mean something, and the wrinkle that attestations hang off the
  per-architecture child digests rather than the tag — so verifying against the tag
  finds nothing.

- **Both workflows declare `permissions: contents: read` at the top level.** Every
  job already declared its own, and a job-level block replaces rather than extends
  the default, so nothing changes for the jobs that exist today. It is the floor for
  the next job added without one.

- **AWS SDK moved from 2.53.1 to 2.53.3** in the `-aws` flavour. A routine patch bump
  with no behaviour change; recorded because it alters what that image contains. The
  version is the imported `software.amazon.awssdk:bom`, so all 31 SDK modules move
  together — verified in the packaged jar, which is the only place a family split
  actually shows up.

- **Removed a dead entry from the security whitelist.** `/cadenzaflow/**` matched no
  path the service actually serves: the web UIs, their API and their static assets are
  reached through the OIDC login chain, not through the whitelist. Proven rather than
  assumed — with the entry present or absent, every real path answers identically, and
  the only difference was that unserved paths under it returned 404 instead of 401,
  i.e. it made the application's presence observable without authentication. Two
  regression tests now pin both halves of that behaviour.

- **The README says which identity providers actually work, and where.** The
  directory — task assignment and the Admin UI — is an engine plugin and must be
  Keycloak or Entra ID. Token validation and browser sign-on are ordinary OIDC and
  work with any compliant provider, several issuers at once for tokens. That
  distinction decides whether a given provider can be adopted, and it was previously
  only implicit.

- **The README links the upstream project and its images.** CadenzaFlow's source,
  reference manual, getting-started guide and modeller, plus its own
  `cadenzaflow/cadenzaflow-bpm-platform` images on Docker Hub — with a note on how
  those differ from ours, since they are the bare platform without the RBAC, SSO,
  directory-backed identity, masked logging and metrics this service adds.

- **The database schema is fully documented.** README §7 previously showed one diagram
  covering twelve of the engine's tables. It now carries an entity-relationship diagram
  per table group — deployments and definitions, runtime, history, identity — and
  **every one of the 49 tables appears in one of them, with its columns and types**,
  rather than a chosen dozen. Alongside it, [`docs/database-schema.md`](docs/database-schema.md)
  gives **one specification table per database table** in the platform's
  component-design-card shape: column, primary-key membership, type verbatim from the
  DDL, nullability, uniqueness, default, declared foreign key, and what the column
  holds — 681 columns, 42 foreign keys, 7 unique constraints and 225 indexes, all
  re-parsed from the engine's own PostgreSQL DDL so they cannot drift from it.

  It also answers two questions operators keep asking: why CMMN and DMN tables exist
  when no such model is deployed, and why the `ACT_ID_*` identity tables are created
  but stay empty.

  **Both documents now open by saying the schema is internal to the engine and may
  change in any release**, citing upstream's own words — *"The database is not part of
  the public API. The database schema may change for MINOR and MAJOR version updates"* —
  and the public-API definition that backs them up. So: do not write to these tables, do
  not extend the schema, and do not build reporting on it; the supported entry points are
  the REST API, the history endpoints and Cockpit. The catalogue is for understanding and
  troubleshooting a running system, a use that survives upgrades, rather than for
  integrating against.

  Both also record where this sits relative to upstream documentation: upstream describes
  eight of the forty-nine tables in prose and publishes the rest only as diagrams rendered
  from the **MySQL** schema, so no official per-column reference exists for any of the
  Camunda 7 forks. Ours is derived from the **PostgreSQL** DDL this service actually runs,
  which is also why a type may legitimately differ from an upstream diagram.

- **New README §9.6 on external-task throughput.** `maxTasks` is a batch size, not a
  parallelism setting — the Java client processes a batch on a single thread — so
  raising it makes one worker hold more locks for longer rather than doing more at
  once. The section documents that, the resulting duplicate-execution failure mode
  when `maxTasks × task duration` exceeds `lockDuration`, why long-polling workers
  consume connections rather than server threads (so `max-threads` should *not* scale
  with worker count), the ~30 s cross-pod pickup latency, and the fetch-and-lock
  registration queue. It also flags that a job pod is under-provisioned at the shipped
  defaults: 10 job threads plus an acquisition thread against a pool of 10.

- **§9.1 now lists every metric by name, and says which ones you can alert on.**
  Previously one row said `cadenzaflow.engine.*`; all thirteen meters are now named
  individually with what each means and which indicate saturation, plus the Prometheus
  renaming rule.

  The part that changes how you configure alerting: **the nine counters lag by up to 15
  minutes.** They are sums over `ACT_RU_METER_LOG`, and the engine flushes its in-memory
  counts on a fixed 15-minute timer that is not exposed as a configuration property —
  scraping more often re-reads the same stored sums. The two gauges are live queries and
  current to the second. So a rule like "N job failures in the last 5 minutes" reads flat
  through an incident and then steps; `cadenzaflow.engine.incidents.open` is what actually
  fires. Metrics are reported by every pod independently of the job executor, so splitting
  the deployment does not lose them.

- **§9.5 answers which pods should serve the web UIs**, which the split-deployment
  guidance previously left implicit. They belong on a pod with the job executor off, and
  the reason is reachability rather than performance: job pods are in no Service on
  purpose, and serving Cockpit from them would mean giving them an Ingress, SSO redirect
  URIs and session cookies. No UI action needs a local job executor — retries, batch
  operations and history cleanup are all picked up from the database by whichever pods run
  one. The section also names the case where two deployments are not enough: Cockpit can
  issue the most expensive queries in the system, and sharing a pool with external-task
  workers means one broad history query can expire worker locks and cause duplicate
  execution, so busy installations should run the UI as its own deployment.

- **Corrected throughout:** `schema-update` creates missing tables, it does **not**
  patch existing ones — an engine upgrade shipping new DDL needs a deliberate step, and
  the README, the design card and the new schema doc had all said otherwise. The role
  override examples in §8.5 used `read`/`write`, which are not the roles this service
  ships (`reader`/`writer`).

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

  > **Correction, added 2026-08-19 — the paragraph above does not work as written.**
  > No published child of 1.1.3 or 1.1.4 carries an attestation: the attestation was
  > applied to an intermediate digest that never shipped. Resolving the tag to a child
  > and verifying there returns `no matching attestations` for those two releases, and
  > there is no procedure that succeeds against them. Signatures on the index are
  > unaffected and do verify. Fixed in 1.1.5; the SBOM contents for 1.1.4 are on that
  > release page as `sbom[-flavour]-<arch>.cdx.json`. Left in place rather than
  > rewritten, because it is what those releases shipped with and consumers may have
  > acted on it.

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
