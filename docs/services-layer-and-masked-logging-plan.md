# Plan: move the engine into the deployment's services layer + masked JSON logging

**Decision (owner, 2026-08-15):** opentmf-cadenzaflow is a service we release —
cosigned multi-arch image, Entra/Keycloak identity, its own security surface —
so in the DNMS deployment repo (`pia-team/dnms-deploy`, private) it moves out
of the infra layer (`base/infra/camunda`, raw kustomize manifests) into the
**services layer** (`services/`, rendered by the shared `dnms-backend` Helm
chart). That makes it a full participant in the platform's two-channel config
model (combined-rules §25.6a) — including the service-owned logback baseline
and the §13.1 **masked JSON logging**.

This plan is self-contained for a fresh session. Part A is work in THIS repo;
Part B is work in `dnms-deploy` (its own checkout/session — do not perform
Part B from an unrelated working tree). Parts are independent: **Part B does
not wait for Part A**, because the already-released 1.1.0 image ships
everything masked logging needs (verified below).

---

## 1. Verified state (2026-08-15, this repo @ `cf0fb0c`, dnms-deploy @ develop)

Facts checked against sources — do not re-derive, but re-verify anything
marked ❓ before relying on it.

| Fact | State |
|---|---|
| `logstash-logback-encoder` 9.0 in the pom (runtime, property-pinned) | ✅ shipped since **1.1.0** (Entra PR `9f9587d`) |
| `src/main/resources/logback-masking.xml` with the **`<decorator>`** element (encoder 9.x renamed the config surface; the old `<jsonGeneratorDecorator>` is SILENTLY IGNORED — masking off, no error) | ✅ shipped since **1.1.0**, correct element already |
| Management port **16000**, `base-path: /`, `health.probes.enabled: true`, `show-details: always` | ✅ in-image (`application.yml`) — the chart's liveness/readiness probe paths work |
| `micrometer-registry-prometheus` | ✅ present — the chart's prometheus scrape annotations work |
| `openid-rbac-security` 2.3.0 | ✅ — the chart's chart-owned platform profile (`opentmf.security.management.*` ACL) applies to this app |
| `spring-kafka` / `spring-data-redis` on the classpath | ✅ **absent** — the deployment's shared `envStatic` Kafka/Valkey variables are inert here (same receive-and-ignore precedent as the no-DB services) |
| OTLP tracing dependency | ✅ **verified 2026-08-15** — no `otlp` / `opentelemetry` / `micrometer-tracing` reference anywhere in the pom or `src/main/resources`, so `MANAGEMENT_OTLP_TRACING_ENDPOINT` from envStatic is inert |
| Container user | ✅ **verified 2026-08-15: uid 1001, NOT 1000.** `docker run --rm ghcr.io/opentmf/opentmf-cadenzaflow:1.1.0 id` → `uid=1001(java) gid=1001(java)`. Cause: `eclipse-temurin:25-jre-noble` inherits Ubuntu noble's own `ubuntu` user at uid/gid 1000, so an unpinned `adduser` takes the next free id. Part A now pins `--uid 1001` (the value already released); **Part B's `runAsUser` is 1001** |
| `default-serialization-format: application/json` in-image | ✅ **shipped on develop** (`cbd523c`) — the deployment's `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT` env workaround can be dropped at the 1.1.1 digest bump |
| Behavioral masking test | ✅ **the fact table was wrong** — `src/test/java/org/opentmf/cadenzaflow/config/logging/LogbackMaskingTests.java` has shipped since 1.1.0. It configures the fragment exactly as a deployment does and asserts on encoded bytes, and its `@BeforeEach` fails on any Joran status above INFO — a *stronger* silent-failure guard than the template's, worth back-porting there. Part A **extends** it rather than porting `MaskedJsonAppenderTests` |
| Masking rule set vs the template's fragment | ⚠️ **not identical, by design.** The credential-class rules are a uniform floor and were missing here — added for 1.1.1 with tests. The three PII-class rules diverge deliberately (e-mail readable; IBAN whole-masked; unseparated 12+ digit runs masked) and are now pinned by `DeliberateDivergences` tests plus rationale in the fragment |
| pom version | `1.1.1-SNAPSHOT`; CHANGELOG already carries the `[1.1.1]` section |

## 2. Part A — this repo (target release: 1.1.1) — ✅ DONE 2026-08-15

1. ✅ **Ship the serialization default in-image.**
   `default-serialization-format: application/json` sits in the engine block of
   `config-cadenzaflow.yml` (shipped in `cbd523c`, beside the history/TTL
   defaults that moved in-image in 1.1.0 — same rationale: platform
   requirement, not tuning). The `[1.1.1]` CHANGELOG entry now says deployments
   may drop the `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT` env once on 1.1.1.
2. ✅ **Extend the existing masking test — do NOT port the template's.**
   `LogbackMaskingTests` already did the hard part (fragment configured the way
   a deployment consumes it, assertions on encoded bytes, Joran warnings
   treated as failures). Splitting the work by rule class, per the review:
   - **Credential-class rules are a uniform floor and never diverge.** Three
     were missing here and are now in the fragment, byte-for-byte from the
     template: `key=value` secrets in message text, Bearer/Basic + bare JWTs,
     separated card PANs. This was a real gap, not a formality — the engine
     logs identity-plugin and REST-client failures verbatim, and a Keycloak
     admin-client error is precisely where a token echoes into free text.
   - **The PII-class divergences stay**, each with its rationale in the
     fragment and a `DeliberateDivergences` test case pinning it so the
     difference is provably a decision, not drift: e-mail readable (the engine
     user id IS the e-mail; masking erases the actor from authz/task/incident
     lines), 12+ digit runs masked and IBANs masked whole (both stricter than
     the template — stricter is always acceptable).
   - **One residual risk is named in the fragment comment, not fixed:**
     external task workers submit `errorMessage` content the engine then logs,
     so the e-mail divergence leaves a customer e-mail inside such text
     readable. No regex can tell an actor's address from a subject's. Bounded
     by net #1 (workers must not put raw PII in error messages — worth a line
     in the worker rules someday) and net #3 (collector redaction).
   - 14 tests green, including the Joran guard.
3. ✅ **Pin the uid** in both Dockerfiles — `adduser --uid 1001`, **not 1000**:
   that is the uid every released image already runs as (see the fact table),
   so one chart value stays correct across the 1.1.0 → 1.1.1 digest bump and
   the pin changes nothing at runtime.
4. ⬜ **Release 1.1.1** through the normal split pipeline (per-arch Trivy gate,
   by-digest push, merged-index cosign). Refresh the `[1.1.1]` CHANGELOG date
   to the actual release day first.

## 3. Part B — dnms-deploy: the move (performable on 1.1.0, digest-bump later)

1. **Chart:** add a `runAsUser` value to `charts/dnms-backend`
   (default `999`, the template image's uid) and use it in the deployment's
   `securityContext`. This is the only chart change the move needs — probes,
   management port, profile CM, env model all already fit (verified above).
2. **Create `services/cadenzaflow/`:**
   - `values.yaml` — `name: camunda` (**keep the Service name**: the
     external-task workers dial the engine by this DNS name; the move must not
     rename it), image **digest-pinned** (1.1.0 now, 1.1.1 when cut),
     `runAsUser: 1001` (per the verified uid — see the fact table; 1000 is
     Ubuntu noble's own user, not this image's), `resources` override
     (768Mi request / 1536Mi limit — the engine's current sizing), and env:
     - `SPRING_DATASOURCE_HIKARI_SCHEMA: cadenzaflow` — replaces the old
       URL-embedded `currentSchema`; the shared envStatic
       `SPRING_DATASOURCE_URL` (server-only, §8 stated-schema rule) takes over.
       The image derives its table prefix from `hikari.schema`, so the schema
       name must stay `cadenzaflow`. ❓ the old URL also carried
       `useUnicode/characterEncoding` — expected redundant with the driver's
       UTF-8 default; confirm on first boot.
     - DB username/password via `secretKeyRef` on the `cadenzaflow-db` secret
       (add a `username` key to the local overlay's secretGenerator — today it
       carries only `password` and the username rides a literal env).
     - `PLUGIN_IDENTITY_KEYCLOAK_CLIENTSECRET` via `secretKeyRef` (unchanged).
     - the Keycloak plugin **scalars** move from the old mounted profile to
       env per the §25.6a SHAPE rule: client-id, `administrator-group-name`,
       `use-group-path-as-camunda-group-id` (keep the existing comment about
       group NAME vs path — that trap was found the hard way) as service env;
       the issuer/admin **URLs are per-environment facts** → envStatic in
       `envs/<env>/values-common.yaml`.
     - `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT: application/json` — keep
       until the 1.1.1 digest bump, then delete (Part A ships it in-image).
   - `application-common.yaml` — the engine-rest `secure-endpoints` list
     (whole-list, verbatim from the old `camunda-security-profile` ConfigMap)
     **plus the `logging.config` pointer** (`/config/profile/logback-common.xml`
     — never an env var; see the §25.6a logging-channel rule).
   - `logback-common.xml` — **the masked include, immediately**:
     ```xml
     <configuration>
       <include resource="logback-masking.xml"/>
       <logger name="com.dnext" level="INFO"/>
       <logger name="org.opentmf" level="INFO"/>
       <root level="INFO"><appender-ref ref="JSON"/></root>
     </configuration>
     ```
     The engine becomes the FIRST masked baseline in the stack — 1.1.0
     already carries the encoder and the corrected fragment, so unlike the
     five DNMS services it does not wait for any re-release.
3. **Retire `base/infra/camunda/`** (deployment, service, the
   `camunda-security-profile` ConfigMap, kustomization entry). The engine's
   old `SPRING_PROFILES_ACTIVE: k8s` + `/config/` mount are replaced by the
   chart's standard `platform,common,<env>` chain + `/config/profile/` mount
   from envStatic. The postgres init Job's `cadenzaflow` role/schema
   provisioning is untouched.
4. **Verify:**
   - `make render ENV=<each env>` clean; `make verify-config` green (the
     engine now passes through the one-key-one-channel AND the three
     logging-channel guards).
   - Live on the local cluster: engine Ready (startup probe path), the
     engine e2e gate (the workers' full flow) green, and **masked JSON
     proof**: log a request carrying a mail address / token and check
     `kubectl logs deploy/camunda` shows `****@…` / `Bearer ****` — the same
     spot-checks the template's behavioral test automates.
   - `make verify-acl`: ❓ the script maps deployed ACLs to service checkouts
     by directory name — check it either finds this repo's
     `config-security.yml` or documents the engine as a known-unmapped source.

## 4. Explicitly out of scope

- No chart-inheritance / multi-chart framework: the move needs exactly one
  new chart value (`runAsUser`). Revisit chart structure only when a second
  service diverges in SHAPE (different kind, probes, or mount model), and
  then prefer a Helm library chart over ad-hoc inheritance.
- transformation-app stays in infra for now — same criterion
  ("Spring Boot service we release that speaks the platform config surface")
  to be evaluated separately.
