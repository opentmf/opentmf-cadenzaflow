# Plan: incident operations endpoints (grouped report, group retry, pageable list)

> **Status (2026-09-02): implemented in 1.2.0 (PR #8), with deliberate departures from
> the layout below.** The normative description is now README §9.3 and
> `ci/openapi-service.yaml`. What changed against this plan:
>
> | Plan | Implementation |
> |---|---|
> | Spring MVC controllers under `/opentmf/incidents/**`, own ACL rules | JAX-RS resources registered in `JerseyConfig`, mounted **inside** the engine REST application under `/engine-rest/extensions/incident` (`/groups`, `/retry`); they inherit the `/engine-rest/**` rules, plus one dedicated rule for the retry POST |
> | `tmf630-toolkit-paging-sorting-autoconfigure` + `@Tmf630Response` | `tmf630-toolkit-paging-sorting-core` called programmatically (`Tmf630JaxrsPaging`); the autoconfigure module is Spring-MVC-only and never sees a Jersey request |
> | Package `org.opentmf.cadenzaflow.incident` | `org.opentmf.cadenzaflow.extensions` (resource / service / repository / model) |
> | Selector = root key, definition key, activity, type, tenant | plus `calledFrom` and the echoed time window — the caller is part of the group key, so a retry cannot cross into the sibling group of the same child BPMN called from elsewhere |
> | Part C deferred (decision 6) | implemented in the same release |
> | Version 1.1.6 vs 1.2.0 (decision 1) | 1.2.0 |
>
> Wherever the text below says `/opentmf/incidents…`, read `/engine-rest/extensions/incident…`.


**Owner decision (2026-09-01):** DNMS needs three incident capabilities that the stock
engine REST API does not provide in a usable form at DNMS volume (about 10 million
process starts a day, hundreds of thousands of active incidents at a time):

- **Part A** — active incidents for one root BPMN *and everything it called*, grouped
  by BPMN, task and incident type, with counts. Aggregation happens in the database.
- **Part B** — retry every incident of one group from Part A, scoped to the same call
  tree, as engine batches.
- **Part C** — a pageable, sortable incident list with the TMF-630 paging contract the
  rest of the estate uses (`offset`/`limit`, `X-Total-Count`, `Link`, 200/206/416).

This plan is self-contained for a fresh session in this repository. It was prepared on
`develop` at `4ac4a24` (pom `1.1.6-SNAPSHOT`). Every engine fact below was read from the
cadenzaflow **1.2.2** sources in the local Maven cache
(`org.cadenzaflow.bpm:cadenzaflow-engine` and `cadenzaflow-engine-rest-core-jakarta`,
`-sources.jar`), not from Camunda documentation. Re-verify anything marked ❓ before
relying on it.

Only **active** incidents (`ACT_RU_INCIDENT`) are in scope. History is not.

---

## 1. Verified facts

### 1.1 Engine behaviour that shapes the design

| Fact | Where verified | Consequence |
|---|---|---|
| An incident in a called process is **copied into every ancestor** instance. The copy has the same `INCIDENT_TYPE_`, `CAUSE_INCIDENT_ID_` = the child's incident, `ROOT_CAUSE_INCIDENT_ID_` = the originating one. The originating row has `ID_ = ROOT_CAUSE_INCIDENT_ID_`. | `IncidentEntity.createRecursiveIncidents()` | Counting rows across a tree multiplies each failure by call depth. Filter `i.ID_ = i.ROOT_CAUSE_INCIDENT_ID_`. |
| The copies do **not** carry `CONFIGURATION_` (job / external-task id). | same | Only originating incidents can be retried. |
| `ACT_RU_EXECUTION.ROOT_PROC_INST_ID_` is always set, for plain and for called instances. | `ExecutionEntity` lines 452–465 | The call tree is one join away; no BPMN parsing, works with expression-valued `calledElement`. |
| `ACT_RU_EXECUTION.SUPER_EXEC_` points at the parent's call-activity execution; that execution's `ACT_ID_` is the call activity id. | schema + `ExecutionEntity` | "Called from BPMN X through call activity Y" is a left join. |
| Runtime `IncidentQuery` has `processDefinitionKeyIn`, `processDefinitionId`, `activityId`, `failedActivityId`, `incidentType`, `incidentMessageLike`, timestamp before/after, `tenantIdIn`, `jobDefinitionIdIn`, `causeIncidentId`, `rootCauseIncidentId`, `configuration`, `executionId`, `processInstanceId`. It has **no** root-instance filter and no `id = rootCauseIncidentId` filter. | `IncidentQuery.java`, `IncidentQueryDto.java` | Group drill-down (Part C) works on the stock query; tree scoping does not. |
| Stock REST list paging: `GET /engine-rest/incident?firstResult=&maxResults=&sortBy=&sortOrder=` plus `GET /engine-rest/incident/count`. Twelve sort fields: `incidentId, incidentMessage, incidentTimestamp, incidentType, executionId, activityId, processInstanceId, processDefinitionId, causeIncidentId, rootCauseIncidentId, configuration, tenantId`. | `IncidentRestServiceImpl`, `IncidentQueryDto.VALID_SORT_BY_VALUES` | Part C maps TMF sort names onto exactly these. |
| The only shipped aggregation is `GET /process-definition/{id}/statistics?incidents=true` (per activity id × type, one definition **version**, counts the propagated copy on the call activity, no names) and `GET /process-definition/statistics?incidents=true`. | `Statistics.xml` lines 655–693 | Not sufficient for Part A. |
| Setting retries > 0 **resolves the incident and its copies** inside the engine, for jobs and for external tasks. | `JobEntity.setRetries()` 312–331, `ExternalTaskEntity.setRetriesAndManageIncidents()` 443–449 | Part B needs no separate resolve step. |
| Batch APIs: `ManagementService.setJobRetriesAsync(List<String> jobIds, int retries)` and `ExternalTaskService.setRetriesAsync(List<String> externalTaskIds, ExternalTaskQuery q, int retries)` (query may be null). Batch types `set-job-retries` / `set-external-task-retries`. | `ManagementService.java:875`, `ExternalTaskService.java:428`, `Batch.java:47-48` | Part B feeds id lists to these. |
| `invocationsPerBatchJob` defaults to **1**; `batchJobsPerSeed` 100. | `ProcessEngineConfigurationImpl` 409, 549 | A 200k-id batch seeds 200k jobs. See §5.4. |
| Indexes: `ACT_RU_INCIDENT(PROC_INST_ID_)`, `(PROC_DEF_ID_)`, `(ROOT_CAUSE_INCIDENT_ID_)`, `(CONFIGURATION_)`, `(TENANT_ID_)`, `(JOB_DEF_ID_)`; `ACT_RU_EXECUTION(ROOT_PROC_INST_ID_)`, `(SUPER_EXEC_)`, `(PROC_DEF_ID_)`, PK on `ID_`. | `activiti.postgres.create.engine.sql` | Every join in §4.2 lands on a PK or an index. No new index. |
| The engine runs with a **table prefix**: `table-prefix: ${spring.datasource.hikari.schema}.` (`cadenzaflow.` in the image, `public.` in the IT profile). | `config-cadenzaflow.yml:21`, `application-it.yml` | All SQL must take the prefix from `ProcessEngineConfigurationImpl.getDatabaseTablePrefix()`. Never bare table names. |
| Engine authorization is **enabled** (`authorization.enabled: true`), but the engine authentication filter is registered only for the webapp paths (`/app/*`, `/api/*`). Calls under `/engine-rest` and any new Spring MVC path run with **no engine identity**, so engine authorization checks are skipped for them. | `config-cadenzaflow.yml:15`, `CadenzaFlowSpringSecurityOAuth2AutoConfiguration.java:62-76` | Edge RBAC (openid-rbac-security) is the real gate. **Do not** set an engine user id in Part B — see §5.5. |

### 1.2 Application facts

| Fact | State |
|---|---|
| Jersey (`JerseyConfig`, `@ApplicationPath("/engine-rest")`) owns `/engine-rest/*`. Spring MVC (`spring-boot-starter-web` via the REST starter) serves everything else. Webapp `application-path: /`, so the webapp filter claims `/app/*` and `/api/*`. | New endpoints go in **Spring MVC** under a fresh prefix (§2.3); not under `/engine-rest`, `/app`, `/api`. |
| `spring-boot-starter-jdbc` is a dependency → a `JdbcTemplate` / `NamedParameterJdbcTemplate` is auto-configured on the single DataSource the engine uses. | Use it for the SQL in Parts A and B. |
| No JPA, no Hibernate, no QueryDSL on the classpath. | tmf630-toolkit **attribute filtering** is out. Its **paging/sorting/fields** module is JPA-free (deps: `spring-webmvc`, `spring-data-commons`, `jackson-annotations`, optional `jakarta.persistence-api`) and works on any `Page<T>`. |
| tmf630-toolkit coordinates: `org.opentmf.query:tmf630-toolkit-paging-sorting-autoconfigure`, latest release **3.1.1** on Central; built against Spring Boot 4.1.0, same as this repo. `opentmf-versions` BOM is **not** imported here. | Pin via `<tmf630-toolkit.version>3.1.1</tmf630-toolkit.version>` property (dependency-pinning rule). |
| Toolkit config prefix `opentmf.tmf630.paging`: `default-limit` 50, `max-limit` 500, `strict-mode` true (out-of-range offset → 416), `sort-allowlist` (empty = unrestricted). Field selection prefix `opentmf.tmf630.field-selection`. Annotation `org.opentmf.query.tmf630.annotation.Tmf630Response`; `Pageable` resolved as offset/limit by `TmfPageableHandlerMethodArgumentResolver`. | Part C uses `sort-allowlist` instead of hand-written sort validation. |
| Security: `config-security.yml` `opentmf.security.secure-endpoints` rules match `/engine-rest/**` only (GET → reader/writer/admin; POST/PUT/DELETE → writer/admin). IT profile (`application-it.yml`) overrides with `ebu-federation-*` role names. | Both files need rules for the new prefix. |
| ArchUnit (`ArchitectureTests`): no field injection; SLF4J only, logger field named `log`; no generic exceptions thrown; **every `@RequestParam` / `@PathVariable` / `@RequestHeader` must declare an explicit name** (build has no `-parameters`); no direct self-calls of `@Cacheable`/`@Transactional`. | Write controllers accordingly. |
| Quality gates (JaCoCo, `haltOnFailure`): BUNDLE LINE / INSTRUCTION / BRANCH ≥ 80 %, **CLASS missed = 0**. PIT runs with survivor review, no threshold. | Every new class needs at least one test; aim for 80 %+ per class. |
| ITs (`*IT`, failsafe, **one JVM per IT class**): Testcontainers PostgreSQL 18 (`jdbc:tc:postgresql:18.0-alpine:///db`) + Keycloak 26.4.1 with `realm/dsync-realm.json`, profile `it`, `RANDOM_PORT`. Existing ITs deploy hand-written BPMN XML strings (not `Bpmn.createExecutableProcess`, which injects a TTL). | Part tests follow `OpenTmfCadenzaFlowApplicationIT` for boot, token and BPMN fixture style. |
| Unit tests: plain JUnit 5 / Mockito; no H2 Spring context test exists. | Keep DB-touching assertions in ITs; unit-test pure logic. |
| CHANGELOG already has `## [1.1.6] - 2026-08-20` matching the pom. | Append there unless the owner re-versions (§9). |

---

### 1.3 What the DNMS estate already provides (checked 2026-09-02)

**dnms-journal already ships an engine-ops BFF** (`EngineOpsApi`, develop, deployed):
`GET /ops/engine/incidents/summary|incidents|incidents/grouped`, `POST .../{id}/retry`
(dnms-write), `POST .../retry-batch` (dnms-admin, capped, count-confirmed, audited to
its `engine_retry_audit` ledger), `GET /ops/engine/batches/{id}`,
`GET /ops/engine/instances?requestId=`, `GET /ops/engine/cockpit-link`. It is a
pass-through over **engine-rest history queries**: fetch per definition key, filter
originating (`id == rootCauseIncidentId`) and group (`incidentType` + activity) in the
BFF, cursor-page over a short-lived snapshot.

**Stock engine-rest also covers the single-definition live view**:
`GET /process-definition/key/{key}/statistics?incidents=true` returns per-activity
`{incidentType, incidentCount}` rows in one call (`?failedJobs=true` adds job counts),
and the runtime `GET /incident?processDefinitionKeyIn=...` DOES exist on this engine
(verified in `cadenzaflow-engine-rest-core-jakarta` 1.2.2: `IncidentQueryDto` carries
`processDefinitionKeyIn`). Their limits: key-addressed statistics resolve the **latest
version only**, and neither distinguishes originating from propagated incidents — on
any non-leaf definition the numbers double-count each failure once per tree level.

**So this plan is the engine-native layer, not a duplicate.** What only it can do:

- **Part A**: true call-tree scoping (`ROOT_PROC_INST_ID_` join) across all deployed
  versions with propagation-free grouping — impossible over REST (no root-instance
  filter, no originating filter; the journal pays fetch-all + client-side filtering).
- **Part B**: selector-resolved group retry at engine scale (the journal's retry-batch
  collects ids by paging history REST and is capped accordingly).

**Consumer contract**: the DNMS ops-ui keeps calling **only the journal BFF** (one auth
model, one CORS surface). Once these endpoints ship, the journal swaps its
grouped/summary/retry-batch internals to delegate here — a journal follow-up row, not
part of this change. Part C overlaps the journal's existing list and the stock runtime
query; owner decision 6.

## 2. Decisions

### 2.1 Aggregate in the database, roll up in Java
At DNMS volume the GROUP BY runs in SQL (§4.2). The SQL groups on the **definition id**
so versions come out separately; Java rolls the few hundred rows up to definition-key
level and attaches names. Java never sees individual incidents in Part A.

### 2.2 Retry by selector, resolved in the database
Part A returns a **selector** (root key, definition key, activity id, incident type,
optional tenant/version), not id lists. Part B re-runs the same join with the selector,
selects only `CONFIGURATION_`, and hands the ids to the engine batch API. This is the
only way to retry a shared child BPMN for **one** root's instances without touching its
other parents; the engine's own retry-by-query cannot express the tree.

### 2.3 All three endpoints in Spring MVC under one new prefix
Part C needs Spring MVC (the toolkit is MVC-only), Parts A and B benefit from the same
`@Tmf630Response` field selection and from plain constructor injection. One prefix means
one security block, one OpenAPI section, one README section. **Proposed prefix:
`/opentmf/incidents`** (owner may rename, §9). `JerseyConfig` stays untouched.

### 2.4 No engine identity, log the principal instead
Setting an engine user id would switch engine authorization **on** for that command, and
the caller would then need engine-level batch CREATE and job/external-task UPDATE
grants. Instead, Part B logs one INFO line per request with the edge principal, the
selector, the incident count and the batch ids. The log pipeline is the audit trail.

### 2.5 Read from the engine query in Part C, not SQL
The stock `IncidentQuery` already has every filter the UI needs for a list and for group
drill-down. Offset paging plus a filtered count is fast on the existing indexes at this
table size. Only tree scoping is missing, and it is deliberately **not** part of Part C's
first cut (§6.6 lists how to add it later without changing the contract).

---

## 3. Package and class layout

New package `org.opentmf.cadenzaflow.incident` (outside `config`, which the
identity-containment rule reserves for provider adapters).

```
org.opentmf.cadenzaflow.incident
├── IncidentOperationsProperties      @ConfigurationProperties("cadenzaflow.incidents") @Validated
├── EngineSqlSupport                  table prefix from engine config, validated; shared by the two repositories
├── definition/
│   ├── ProcessDefinitionLookup       key / name / version by definition id (RepositoryService, engine cache)
│   └── ActivityNameLookup            elementId → name per definition id (BpmnModelInstance, ConcurrentHashMap)
├── group/
│   ├── IncidentGroupController       GET  /opentmf/incidents/groups
│   ├── IncidentGroupRepository       the GROUP BY select (NamedParameterJdbcTemplate)
│   ├── IncidentGroupRow              one SQL row (per definition version)
│   ├── IncidentGroupRollup           rows → IncidentGroup (key level, names attached)
│   ├── IncidentGroup / CalledFrom / IncidentGroupSelector   response records
│   ├── IncidentGroupRetryController  POST /opentmf/incidents/groups/retry
│   ├── IncidentGroupRetryService     select ids → chunk → engine batches → log
│   ├── IncidentGroupRetryRequest     selector + retries (validated)
│   └── IncidentGroupRetryResult      incident count + batch descriptors
└── list/
    ├── IncidentListController        GET  /opentmf/incidents
    ├── IncidentListService           IncidentQuery build, count, listPage, enrich → Page<IncidentRow>
    ├── IncidentListFilter            request-parameter record
    ├── IncidentSortMapping           TMF sort name → IncidentQuery orderBy
    └── IncidentRow                   response record (engine fields + enrichment + href)
```

Records for DTOs; constructor injection; `@RestController` + `@RequestMapping`.

---

## 4. Part A — grouped report

### 4.1 Contract

```
GET /opentmf/incidents/groups?rootProcessDefinitionKey=orderFulfilment
      [&incidentType=failedExternalTask|failedJob]
      [&tenantId=...]
      [&minIncidents=1]
      [&fields=...]
```

`rootProcessDefinitionKey` is required (400 otherwise). Response is a JSON array sorted
by `incidentCount` descending. Role: GET rule → reader/writer/admin.

```json
[
  {
    "rootProcessDefinitionKey": "orderFulfilment",
    "processDefinitionKey": "reserveStock",
    "processDefinitionName": "Reserve stock",
    "processDefinitionVersions": [7, 8],
    "activityId": "callWms",
    "activityName": "Call WMS",
    "activityType": "serviceTask",
    "incidentType": "failedExternalTask",
    "tenantId": null,
    "calledFrom": { "processDefinitionKey": "orderFulfilment", "callActivityId": "reserve" },
    "incidentCount": 1842,
    "processInstanceCount": 1842,
    "oldestIncident": "2026-08-31T22:10:04.113+0000",
    "newestIncident": "2026-09-01T07:58:41.902+0000",
    "sampleMessage": "WMS returned 503 for reservation ...",
    "selector": {
      "rootProcessDefinitionKey": "orderFulfilment",
      "processDefinitionKey": "reserveStock",
      "activityId": "callWms",
      "incidentType": "failedExternalTask",
      "tenantId": null
    }
  }
]
```

`calledFrom` is `null` for incidents in the root BPMN itself. `selector` is exactly the
body Part B accepts (minus `retries`), so the UI never assembles it.

### 4.2 The select (`IncidentGroupRepository`)

`${p}` is the validated table prefix. Parameters are bound, never concatenated.

```sql
select rd.KEY_  as ROOT_KEY_,
       pd.ID_   as DEF_ID_, pd.KEY_ as DEF_KEY_, pd.NAME_ as DEF_NAME_, pd.VERSION_ as DEF_VERSION_,
       i.ACTIVITY_ID_, i.INCIDENT_TYPE_, i.TENANT_ID_,
       sd.KEY_  as PARENT_KEY_, sup.ACT_ID_ as CALL_ACTIVITY_ID_,
       count(*)                             as INCIDENTS_,
       count(distinct i.PROC_INST_ID_)      as INSTANCES_,
       min(i.INCIDENT_TIMESTAMP_)           as OLDEST_,
       max(i.INCIDENT_TIMESTAMP_)           as NEWEST_,
       min(substr(i.INCIDENT_MSG_, 1, 300)) as SAMPLE_MSG_
from   ${p}ACT_RU_INCIDENT  i
join   ${p}ACT_RU_EXECUTION pi   on pi.ID_   = i.PROC_INST_ID_
join   ${p}ACT_RU_EXECUTION root on root.ID_ = pi.ROOT_PROC_INST_ID_
join   ${p}ACT_RE_PROCDEF   rd   on rd.ID_   = root.PROC_DEF_ID_
join   ${p}ACT_RE_PROCDEF   pd   on pd.ID_   = i.PROC_DEF_ID_
left join ${p}ACT_RU_EXECUTION sup on sup.ID_ = pi.SUPER_EXEC_
left join ${p}ACT_RE_PROCDEF   sd  on sd.ID_  = sup.PROC_DEF_ID_
where  rd.KEY_ = :rootKey
and    i.ID_   = i.ROOT_CAUSE_INCIDENT_ID_
[and   i.INCIDENT_TYPE_ = :incidentType]
[and   i.TENANT_ID_ = :tenantId]
group by rd.KEY_, pd.ID_, pd.KEY_, pd.NAME_, pd.VERSION_,
         i.ACTIVITY_ID_, i.INCIDENT_TYPE_, i.TENANT_ID_, sd.KEY_, sup.ACT_ID_
[having count(*) >= :minIncidents]
order by INCIDENTS_ desc
```

Notes:
- The row set is bounded by definitions × activities × types. No paging.
- `substr` is portable across PostgreSQL and H2; keep it, do not use `left()`.
- The group key is `i.ACTIVITY_ID_`, deliberately NOT `FAILED_ACTIVITY_ID_`: the engine
  leaves `FAILED_ACTIVITY_ID_` null on external-task incidents (dnms ops-ui finding 11 —
  grouping on it collapsed every external-task failure into one empty-activity group).
- ❓ Run `EXPLAIN (ANALYZE, BUFFERS)` once on a DNMS-sized copy to confirm the planner
  probes `ACT_RU_EXECUTION` by primary key (nested loop) rather than hashing the whole
  table. Record the plan in the PR description.

### 4.3 Roll-up (`IncidentGroupRollup`)
Group SQL rows by (`DEF_KEY_`, `ACTIVITY_ID_`, `INCIDENT_TYPE_`, `TENANT_ID_`,
`PARENT_KEY_`, `CALL_ACTIVITY_ID_`); sum counts, min/max timestamps, collect
`DEF_VERSION_` sorted, take the sample message from the newest version. Names:
`processDefinitionName` from the highest version present; `activityName` and
`activityType` via `ActivityNameLookup` for that definition id (`FlowNode.getName()`,
element type name). A missing element (activity id no longer in the model) yields `null`
names, never an error.

### 4.4 Lookups
- `ProcessDefinitionLookup`: `repositoryService.getProcessDefinition(id)` — the engine's
  deployment cache makes this cheap; no extra cache needed.
- `ActivityNameLookup`: `repositoryService.getBpmnModelInstance(id)` once per definition
  id → `Map<String, ActivityInfo>`; `ConcurrentHashMap.computeIfAbsent`. Definitions are
  immutable, so no eviction. Guard: a definition deleted between the select and the lookup
  → catch the engine's not-found exception, return an empty map.

### 4.5 `EngineSqlSupport`
Reads `((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
.getDatabaseTablePrefix()`, validates it against `^[A-Za-z0-9_]*\.?$`, and exposes
`table("ACT_RU_INCIDENT")`. Fail fast at startup on an invalid prefix.

---

## 5. Part B — retry one group

### 5.1 Contract

```
POST /opentmf/incidents/groups/retry
{
  "rootProcessDefinitionKey": "orderFulfilment",
  "processDefinitionKey": "reserveStock",
  "activityId": "callWms",
  "incidentType": "failedExternalTask",
  "tenantId": null,
  "processDefinitionVersion": null,
  "retries": 1
}
```

Validation (400): the four key fields non-blank; `incidentType` ∈ {`failedJob`,
`failedExternalTask`}; `retries` ≥ 1. Role: POST rule → writer/admin.

Response 200:
```json
{
  "incidentCount": 1842,
  "batches": [
    { "id": "3f1c...", "type": "set-external-task-retries", "size": 1842 }
  ]
}
```
An empty group returns `{"incidentCount": 0, "batches": []}` (someone else retried
first; not an error). Clients poll `GET /engine-rest/batch/{id}` and
`GET /engine-rest/history/batch/{id}`.

### 5.2 Id select
Same joins and predicates as §4.2 plus `pd.KEY_ = :defKey`, `i.ACTIVITY_ID_ =
:activityId`, `i.INCIDENT_TYPE_ = :type`, optional `pd.VERSION_ = :version`; projection
`select distinct i.CONFIGURATION_ ... where i.CONFIGURATION_ is not null`. No GROUP BY.
200 000 ids ≈ a few MB, transient.

### 5.3 Batch creation (`IncidentGroupRetryService`)
Route on `incidentType`:
- `failedJob` → `managementService.setJobRetriesAsync(chunk, retries)`
- `failedExternalTask` → `externalTaskService.setRetriesAsync(chunk, null, retries)`

Chunk the id list by `cadenzaflow.incidents.retry.chunk-size` (default **20000**). One
engine batch per chunk; a failing chunk does not stall the others. Return every batch id.

### 5.4 Engine batch tuning (owner decision, not in this change)
With `invocationsPerBatchJob = 1` a 200k batch seeds 200k execution jobs, 100 per seed
cycle. Raising `invocations-per-batch-job-by-batch-type` for `set-job-retries` and
`set-external-task-retries` (e.g. 100) is a separate, engine-wide decision. Document the
knob in README §8.7; do not change it here.

### 5.5 Audit line
One INFO log per request:
`incident group retry by {principal}: {selector} → {n} incidents, batches {ids}`.
Principal from `SecurityContextHolder` (`Authentication.getName()`, the `email` claim per
`user-claim`). No engine identity is set (see §2.4).

### 5.6 Blast radius
Owner decision (§9): whether groups above a size threshold require `admin`. If yes, add
`cadenzaflow.incidents.retry.admin-threshold` and check the authority in the service
(403 with a message naming the threshold). Default in this plan: **no threshold**, since
RBAC already gates POST to writer/admin.

Two DNMS deployment facts belong here:

- **The retry Batch executes on the `camunda-jobs` flavor only** (the serving flavor
  runs with the job executor off) — batch progress stalls while `camunda-jobs` is down,
  and any `invocations-per-batch-job` tuning (§5.4) belongs to that deployment.
- **At the DNMS edge the journal ruling applies** (2026-08-20: bulk retry = admin +
  cap + count-confirmation + audit — blast radius is availability). The deploy-profile
  mapping for `POST /engine-rest/extensions/incident/retry` should be `dnms-admin` (owner
  decision 7); the in-service writer/admin rule stays for non-DNMS deployments.

---

## 6. Part C — pageable incident list

### 6.1 Dependency
```xml
<tmf630-toolkit.version>3.1.1</tmf630-toolkit.version>
...
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-paging-sorting-autoconfigure</artifactId>
  <version>${tmf630-toolkit.version}</version>
</dependency>
```
Not the `-all` artifact (it drags attribute filtering / QueryDSL). Run the two allowed
`versions:` goals afterwards (`-Paws,azure`).

### 6.2 Contract

```
GET /opentmf/incidents
    ?incidentType=&processDefinitionKeyIn=a,b&processDefinitionId=&processInstanceId=
    &activityId=&failedActivityId=&incidentMessageLike=&incidentTimestampAfter=
    &incidentTimestampBefore=&tenantIdIn=&jobDefinitionIdIn=&rootCauseIncidentId=
    &offset=0&limit=50&sort=-incidentTimestamp&fields=...
```

All filters optional; they map 1:1 onto `IncidentQuery`. Default sort
`-incidentTimestamp`. Response: JSON array of `IncidentRow` with the toolkit's headers
(`X-Total-Count`, `X-Result-Count`, `Content-Range`, `Link`) and status 200/206/416.
Role: GET rule → reader/writer/admin.

Group drill-down from Part A: `processDefinitionKeyIn=<key>&activityId=<activityId>
&incidentType=<type>` returns exactly that group's originating incidents. Propagated
copies fall out because a copy sits in a **different** definition on a call activity.

### 6.3 Row shape
```json
{
  "id": "…", "href": "/engine-rest/incident/…",
  "incidentType": "failedExternalTask", "incidentTimestamp": "…", "incidentMessage": "…",
  "activityId": "callWms", "activityName": "Call WMS", "failedActivityId": "callWms",
  "processDefinitionId": "reserveStock:8:…", "processDefinitionKey": "reserveStock",
  "processDefinitionName": "Reserve stock", "processDefinitionVersion": 8,
  "processInstanceId": "…", "executionId": "…",
  "causeIncidentId": "…", "rootCauseIncidentId": "…", "originating": true,
  "configuration": "…", "jobDefinitionId": null, "tenantId": null, "annotation": null
}
```
`originating` = `id.equals(rootCauseIncidentId)`. `href` points at the stock single-get.
Field selection via `fields=` comes from `@Tmf630Response` for free.

### 6.4 Service flow
1. `IncidentListFilter` → `IncidentQuery` (each non-null filter applied).
2. `IncidentSortMapping` applies the `Sort` orders: `incidentTimestamp → orderByIncidentTimestamp()`, etc., then `asc()`/`desc()`. Multi-field sort is supported by chaining.
3. `total = query.count()`; `content = query.listPage((int) pageable.getOffset(), pageable.getPageSize())`.
4. Enrich each `Incident` → `IncidentRow` through the two lookups (§4.4). At most 500 rows.
5. Return `new PageImpl<>(rows, pageable, total)` from a controller method annotated `@Tmf630Response`.

### 6.5 Configuration (plain defaults, no `${ENV:default}`)
```yaml
opentmf:
  tmf630:
    paging:
      sort-allowlist:
        - id
        - incidentTimestamp
        - incidentType
        - incidentMessage
        - activityId
        - processInstanceId
        - processDefinitionId
        - executionId
        - causeIncidentId
        - rootCauseIncidentId
        - configuration
        - tenantId
```
An unknown sort field is then a toolkit 400, not our code. `default-limit` 50 and
`max-limit` 500 are the toolkit defaults; do not restate them.

### 6.6 Later, if the list must be tree-scoped
Add `rootProcessDefinitionKey` to `IncidentListFilter`; when present, replace steps 1–3
with a JDBC select using the §4.2 joins plus `order by … offset :o limit :l` and a
matching `count(*)`. The `PageImpl` and everything above it stay unchanged. Not in this
change.

---

## 7. Cross-cutting changes

| Where | Change |
|---|---|
| `pom.xml` | toolkit dependency + property (§6.1). `hibernate-validator` is **not** in the dependency tree today, so add `spring-boot-starter-validation` (BOM-managed, no version) for `@Validated` on the request body and the properties class. |
| `config-security.yml` | Add `GET /opentmf/incidents/**` → reader, writer, admin; `POST /opentmf/incidents/**` → writer, admin. Keep the existing `/engine-rest/**` rules. |
| `src/test/resources/application-it.yml` | Same two rules with the `ebu-federation-*` role names used there. |
| `config-cadenzaflow.yml` or `application.yml` | `cadenzaflow.incidents.retry.chunk-size: 20000`; the sort allowlist (§6.5). |
| `docs/openapi.yaml` | Three new paths with parameters, request/response schemas, 400/401/403 responses; tag `incidents`. |
| `README.md` | §2 Interfaces: three rows. §4.1 use cases: UC for "view grouped incidents", "retry a group", "browse incidents" (next free UC numbers). §5.1: matching FRs. §6: a short processing-logic subsection with the join explained in one paragraph and the propagation caveat. §8.5: note the new prefix in the role table. §8.7: the batch tuning knob (§5.4). §9.3 Incidents: how to read the report, how to retry, how to poll the batch. |
| `CHANGELOG.md` | Under `## [1.1.6]` (or the re-versioned heading, §9), `### Added` entries written by effect: what an operator can now do, which manual Cockpit procedure it retires. |
| `docs/database-schema.md` | No change (read-only access, no new tables). |

---

## 8. Tests

### 8.1 Unit (surefire)
- `IncidentGroupRollupTests` — two versions of one key merge into one group; versions sorted; names taken from the highest version; missing element → null names; sample message from the newest version; ordering by count desc preserved.
- `IncidentSortMappingTests` — every allow-listed name maps to the right `orderBy*`; direction applied; multi-field chaining; unknown name throws (defensive, even though the toolkit rejects it first).
- `EngineSqlSupportTests` — `cadenzaflow.` and `public.` and empty prefixes accepted; `a;drop` rejected at construction.
- `IncidentGroupRetryServiceTests` (Mockito on the engine services and the repository) — routing by type; chunking boundaries (0, 1, chunk, chunk+1 ids); empty group returns zero and creates no batch; audit line contains principal and batch ids (assert via a Logback list appender, as `IncidentLoggerTests` does).
- `IncidentListServiceTests` — filter-to-query mapping (verify each setter called with the given value, none for nulls); `listPage` offset/limit taken from the `Pageable`; `PageImpl` total from `count()`.
- `IncidentGroupRetryRequestTests` — validation: bad type, retries 0, blank keys.
- Controller classes: cover through the IT below; if a class would otherwise be untested (CLASS missed = 0 gate), add a `@WebMvcTest`-free direct call test.

### 8.2 Integration (failsafe, one class → one JVM)
`IncidentOperationsIT`, bootstrapped like `OpenTmfCadenzaFlowApplicationIT` (Testcontainers
PostgreSQL + Keycloak, tokens for reader and writer users from the dsync realm).

Fixtures, hand-written XML strings:
- `parentFlow`: start → callActivity `reserve` (`calledElement="childFlow"`) → end.
- `childFlow`: start → serviceTask `callWms` (`camunda:type="external"`, `camunda:topic="wms"`) → end. Give the task `name="Call WMS"` and the process `name="Child flow"`.

Steps:
1. Deploy both; start 3 `parentFlow` instances.
2. In-process: `externalTaskService.fetchAndLock(10, "it-worker").topic("wms", 60000).execute()`, then `handleFailure(id, "it-worker", "boom", 0, 0)` for each → 3 incidents in the child, 3 copies in the parents. Assert `runtimeService.createIncidentQuery().count() == 6` (proves propagation happened, so the dedup is being tested).
3. **Part A**: `GET /opentmf/incidents/groups?rootProcessDefinitionKey=parentFlow` with the reader token → exactly one group; `incidentCount 3`, `processInstanceCount 3`, `processDefinitionKey childFlow`, `processDefinitionName "Child flow"`, `activityId callWms`, `activityName "Call WMS"`, `activityType serviceTask`, `incidentType failedExternalTask`, `calledFrom {parentFlow, reserve}`. A second call with `minIncidents=4` → empty array. Without a token → 401; with a token lacking the role → 403.
4. **Part C**: `GET /opentmf/incidents?processDefinitionKeyIn=childFlow&limit=2&sort=-incidentTimestamp` → 206, `X-Total-Count: 3`, 2 rows, `Link` header with `next`; `offset=2` → 200, 1 row; `offset=10` → 416; `sort=bogus` → 400; `fields=id,activityName` → only those plus `href`. Rows show `originating true`, `processDefinitionName`, `activityName`.
5. **Part B**: POST the group's `selector` + `retries 1` with the writer token → 200, `incidentCount 3`, one batch. The job executor is active in the IT profile (`job-execution` block in `config-cadenzaflow.yml`, no override in `application-it.yml`), so the batch runs on its own: poll `runtimeService.createIncidentQuery().count()` until 0 with a bounded wait (Awaitility if present, otherwise a small loop with a 30 s deadline). Assert: incident count 0; all three external tasks have `retries == 1`; `GET /engine-rest/history/batch/{id}` shows the batch completed. POST again → `incidentCount 0`, no batches. POST with the reader token → 403; `retries 0` → 400.

### 8.3 Gates
`mvn clean verify` green (JaCoCo bundle ≥ 80 %, no missed class), ArchUnit green, PIT
survivors reviewed for the new package. Image scans are a release-time requirement, not
per PR.

---

## 9. Owner decisions before merge

| # | Decision | Default in this plan |
|---|---|---|
| 1 | Release version: keep `1.1.6` or re-version to `1.2.0` for a feature of this size (pom + CHANGELOG heading) | append under `[1.1.6]` |
| 2 | URL prefix name | `/opentmf/incidents` |
| 3 | Admin-only above a group size threshold (§5.6) | no threshold |
| 4 | Engine batch tuning `invocations-per-batch-job-by-batch-type` (§5.4) | documented, unchanged |
| 5 | Should Part C expose `incidentMessageLike` at all (a leading-wildcard LIKE on a 4000-char column is a sequential scan at DNMS size) | expose, document the cost |
| 6 | Part C now or defer — the journal’s shipped list plus the stock runtime `processDefinitionKeyIn` query already serve the UI (§1.3) | defer; implement PRs 1–2 first |
| 7 | dnms-deploy ripple: the endpoints ride the existing `/engine-rest/**` rows of the mounted serving-profile ACL; only the dedicated `POST /engine-rest/extensions/incident/retry` row needs adding, mapped to **dnms-admin** (§5.6) | row lands with the adopting release |

---

## 10. Sequencing

Three pull requests, each leaving `develop` releasable:

1. **PR 1 — Part A + foundations.** Package, properties, `EngineSqlSupport`, lookups,
   report endpoint, security rules (both files), OpenAPI, README §2/§4/§5/§9.3, CHANGELOG,
   `IncidentOperationsIT` with the fixtures and the Part A assertions.
2. **PR 2 — Part B.** Retry endpoint, chunking, audit line, README §8.7 knob, IT steps 5.
3. **PR 3 — Part C.** Toolkit dependency, list endpoint, sort allowlist, IT step 4,
   `versions:` check.

---

## 11. Out of scope

- Historic incidents (`ACT_HI_INCIDENT`), resolved incidents.
- Keyset pagination; tree-scoped list (§6.6 documents the extension path).
- Full tmf630-toolkit attribute filtering via read-only JPA entities on engine tables
  (would add Hibernate and a second transaction manager next to the engine's MyBatis).
- Cockpit plugin UI; the consumer is the DNMS ops UI.
- Changing engine batch tuning (§5.4) or engine authorization for REST callers.
