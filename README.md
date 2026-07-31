# opentmf-cadenzaflow
An OpenTMF produced Spring Boot 4 microservice that embeds the [CadenzaFlow](https://github.com/cadenzaflow/cadenzaflow-bpm-platform) community edition BPM platform with its public Spin, and [OpenID auth for Keycloak](https://github.com/cadenzaflow/cadenzaflow-keycloak) plugins, as well as using OpenTMF's [openid-rbac-security](https://github.com/opentmf/openid-rbac-security) framework to secure the API endpoints.

This is the successor of [opentmf-camunda7](https://github.com/opentmf/opentmf-camunda7). The service uses CadenzaFlow's Spring Boot **4** starters (`cadenzaflow-bpm-spring-boot-starter-*-4`, Spring Framework 7), its `org.cadenzaflow.*` Maven artifacts, and its `cadenzaflow.bpm.*` configuration namespace.

## CadenzaFlow Dependencies

All CadenzaFlow artifacts resolve from Maven Central: the platform (1.2.1+,
including the Spring Boot 4 starters) and `cadenzaflow-keycloak-4` (1.1.2+) -
the spring-web-7-compiled Keycloak identity plugin line matching the `-4`
starters. (The non-`-4` `cadenzaflow-keycloak` artifact is compiled against
spring-web 6 and does NOT work on Spring Framework 7.) No extra Maven
repository declaration is needed.

## Service URLs

The application listens on two ports: the application port (`8080`, context path `/cadenzaflow/v1`) and the management port (`16000`). The management port is expected to stay internal (not exposed through the Ingress).

| URL | Description | Authentication |
|---|---|---|
| `http://<host>:8080/cadenzaflow/v1/engine-rest/**` | Engine REST API (process definitions, instances, tasks, deployments, history, ...) | JWT with roles per [config-security.yml](src/main/resources/config-security.yml); `/engine-rest/external-task/**` is whitelisted by default for worker traffic |
| `http://<host>:8080/cadenzaflow/v1/app/cockpit` | Cockpit — monitoring and operations UI | Keycloak OpenID SSO |
| `http://<host>:8080/cadenzaflow/v1/app/tasklist` | Tasklist — human task UI | Keycloak OpenID SSO |
| `http://<host>:8080/cadenzaflow/v1/app/admin` | Admin — users, groups, authorizations UI | Keycloak OpenID SSO |
| `http://<host>:16000/actuator/health/liveness`, `/readiness` | Kubernetes probes | anonymous |
| `http://<host>:16000/actuator/prometheus` | Prometheus scrape endpoint | anonymous (management whitelist) |
| `http://<host>:16000/actuator/metrics/**`, `/loggers/**` | Micrometer metrics, runtime log-level management | anonymous (management whitelist) |
| `http://<host>:16000/actuator/env` | Effective configuration inspection | JWT + `admin` role |

## Engine Configuration (`cadenzaflow.bpm.*`)

All engine behavior is configured under the `cadenzaflow.bpm` prefix (environment-variable form `CADENZAFLOW_BPM_*`). The defaults this image ships with are defined in [config-cadenzaflow.yml](src/main/resources/config-cadenzaflow.yml):

| Property | Default | Description |
|---|---|---|
| `cadenzaflow.bpm.authorization.enabled` | `true` | Engine-level authorization checks (drives what users/groups may see and do in the web UIs and REST API). |
| `cadenzaflow.bpm.database.type` | `postgres` | Database dialect. |
| `cadenzaflow.bpm.database.schema-update` | `true` | Create/patch the engine (`ACT_*`) tables automatically at startup. |
| `cadenzaflow.bpm.database.schema-name` | `${spring.datasource.hikari.schema}` | Schema holding the engine tables. |
| `cadenzaflow.bpm.database.table-prefix` | `<schema>.` | Table prefix used in generated SQL. |
| `cadenzaflow.bpm.filter.create` | `All tasks` | Default Tasklist filter created on first start. |
| `cadenzaflow.bpm.webapp.application-path` | `/` | Serves the web UIs directly under the context path (`/cadenzaflow/v1/app/...`). |
| `cadenzaflow.bpm.oauth2.sso-logout.enabled` | `true` | Logging out of a web UI also ends the Keycloak SSO session. |
| `...generic-properties.properties.historyTimeToLive` | `P92D` | Global history time to live — see [History Cleanup](#history-cleanup). |
| `...generic-properties.properties.legacyJobRetryBehaviorEnabled` | `true` | Failed jobs keep the classic retry-cycle behavior. |

Commonly tuned knobs (settable directly as environment variables; engine defaults shown):

| Environment Variable | Default | Description |
|---|---|---|
| `CADENZAFLOW_BPM_HISTORY_LEVEL` | `full` | History/audit detail: `full`, `audit`, `activity`, or `none`. `activity` drops per-variable history writes — a significant insert-load reduction for high-throughput installations that keep their own payload audit. |
| `CADENZAFLOW_BPM_JOB_EXECUTION_CORE_POOL_SIZE` | `3` | Job executor worker threads (core). |
| `CADENZAFLOW_BPM_JOB_EXECUTION_MAX_POOL_SIZE` | `10` | Job executor worker threads (max). |
| `CADENZAFLOW_BPM_JOB_EXECUTION_QUEUE_CAPACITY` | `3` | In-memory queue between job acquisition and the worker pool. |
| `CADENZAFLOW_BPM_JOB_EXECUTION_MAX_JOBS_PER_ACQUISITION` | `3` | Jobs fetched per acquisition round-trip; raise it when sustained job throughput makes the single acquisition thread the bottleneck. |

> **Note:** keys under `generic-properties.properties` are camelCase map keys — Spring's
> relaxed binding does not reliably map `CADENZAFLOW_BPM_...` environment variables onto
> them. Override those via a mounted configuration file
> (`SPRING_CONFIG_ADDITIONAL_LOCATION`) instead, as shown in
> [History Cleanup](#history-cleanup).

### External Task Workers

Worker services consume service tasks of type *external* over the REST API — point the worker client's base URL at `http://<host>:8080/cadenzaflow/v1/engine-rest`. Long polling (`POST /engine-rest/external-task/fetchAndLock`) is supported. `/engine-rest/external-task/**` is whitelisted by default in [config-security.yml](src/main/resources/config-security.yml) so that high-frequency worker polling does not require tokens; tighten this in deployments where the application port is reachable by untrusted clients.

### Separating Job Execution from API Traffic

All pods that share one database form a single engine cluster: the job executor on every pod competes for the same jobs (timers, async continuations, retries, history cleanup). Under load, that background work contends with interactive traffic — REST calls and the web UIs — for the same worker threads, connection pool, and CPU. To isolate the two workloads, run **two deployments of the same image against the same database** and split the roles with configuration only:

**API pods** — behind the Service/Ingress, serve REST and the web UIs, run no jobs:

```yaml
CADENZAFLOW_BPM_JOB_EXECUTION_ENABLED: "false"
```

**Job-executor pods** — not registered in any Service (they receive no HTTP traffic), dedicated to background jobs; optionally switch the web UIs off:

```yaml
CADENZAFLOW_BPM_JOB_EXECUTION_ENABLED: "true"   # default
CADENZAFLOW_BPM_WEBAPP_ENABLED: "false"
```

Sizing notes:

- **At least one pod must run the job executor**, or timers, `asyncBefore`/`asyncAfter` continuations, retries, and history cleanup never execute.
- The two deployments **scale independently**: add API pods for request throughput, job pods for job throughput. Job pods are where the [job executor knobs](#engine-configuration-cadenzaflowbpm) (`CORE_POOL_SIZE`, `MAX_POOL_SIZE`, `QUEUE_CAPACITY`, `MAX_JOBS_PER_ACQUISITION`) matter; on API pods they are irrelevant.
- Watch the **database connection budget**: every pod holds its own Hikari pool (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, default 10) — the sum across both deployments plus every other client must stay below the database's `max_connections`.
- History cleanup runs on the job pods (cleanup jobs are ordinary jobs), so its batch window and parallelism size against *their* capacity.
- Keep heavy business logic out of the engine entirely by modeling it as [external tasks](#external-task-workers): the engine's own jobs then stay short (state transitions), and the actual work scales in the worker services.

## Secure Endpoints
This project uses OpenTMF's [openid-rbac-security](https://github.com/opentmf/openid-rbac-security) (2.x line, built for Spring Boot 4) to secure its exposed endpoints.

The default openid-rbac-security configuration requires read or write access for GET, write access for POST, PUT, and DELETE endpoints. These defaults can be overridden. Please see [config-security.yml](src/main/resources/config-security.yml) for initial configuration.

## Incident Logging
A built-in process engine plugin (`org.opentmf.cadenzaflow.config.incident`) writes a WARN log statement when a failed task has zero retry counts. It only logs — engine incident behavior is unchanged. Incidents without an execution entity (e.g. raised during process instance version migrations) are intentionally not logged.

## Workflow Variables Longer Than 4KB
With the help of the public Spin plugin, longer than 4KB workflow variables can be used. The Spin plugin is included by default.

## JavaScript Script Tasks (GraalJS)
BPMN script tasks with `scriptFormat="javascript"` are evaluated by [GraalJS](https://github.com/oracle/graaljs), since Nashorn was removed from the JDK. The stock GraalJS JSR-223 bridge leaks one polyglot context per evaluation (nothing ever calls `Context.close()`, and the Truffle engine registry pins every context forever), which grows the old generation unboundedly under script-task load. This application therefore routes JavaScript evaluation through a context-closing engine facade that closes each polyglot context as soon as the script invocation (environment scripts plus user script) completes.

- Spin helpers such as `S(...)` keep working: the context stays open across the environment scripts and the user script of one invocation.
- Script results (`camunda:resultVariable`) that are plain JavaScript objects or arrays are copied into plain Java maps/lists before the context closes.
- Storing a raw JavaScript object **directly** via `execution.setVariable(...)` is discouraged: the value is serialized after the context has closed. Convert it first, e.g. `S(JSON.stringify(obj))` or a Java type.
- Two Micrometer counters, `opentmf.graaljs.contexts.created` and `opentmf.graaljs.contexts.closed`, expose the context lifecycle; in steady state their difference is 0.
- Scripts run in GraalJS **interpreter mode** — this is intentional and matches how the image has always behaved. As of GraalVM 25, in-process JIT compilation of JavaScript requires a GraalVM JDK; the image ships Temurin JRE 25 (which matches the Truffle 25.x runtime requirement, keeping startup free of version-mismatch warnings). Short BPMN scripts are unaffected; if you run JS-heavy workloads, consider a GraalVM-based image.

## Request - Response Logging
In order to enable request - response logging, set the following logging level to DEBUG. To cancel, set to INFO.

```xml
<logger name="org.glassfish.jersey.logging.LoggingFeature" level="DEBUG" />
```

## Use CadenzaFlow UIs Through OpenID Authentication
No need to setup users to access the CadenzaFlow user interfaces like Cockpit, Tasklist, and Admin. Just use Keycloak's OpenID authentication to access the UIs with the help of the [OpenID auth for Keycloak](https://github.com/cadenzaflow/cadenzaflow-keycloak) plugin.

## Prometheus Metrics

Micrometer's Prometheus registry is included; the scrape endpoint is exposed on the
management port: `http://<host>:16000/actuator/prometheus`. It is whitelisted for
scraping (no JWT), like `/actuator/metrics` — the management port is expected to stay
internal (not exposed through the Ingress). Point your `ServiceMonitor`/scrape config
at port `16000`, path `/actuator/prometheus`.

## Deployment Configuration

The application is configured through environment variables. The tables below list the key variables; see [application.yml](src/main/resources/application.yml), [config-cadenzaflow.yml](src/main/resources/config-cadenzaflow.yml), and [config-security.yml](src/main/resources/config-security.yml) for the full set of defaults.

### General

| Environment Variable | Description | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Comma-separated list of active Spring profiles. | — |
| `LOGGING_CONFIG` | Path to a custom Logback configuration file. | built-in default |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Strategy for handling forwarded headers (`framework`, `native`, `none`). Set to `framework` when running behind a reverse proxy or Ingress. | `none` |

### Database

| Environment Variable | Description | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC connection URL. | `jdbc:postgresql://postgresql:5432/db?useUnicode=yes&characterEncoding=UTF-8&currentSchema=cadenzaflow` |
| `SPRING_DATASOURCE_USERNAME` | Database user. | `cadenzaflow` |
| `SPRING_DATASOURCE_PASSWORD` | Database password. | — |
| `SPRING_DATASOURCE_HIKARI_SCHEMA` | Schema used by the engine tables and the HikariCP connection pool. | `cadenzaflow` |

The engine stores its state in `ACT_*` tables. The schema is backward-compatible with an existing `opentmf-camunda7` database — pointing this service at such a schema is the supported migration path: set the three variables above to the current values (e.g. schema `camunda7`).

### Keycloak / OpenID

| Environment Variable | Description | Default |
|---|---|---|
| `PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ISSUER_URL` | Server-to-server Keycloak realm URL. Used by the identity provider plugin to query users/groups and by Spring Security for token exchange, JWK Set retrieval, and user-info calls. | `http://keycloak.iam-dev.svc.cluster.local/realms/devtest` |
| `PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ADMIN_URL` | Keycloak Admin REST API URL for the realm. | `http://keycloak.iam-dev.svc.cluster.local/admin/realms/devtest` |
| `PLUGIN_IDENTITY_KEYCLOAK_CLIENT_ID` | OAuth2 client ID registered in Keycloak. | `xxx` |
| `PLUGIN_IDENTITY_KEYCLOAK_CLIENT_SECRET` | OAuth2 client secret. | `xxx` |
| `KEYCLOAK_URL_AUTH` | Browser-facing Keycloak realm URL. Used for the OAuth2 authorization redirect and the CSP `connect-src` header. Defaults to `PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ISSUER_URL`, so in standard deployments only the issuer URL needs to be set. Override this when browsers reach Keycloak at a different address than the application (e.g. Citrix, split-DNS, external Ingress). | same as `PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ISSUER_URL` |
| `OPENTMF_SECURITY_USER_CLAIM` | JWT claim used as the authenticated user's identity (e.g. `email`, `preferred_username`, `sub`). | `email` |
| `OPENTMF_SECURITY_AUTHORITIES_CLAIM` | JWT claim that carries the user's role/group list. | `groups` |

### History Cleanup

Every process instance leaves history data (`ACT_HI_*` tables) behind when it finishes. Without cleanup those tables grow forever, so the image ships with **removal-time-based history cleanup enabled out of the box**: when an instance ends, the engine stamps it with a removal time (`end time + TTL`), and a nightly cleanup job deletes everything whose removal time has passed, in batches, inside a configured time window.

The TTL for an instance is resolved in this order:

1. The `historyTimeToLive` set on the **process definition itself** (the `camunda:historyTimeToLive` attribute in the BPMN XML, also editable per definition via Cockpit or `PUT /process-definition/{id}/history-time-to-live`) — takes precedence.
2. Otherwise the **global default**: `P92D` (92 days) in this image.
3. Cockpit **batch operations** (migrations, cancellations, ...) have their own TTL: `batchOperationHistoryTimeToLive`, `P30D` in this image.

Shipped schedule: batch window **01:00–05:00** (times are in the JVM timezone — the Docker image pins it to UTC), `historyCleanupDegreeOfParallelism: 2`. Cleanup is cluster-safe — jobs are acquired through the shared database, so multiple pods never run the same job twice, and the degree of parallelism is cluster-wide, not per pod.

To change the schedule, **do not use `CADENZAFLOW_BPM_...` environment variables**: Spring's relaxed binding does not reliably map environment-variable names onto the camelCase map keys under `generic-properties.properties`. Instead, mount an override file (see `SPRING_CONFIG_ADDITIONAL_LOCATION` above) containing, e.g.:

```yaml
cadenzaflow.bpm:
  generic-properties:
    properties:
      historyCleanupBatchWindowStartTime: "22:00"
      historyCleanupBatchWindowEndTime: "06:00"
      historyCleanupDegreeOfParallelism: 4
```

A `"00:00"`–`"00:00"` window means continuous cleanup; per-weekday windows are available via `sundayHistoryCleanupBatchWindowStartTime` and friends.

Operations and monitoring (all under `/cadenzaflow/v1/engine-rest`):

| Call | Purpose |
|---|---|
| `GET /history/cleanup/configuration` | The currently configured batch window. |
| `GET /history/cleanup/job` (or `/jobs`) | Inspect the scheduled cleanup job(s): next due date, retries, failures. |
| `POST /history/cleanup` | Schedule an immediate cleanup run (outside the window). |
| `GET /history/cleanup/cleanable-process-instance-report` | Per-definition report of finished vs. cleanable instances — useful to gauge backlog and TTL coverage. |
| `POST /history/process-instance/set-removal-time` | Backfill removal times as a batch. Instances that finished **before** a TTL was in effect carry no removal time and are never cleaned — run this once after introducing TTLs (or use the equivalent Cockpit batch operation). |

### Split-URL deployments

In environments where the browser-facing Keycloak address differs from the address reachable by the application (e.g. when accessing through Citrix or a corporate proxy), set both variables independently:

```yaml
# K8s service address — used for all server-to-server communication
PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ISSUER_URL: https://keycloak.iam.svc.cluster.local/realms/myRealm
# Browser-facing address — used for OAuth2 authorization redirects
KEYCLOAK_URL_AUTH: https://keycloak.internal.company.com/realms/myRealm
```

In standard deployments where a single URL is reachable from both the browser and the application, only `PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAK_ISSUER_URL` needs to be set; `KEYCLOAK_URL_AUTH` inherits the same value automatically.

### Overriding Role Mappings
It is possible to override the default role mappings for specific endpoints. Given that we set:
- `SPRING_PROFILES_ACTIVE=test`
- `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/application/`

the following configuration can be supplied in a file mounted on `/application/application-test.yml`. Of course different roles can be specified than the below example.

```yaml
---
opentmf:
  security:
    secure-endpoints:
      - method: GET
        path: /engine-rest/**
        roles:
          - reader
          - writer
          - admin
      - method: POST
        path: /engine-rest/**
        roles:
          - writer
          - admin
      - method: PUT
        path: /engine-rest/**
        roles:
          - writer
          - admin
      - method: DELETE
        path: /engine-rest/**
        roles:
          - writer
          - admin
```

Actuator endpoints live on the separate management port (16000) and are governed by
the dedicated `opentmf.security.management` block (openid-rbac-security 2.x), e.g.:

```yaml
opentmf:
  security:
    management:
      secure-endpoints:
        - method: GET
          path: /actuator/env
          roles:
            - admin
```

## AWS IAM Authentication Support

For deployments on AWS, a dedicated image variant is published with the `-aws` tag suffix (e.g. `opentmf-cadenzaflow:1-aws`). This variant bundles the following runtime libraries:

| Library | Purpose |
|---|---|
| [AWS Advanced JDBC Wrapper](https://github.com/aws/aws-advanced-jdbc-wrapper) | IAM-based authentication for Amazon RDS / Aurora PostgreSQL — no database passwords needed |
| [AWS MSK IAM Auth](https://github.com/aws/aws-msk-iam-auth) | IAM-based authentication for Amazon MSK (Managed Kafka) |
| [AWS STS SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/sts.html) | Required for IRSA (IAM Roles for Service Accounts) / WebIdentity credential resolution on EKS |

### RDS IAM authentication example

Configure the AWS JDBC Wrapper as the datasource driver and let IAM handle credentials:

```yaml
spring:
  datasource:
    url: jdbc:aws-wrapper:postgresql://your-cluster.cluster-xxxx.eu-central-1.rds.amazonaws.com:5432/cadenzaflow
    driver-class-name: software.amazon.jdbc.Driver
    hikari:
      data-source-properties:
        wrapperPlugins: iam
        targetDriverClassName: org.postgresql.Driver
    username: your_iam_db_user
    # no password — the wrapper obtains short-lived tokens via IAM
```

On EKS, attach an IAM role to the pod's service account (IRSA) and ensure the role has `rds-db:connect` permission on the database resource.

### Building a local AWS variant

```shell
mvn -Dmaven.test.skip -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -P docker,aws-iam clean package
```

Or directly with Docker:

```bash
docker build -f Dockerfile_release --build-arg MAVEN_PROFILES=repackage,aws-iam -t local/opentmf-cadenzaflow:aws .
```

## Building a Local Docker Image
You can build a local docker image with the following command:
```shell
mvn -Dmaven.test.skip -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -P docker clean package
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full version history.
