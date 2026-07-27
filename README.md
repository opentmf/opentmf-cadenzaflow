# opentmf-cadenza-flow
An OpenTMF produced Spring Boot 4 microservice that embeds the [CadenzaFlow](https://github.com/cadenzaflow/cadenzaflow-bpm-platform) community edition (the maintained Camunda 7 fork) with the public Spin, and [OpenID auth for Keycloak](https://github.com/cadenzaflow/cadenzaflow-keycloak) plugins, as well as using OpenTMF's [openid-rbac-security](https://github.com/opentmf/openid-rbac-security) framework to secure the API endpoints.

This is the successor of [opentmf-camunda7](https://github.com/opentmf/opentmf-camunda7): after Camunda 7 reached community end-of-life with 7.24, CadenzaFlow continues the engine under the `org.cadenzaflow.*` namespace. This service uses CadenzaFlow's Spring Boot **4** starters (`cadenzaflow-bpm-spring-boot-starter-*-4`, Spring Framework 7).

## CadenzaFlow Dependencies (temporary notes)

CadenzaFlow >= 1.2.0 (including the Spring Boot 4 starters) is not yet on Maven Central,
but is published on the CadenzaFlow Nexus, which this pom declares as a repository:
`https://nexus.cadenzaflow.com/repository/cadenzaflow-nexus`. The platform artifacts
resolve from there without any local build.

The one exception is `cadenzaflow-keycloak`: its released 1.0.1 is compiled against
spring-web 6 and fails at engine bootstrap on Spring Framework 7
(`IncompatibleClassChangeError: HttpHeaders does not implement MultiValueMap`).
Until a spring-web-7-compiled artifact is published, install it locally:

```shell
git clone --depth 1 https://github.com/cadenzaflow/cadenzaflow-keycloak.git
cd cadenzaflow-keycloak
mvn -pl extension clean install -DskipTests -Dversion.spring-web6=7.0.6
```

(The 1.0.1 sources need no code changes for Spring 7 - only recompilation. Current
master additionally needs `getStatusCodeValue()` -> `getStatusCode().value()`.)

## Secure Endpoints
This project uses OpenTMF's [openid-rbac-security](https://github.com/opentmf/openid-rbac-security) (2.x line, built for Spring Boot 4) to secure its exposed endpoints.

The default openid-rbac-security configuration requires read or write access for GET, write access for POST, PUT, and DELETE endpoints. These defaults can be overridden. Please see [config-security.yml](src/main/resources/config-security.yml) for initial configuration.

## Incident Logging
OpenTMF's CadenzaFlow Incident Logger (`org.opentmf.cadenzaflow:cadenzaflow-incident-logger`) is used to write a log statement when a failed task has zero retry counts. It only logs — engine incident behavior is unchanged.

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

The engine's database schema is inherited from Camunda 7.24 (`ACT_*` tables). Pointing this service at an existing `opentmf-camunda7` schema is the intended migration path — set the three variables above to the current values (e.g. schema `camunda7`).

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

The image ships with nightly history cleanup enabled: a 01:00–05:00 UTC batch window, cleanup parallelism of 2, a global `historyTimeToLive` of `P92D` (individual BPMNs override it via `camunda:historyTimeToLive`), and a `P30D` TTL for batch-operation history. Cleanup is cluster-safe — jobs are acquired through the shared database, so multiple pods never run the same job twice.

To change the window, **do not use `CADENZAFLOW_BPM_...` environment variables**: Spring's relaxed binding does not reliably map environment-variable names onto the camelCase map keys under `generic-properties.properties`. Instead, mount an override file (see `SPRING_CONFIG_ADDITIONAL_LOCATION` above) containing, e.g.:

```yaml
cadenzaflow.bpm:
  generic-properties:
    properties:
      historyCleanupBatchWindowStartTime: "22:00"
      historyCleanupBatchWindowEndTime: "06:00"
```

A `"00:00"`–`"00:00"` window means continuous cleanup; per-weekday windows are available via `sundayHistoryCleanupBatchWindowStartTime` and friends. Instances that ended before a TTL was in effect carry no removal time and are never cleaned — run `POST /history/process-instance/set-removal-time` once (or the equivalent Cockpit batch operation) to backfill. Verify the schedule with `GET /history/cleanup/job`, or trigger an immediate run with `POST /history/cleanup`.

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

      # Actuator Endpoints That Require Authorization
      - method: GET
        path: /actuator/env
        roles:
          - admin
      - method: GET
        path: /actuator/env/**
        roles:
          - admin
```

## AWS IAM Authentication Support

For deployments on AWS, a dedicated image variant is published with the `-aws` tag suffix (e.g. `opentmf-cadenza-flow:1-aws`). This variant bundles the following runtime libraries:

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
docker build -f Dockerfile_release --build-arg MAVEN_PROFILES=repackage,aws-iam -t local/opentmf-cadenza-flow:aws .
```

## Building a Local Docker Image
You can build a local docker image with the following command:
```shell
mvn -Dmaven.test.skip -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -P docker clean package
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full version history.
