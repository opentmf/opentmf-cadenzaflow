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
| OTLP tracing dependency | ❓ none found in the pom — `MANAGEMENT_OTLP_TRACING_ENDPOINT` from envStatic is expected to be a no-op; confirm the boot log stays clean |
| Container user | `adduser java` on `eclipse-temurin:25-jre-noble` → expected **uid 1000**; ❓ confirm with `docker run --rm ghcr.io/opentmf/opentmf-cadenzaflow:1.1.0 id` (the chart hardcodes 999 today — Part B parameterizes it) |
| `default-serialization-format: application/json` in-image | ❌ **1.1.0 gap** — the deployment carries `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT` as an env workaround (engine-produced collection variables are otherwise java-serialized and external-task workers fail with `ValueMapperException`) |
| Behavioral masking test | ❌ nothing in this repo executes the fragment; only the file ships |
| pom version | `1.1.1-SNAPSHOT`; CHANGELOG already carries the `[1.1.1]` section |

## 2. Part A — this repo (target release: 1.1.1)

1. **Ship the serialization default in-image.** Add
   `default-serialization-format: application/json` to the engine block in
   `config-cadenzaflow.yml` (beside the history/TTL defaults that moved
   in-image in 1.1.0 — same rationale: platform requirement, not tuning).
   CHANGELOG under the existing `[1.1.1]` section. Note in the entry that
   deployments may drop the `CADENZAFLOW_BPM_DEFAULTSERIALIZATIONFORMAT`
   env once on 1.1.1.
2. **Port the behavioral masking test** from
   `pia-team/dnms-service-template` (`MaskedJsonAppenderTests`, commit
   `0ffb22b`): a fresh `LoggerContext`, configured exactly the way a
   deployment consumes the fragment
   (`<include resource="logback-masking.xml"/>` + `ref="JSON"`), asserting on
   emitted bytes per rule — path masks (MDC + StructuredArguments), `key=value`
   secrets in message text, Bearer/JWT, e-mail local-part (domain kept), IBAN
   (country+check kept), phone forms, separated card PANs, and the DELIBERATE
   non-rule (unseparated digit runs stay unmasked). Two pitfalls the template
   already solved — copy the solutions, not the debugging:
   - a bare `new LoggerContext()` has **no MDCAdapter** and NPEs on every
     append → `context.setMDCAdapter(MDC.getMDCAdapter())`;
   - the test exists precisely because the wrong decorator element fails
     SILENT — never weaken it to a structure-only check.
3. *(Optional, recommended)* **Pin the uid** in the Dockerfile
   (`adduser --uid 1000 …`) so the deployment's `runAsUser` value can never
   drift from the image.
4. **Release 1.1.1** through the normal split pipeline (per-arch Trivy gate,
   by-digest push, merged-index cosign).

## 3. Part B — dnms-deploy: the move (performable on 1.1.0, digest-bump later)

1. **Chart:** add a `runAsUser` value to `charts/dnms-backend`
   (default `999`, the template image's uid) and use it in the deployment's
   `securityContext`. This is the only chart change the move needs — probes,
   management port, profile CM, env model all already fit (verified above).
2. **Create `services/cadenzaflow/`:**
   - `values.yaml` — `name: camunda` (**keep the Service name**: the
     external-task workers dial the engine by this DNS name; the move must not
     rename it), image **digest-pinned** (1.1.0 now, 1.1.1 when cut),
     `runAsUser: 1000` (per the verified uid), `resources` override
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
