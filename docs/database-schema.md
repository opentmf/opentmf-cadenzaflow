# Database schema reference

`opentmf-cadenzaflow` embeds the CadenzaFlow BPM engine (a Camunda 7 derivative) and lets the
engine own its own schema. On start-up the engine creates every table it needs; the application
declares no JPA entities and ships no Flyway/Liquibase migrations of its own.

| | |
|---|---|
| **Database** | PostgreSQL |
| **Schema name** | `cadenzaflow` |
| **Table prefix** | `cadenzaflow.` — i.e. the schema qualifier *is* the prefix; table names themselves keep their stock `ACT_` names, un-renamed |
| **Total tables** | **49** |
| **Schema management** | `cadenzaflow.bpm.database.schema-update: true` → the engine runs `dbSchemaUpdate()` on boot and creates any missing sub-schema |

### Table count per group

| Prefix | Tables | Purpose |
|---|---:|---|
| `ACT_GE_*` | 3 | General / cross-cutting engine state |
| `ACT_RE_*` | 6 | Repository — deployed BPMN, DMN, CMMN and form definitions |
| `ACT_RU_*` | 16 | Runtime — live, mutable process state |
| `ACT_HI_*` | 18 | History — audit trail, removed by TTL-driven history cleanup |
| `ACT_ID_*` | 6 | Identity — engine-local users, groups and tenants |
| **Total** | **49** | |

### Which DDL scripts apply, and why

The engine's `AbstractPersistenceSession.dbSchemaUpdate()` creates each sub-schema behind a flag.
Evaluated against our configuration, **all seven PostgreSQL create scripts apply**:

| Script | Applies | Evidence |
|---|---|---|
| `activiti.postgres.create.engine.sql` | Yes | Unconditional — always created if `ACT_GE_PROPERTY` is absent. |
| `activiti.postgres.create.history.sql` | Yes | Guarded by `isDbHistoryUsed()`, which defaults to `true` (`ProcessEngineConfigurationImpl.isDbHistoryUsed = true`) and is not overridden anywhere in our config. |
| `activiti.postgres.create.identity.sql` | Yes | Guarded by `isDbIdentityUsed()`, default `true`. The Keycloak plugin (`KeycloakIdentityProviderPlugin.preInit`) only calls `setIdentityProviderSessionFactory(...)`; it does **not** set `dbIdentityUsed = false`. The tables are therefore created even though user/group *reads* are served from Keycloak. |
| `activiti.postgres.create.case.engine.sql` | Yes | Guarded by `isCmmnEnabled()`, default `true` (`ProcessEngineConfigurationImpl:621`). Our config never disables CMMN. |
| `activiti.postgres.create.case.history.sql` | Yes | `isCmmnEnabled() && isDbHistoryUsed()` — both true. |
| `activiti.postgres.create.decision.engine.sql` | Yes | Guarded by `isDmnEnabled()`, default `true` (`ProcessEngineConfigurationImpl:632`). |
| `activiti.postgres.create.decision.history.sql` | Yes | `isDmnEnabled() && isDbHistoryUsed()` — both true. |

**History level.** The Spring Boot starter defaults to `HISTORY_FULL`
(`CamundaBpmProperties.historyLevel = ProcessEngineConfiguration.HISTORY_FULL`), and our config does
not override it. The history level does **not** change *which* tables are created — the whole
history script runs whenever `isDbHistoryUsed()` is true — it changes *which rows are written*. At
`full`, every history table below is populated, including `ACT_HI_DETAIL` (variable-update details)
and `ACT_HI_OP_LOG` (user operation log), which lower levels skip.

**Retention.** `historyTimeToLive: P30D` is the engine-wide default TTL, overridable per definition
via `camunda:historyTimeToLive` or the REST API. `batchOperationHistoryTimeToLive: P30D` covers
batch history. History cleanup runs in the `01:00`–`05:00` (UTC) batch window with a
degree-of-parallelism of 2. Every history row carries `REMOVAL_TIME_`, the computed timestamp at
which cleanup may delete it.

### Conventions used below

- **Type** is the SQL datatype exactly as written in the PostgreSQL DDL.
- Columns appear in DDL order.
- Key markers in the Description column: **PK** (primary key), **NOT NULL**, **FK →** `TARGET(COLUMN)`.
  Columns with neither marker are nullable and unconstrained.
- `REV_` is Camunda's **optimistic-locking revision counter**. The engine reads it with the row,
  increments it on every update, and adds `WHERE REV_ = <read value>` to the UPDATE. A zero-row
  update means another node changed the row first, and the engine raises
  `OptimisticLockingException`. It is *not* a business version number and it never appears in the
  REST API. History tables mostly have no `REV_` because history rows are append-only.
- `TENANT_ID_` is the multi-tenancy discriminator. Camunda's tenant model partitions deployments,
  definitions, instances and identity by this value; it is `NULL` in a single-tenant deployment,
  which is what this service runs. It is a soft key — no FK to `ACT_ID_TENANT` exists on the
  runtime/history tables.
- Columns ending in `_ID_` are references. Only some are enforced by database foreign keys; the
  rest are logical references the engine maintains itself (deliberately, so history survives the
  deletion of the runtime rows it describes). Markers below reflect the *actual* DDL constraints.

---

## ACT_GE_* — general

Cross-cutting engine infrastructure that belongs to no single sub-engine: the engine's own
key/value property store (schema version, the ID generator's high-water mark, and the row-level
locks the cluster coordinates through), the binary large-object store shared by every other table,
and the schema-migration log. Rows in `ACT_GE_PROPERTY` and `ACT_GE_SCHEMA_LOG` are seeded at
schema creation and thereafter only updated, never added by normal operation. `ACT_GE_BYTEARRAY`
rows appear whenever anything too large or too binary for an inline column is stored — deployment
resources (the BPMN/DMN XML itself), serialized object variables, job/external-task exception
stack traces, attachment content — and disappear when their owner is deleted or, for
history-scoped bytes, when history cleanup passes their `REMOVAL_TIME_`.

#### ACT_GE_PROPERTY

Engine-wide key/value properties, including the schema version, the ID generator's next-block
pointer, and the cluster-wide advisory locks the engine takes on start-up, deployment and history
cleanup.

| Column | Type | Description |
|---|---|---|
| `NAME_` | varchar(64) | Property key. Seeded with `schema.version`, `schema.history`, `next.dbid`, `deployment.lock`, `history.cleanup.job.lock`, `startup.lock`, `installationId.lock`. **PK** |
| `VALUE_` | varchar(300) | Property value. For the `*.lock` rows the value is irrelevant — the row exists so nodes can serialise on it via `SELECT ... FOR UPDATE`. |
| `REV_` | integer | Optimistic-locking revision. |

#### ACT_GE_BYTEARRAY

The engine's blob store: every byte payload owned by any other table lives here, keyed by an ID the
owning row holds.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Blob identifier, referenced by the owning row. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `NAME_` | varchar(255) | Logical name — for deployment resources, the resource path inside the deployment (e.g. `order-process.bpmn`). |
| `DEPLOYMENT_ID_` | varchar(64) | Owning deployment, for deployment resources; `NULL` for variable/exception blobs. **FK →** `ACT_RE_DEPLOYMENT(ID_)` |
| `BYTES_` | bytea | The binary payload itself. |
| `GENERATED_` | boolean | True when the engine produced the resource rather than the deployer — e.g. an auto-generated process diagram. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `TYPE_` | integer | Resource scope, from `ResourceTypes`: `1` = REPOSITORY (deployment resource), `2` = RUNTIME, `3` = HISTORY. Determines whether history cleanup may remove the row. |
| `CREATE_TIME_` | timestamp | When the blob was written. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Root process instance the blob ultimately belongs to, denormalised so history cleanup can find and remove a whole instance's blobs in one pass. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row; set for `HISTORY`-typed blobs from the owning instance's TTL. |

**Indexes:** `ACT_IDX_BYTEAR_DEPL(DEPLOYMENT_ID_)`, `ACT_IDX_BYTEARRAY_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_BYTEARRAY_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_BYTEARRAY_NAME(NAME_)`.

**Referenced by:** `ACT_RU_VARIABLE.BYTEARRAY_ID_`, `ACT_RU_JOB.EXCEPTION_STACK_ID_`, `ACT_RU_EXT_TASK.ERROR_DETAILS_ID_`.

#### ACT_GE_SCHEMA_LOG

Append-only log of schema versions applied to this database, one row per create or upgrade step.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Log entry identifier. Seeded with `'0'` for the initial create. **PK** |
| `TIMESTAMP_` | timestamp | When this schema version was applied. |
| `VERSION_` | varchar(255) | Engine schema version applied (initial row: `1.2.0`). |

---

## ACT_RE_* — repository (deployed definitions)

The immutable, versioned record of everything that has been *deployed* to the engine. A row appears
here when a deployment is made — via the REST API, the webapp, or a classpath auto-deployment at
start-up — and one deployment fans out into one definition row per BPMN process, DMN decision, DMN
decision-requirements graph, CMMN case and Camunda Form found in its resources. Definitions are
never mutated in place: redeploying the same key produces a **new** row with `VERSION_` incremented,
so running instances keep pointing at the exact definition they started on. Rows disappear only
when the deployment is explicitly deleted (`DELETE /deployment/{id}`), which is normally blocked
while instances still reference it unless cascade is requested. The raw BPMN/DMN XML is not stored
here — it lives in `ACT_GE_BYTEARRAY`, addressed by `RESOURCE_NAME_`.

#### ACT_RE_DEPLOYMENT

One row per deployment operation — the unit of packaging that carries one or more definition
resources.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Deployment identifier. **PK** |
| `NAME_` | varchar(255) | Deployment name supplied by the deployer. |
| `DEPLOY_TIME_` | timestamp | When the deployment was made. |
| `SOURCE_` | varchar(255) | Origin marker, e.g. `process application`, or the value passed by the REST caller. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_DEPLOYMENT_NAME(NAME_)`, `ACT_IDX_DEPLOYMENT_TENANT_ID(TENANT_ID_)`.

#### ACT_RE_PROCDEF

One row per deployed, versioned BPMN process definition.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Process definition identifier, conventionally `key:version:dbid`. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CATEGORY_` | varchar(255) | The BPMN `targetNamespace` of the defining XML. |
| `NAME_` | varchar(255) | Human-readable process name from the BPMN. |
| `KEY_` | varchar(255) | Stable process key (the BPMN `process id`); constant across versions. **NOT NULL** |
| `VERSION_` | integer | Version number within `KEY_` + tenant, incremented on each redeploy. **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment that introduced this version. |
| `RESOURCE_NAME_` | varchar(4000) | Name of the deployment resource holding the BPMN XML, resolved against `ACT_GE_BYTEARRAY`. |
| `DGRM_RESOURCE_NAME_` | varchar(4000) | Name of the deployment resource holding the rendered diagram image, if any. |
| `HAS_START_FORM_KEY_` | boolean | True when the start event declares a form key — lets the webapp skip a lookup. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. A suspended definition starts no new instances. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator; versioning is per key *and* tenant. |
| `VERSION_TAG_` | varchar(64) | Free-text `camunda:versionTag`, a deployer-controlled label independent of `VERSION_`. |
| `HISTORY_TTL_` | integer | Retention in days for this definition's finished instances. Set from `camunda:historyTimeToLive` or the REST endpoint; falls back to the engine default `P30D` when absent. |
| `STARTABLE_` | boolean | Whether the definition may be started from Tasklist (`camunda:isStartableInTasklist`). **NOT NULL**, default `TRUE` |

**Indexes:** `ACT_IDX_PROCDEF_DEPLOYMENT_ID(DEPLOYMENT_ID_)`, `ACT_IDX_PROCDEF_TENANT_ID(TENANT_ID_)`, `ACT_IDX_PROCDEF_VER_TAG(VERSION_TAG_)`.

**Referenced by:** `ACT_RU_EXECUTION.PROC_DEF_ID_`, `ACT_RU_TASK.PROC_DEF_ID_`, `ACT_RU_IDENTITYLINK.PROC_DEF_ID_`, `ACT_RU_INCIDENT.PROC_DEF_ID_`.

#### ACT_RE_CAMFORMDEF

One row per deployed, versioned Camunda Form definition (`.form` resources).

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Form definition identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `KEY_` | varchar(255) | Stable form key, constant across versions. **NOT NULL** |
| `VERSION_` | integer | Version number within `KEY_` + tenant. **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment that introduced this version. |
| `RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the form JSON. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

#### ACT_RE_CASE_DEF

One row per deployed, versioned CMMN case definition. *(Created because CMMN is enabled by default;
this service deploys no CMMN today, so the table stays empty in practice.)*

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Case definition identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CATEGORY_` | varchar(255) | The CMMN `targetNamespace`. |
| `NAME_` | varchar(255) | Human-readable case name. |
| `KEY_` | varchar(255) | Stable case key, constant across versions. **NOT NULL** |
| `VERSION_` | integer | Version number within `KEY_` + tenant. **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment that introduced this version. |
| `RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the CMMN XML. |
| `DGRM_RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the rendered diagram, if any. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `HISTORY_TTL_` | integer | Retention in days for this definition's closed case instances. |

**Indexes:** `ACT_IDX_CASE_DEF_TENANT_ID(TENANT_ID_)`.

#### ACT_RE_DECISION_DEF

One row per deployed, versioned DMN decision (a single decision table / literal expression).

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Decision definition identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CATEGORY_` | varchar(255) | The DMN `namespace`. |
| `NAME_` | varchar(255) | Human-readable decision name. |
| `KEY_` | varchar(255) | Stable decision key (the DMN `decision id`). **NOT NULL** |
| `VERSION_` | integer | Version number within `KEY_` + tenant. **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment that introduced this version. |
| `RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the DMN XML. |
| `DGRM_RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the rendered diagram, if any. |
| `DEC_REQ_ID_` | varchar(64) | Decision-requirements definition this decision belongs to, when the DMN file contains more than one decision. **FK →** `ACT_RE_DECISION_REQ_DEF(ID_)` |
| `DEC_REQ_KEY_` | varchar(255) | Key of that decision-requirements definition, denormalised. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `HISTORY_TTL_` | integer | Retention in days for this decision's historic evaluations. |
| `VERSION_TAG_` | varchar(64) | Free-text version label, independent of `VERSION_`. |

**Indexes:** `ACT_IDX_DEC_DEF_TENANT_ID(TENANT_ID_)`, `ACT_IDX_DEC_DEF_REQ_ID(DEC_REQ_ID_)`.

#### ACT_RE_DECISION_REQ_DEF

One row per deployed DMN decision-requirements graph (DRD) — the container when a `.dmn` file
declares several interrelated decisions.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | DRD identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CATEGORY_` | varchar(255) | The DMN `namespace`. |
| `NAME_` | varchar(255) | Human-readable DRD name. |
| `KEY_` | varchar(255) | Stable DRD key. **NOT NULL** |
| `VERSION_` | integer | Version number within `KEY_` + tenant. **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment that introduced this version. |
| `RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the DMN XML. |
| `DGRM_RESOURCE_NAME_` | varchar(4000) | Deployment resource holding the rendered diagram, if any. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_DEC_REQ_DEF_TENANT_ID(TENANT_ID_)`.

---

## ACT_RU_* — runtime (live state)

The engine's live working set: every row here describes something that is currently *in flight*.
Executions (process instances and their concurrent branches), waiting user tasks, current variable
values, pending jobs (timers, async continuations, history cleanup), event subscriptions, open
incidents, and locked external tasks. Rows are created as a process instance advances and are
**deleted the moment the thing they describe finishes** — completing a user task deletes its
`ACT_RU_TASK` row, ending a process instance deletes its whole `ACT_RU_EXECUTION` tree together
with its variables, jobs and subscriptions. This is why runtime tables stay small in a healthy
system and why growth here is a symptom (stuck instances, failed jobs with exhausted retries,
orphaned timers) rather than a normal accumulation. The audit copy of everything that leaves this
group is written to `ACT_HI_*` first. Three tables are exceptions to the "live only" rule:
`ACT_RU_AUTHORIZATION` and `ACT_RU_FILTER` hold long-lived configuration rather than instance
state, and `ACT_RU_METER_LOG` / `ACT_RU_TASK_METER_LOG` accumulate telemetry counters that are
pruned on their own schedule.

#### ACT_RU_EXECUTION

The execution tree of every running process instance — one row for the instance itself plus one per
live concurrent branch, sub-process scope or event scope.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Execution identifier. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most process instance of the call-activity hierarchy this execution sits in; denormalised so history cleanup can address a whole tree. |
| `PROC_INST_ID_` | varchar(64) | The process instance this execution belongs to; equals `ID_` on the instance row itself. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `BUSINESS_KEY_` | varchar(255) | Caller-supplied domain correlation key (order number, ticket id, …). Set on the instance row. |
| `PARENT_ID_` | varchar(64) | Parent execution in the tree; `NULL` on the instance root. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_DEF_ID_` | varchar(64) | Process definition version this instance is running. **FK →** `ACT_RE_PROCDEF(ID_)` |
| `SUPER_EXEC_` | varchar(64) | The call-activity execution in the *calling* process that started this instance. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `SUPER_CASE_EXEC_` | varchar(64) | The CMMN case execution that started this process instance, when launched from a case. |
| `CASE_INST_ID_` | varchar(64) | Case instance this execution belongs to, when launched from CMMN. |
| `ACT_ID_` | varchar(255) | BPMN element id the execution is currently sitting at. |
| `ACT_INST_ID_` | varchar(64) | Current activity-instance id — the runtime instance of `ACT_ID_`, distinguishing repeated visits to the same element. |
| `IS_ACTIVE_` | boolean | Whether this execution is currently executing rather than parked (an inactive parent whose children are running). |
| `IS_CONCURRENT_` | boolean | Whether this execution is one of several parallel siblings. |
| `IS_SCOPE_` | boolean | Whether this execution defines a variable/event scope (sub-process, multi-instance body). |
| `IS_EVENT_SCOPE_` | boolean | Whether this execution exists only to keep compensation/boundary-event handlers reachable after its activity ended. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. |
| `CACHED_ENT_STATE_` | integer | Bitmask recording which related collections (tasks, jobs, event subscriptions, incidents, variables, external tasks, sub-instances) are non-empty, so the engine can skip queries that would return nothing. Pure performance metadata. |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter used to order history events emitted by this execution. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised from `ACT_RE_PROCDEF` to avoid a join on hot query paths. |

**Indexes:** `ACT_IDX_EXE_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_EXEC_BUSKEY(BUSINESS_KEY_)`, `ACT_IDX_EXEC_TENANT_ID(TENANT_ID_)`, `ACT_IDX_EXE_PROCINST(PROC_INST_ID_)`, `ACT_IDX_EXE_PARENT(PARENT_ID_)`, `ACT_IDX_EXE_SUPER(SUPER_EXEC_)`, `ACT_IDX_EXE_PROCDEF(PROC_DEF_ID_)`.

#### ACT_RU_JOB

Every unit of work the job executor still has to run: timers, async continuations, message jobs,
batch seed/monitor/execution jobs and the history-cleanup job.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Job identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `TYPE_` | varchar(255) | Job discriminator: `timer`, `message`, `event`. **NOT NULL** |
| `LOCK_EXP_TIME_` | timestamp | When the current executor's lock on this job expires; another node may take it after that. |
| `LOCK_OWNER_` | varchar(255) | Identifier of the job executor instance currently holding the job. This is how the cluster avoids running the same job twice. |
| `EXCLUSIVE_` | boolean | When true, this job will not run concurrently with another exclusive job of the same process instance. |
| `EXECUTION_ID_` | varchar(64) | Execution the job belongs to. Indexed, but no database foreign key — the engine maintains the reference itself. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Root process instance of the hierarchy, denormalised. |
| `PROCESS_INSTANCE_ID_` | varchar(64) | Process instance the job belongs to. |
| `PROCESS_DEF_ID_` | varchar(64) | Process definition version the job belongs to. |
| `PROCESS_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `RETRIES_` | integer | Attempts remaining. Reaching `0` turns the failure into an incident and the job stops being acquired. |
| `EXCEPTION_STACK_ID_` | varchar(64) | Blob holding the full stack trace of the last failure. **FK →** `ACT_GE_BYTEARRAY(ID_)` |
| `EXCEPTION_MSG_` | varchar(4000) | Truncated message of the last failure. |
| `FAILED_ACT_ID_` | varchar(255) | BPMN element id at which the last failure occurred. |
| `DUEDATE_` | timestamp | Earliest time the job may be acquired; the timer fire time for timer jobs. |
| `REPEAT_` | varchar(255) | Recurrence expression for cycle timers (ISO-8601 repeating interval or cron). |
| `REPEAT_OFFSET_` | bigint | Millisecond offset applied when computing the next occurrence of a repeating timer, so drift does not accumulate. Default `0`. |
| `HANDLER_TYPE_` | varchar(255) | Handler that will execute the job, e.g. `async-continuation`, `timer-transition`, `history-cleanup`. |
| `HANDLER_CFG_` | varchar(4000) | Handler-specific configuration payload. |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment the job belongs to; used by deployment-aware job execution to keep a job on a node that has the definition. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. **NOT NULL**, default `1` |
| `JOB_DEF_ID_` | varchar(64) | Job definition this job is an instance of. |
| `PRIORITY_` | bigint | Acquisition priority; higher is acquired first. **NOT NULL**, default `0` |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter ordering the job's history events. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_TIME_` | timestamp | When the job row was created. |
| `LAST_FAILURE_LOG_ID_` | varchar(64) | `ACT_HI_JOB_LOG` entry recording the most recent failure, for direct navigation from an incident. |
| `BATCH_ID_` | varchar(64) | Batch this job belongs to, for batch operations. |

**Indexes:** `ACT_IDX_JOB_EXECUTION_ID(EXECUTION_ID_)`, `ACT_IDX_JOB_HANDLER(HANDLER_TYPE_, HANDLER_CFG_)`, `ACT_IDX_JOB_HANDLER_TYPE(HANDLER_TYPE_)`, `ACT_IDX_JOB_PROCINST(PROCESS_INSTANCE_ID_)`, `ACT_IDX_JOB_ROOT_PROCINST(ROOT_PROC_INST_ID_)`, `ACT_IDX_JOB_TENANT_ID(TENANT_ID_)`, `ACT_IDX_JOB_JOB_DEF_ID(JOB_DEF_ID_)`, `ACT_IDX_JOB_EXCEPTION(EXCEPTION_STACK_ID_)`.

#### ACT_RU_JOBDEF

The static "job slots" derived from a deployed definition — one row per asynchronous element or
timer in the BPMN — used to suspend or re-prioritise all jobs of a kind at once.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Job definition identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version this job definition was derived from. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `ACT_ID_` | varchar(255) | BPMN element id the jobs are created for. |
| `JOB_TYPE_` | varchar(255) | Job type produced: `timer`, `async-continuation`, `message`. **NOT NULL** |
| `JOB_CONFIGURATION_` | varchar(255) | Type-specific configuration, e.g. the timer's cycle expression or `async-before` / `async-after`. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. Suspending here suspends every job of this definition. |
| `JOB_PRIORITY_` | bigint | Overriding priority applied to jobs created from this definition. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment this job definition came from. |

**Indexes:** `ACT_IDX_JOBDEF_TENANT_ID(TENANT_ID_)`, `ACT_IDX_JOBDEF_PROC_DEF_ID(PROC_DEF_ID_)`.

**Referenced by:** `ACT_RU_INCIDENT.JOB_DEF_ID_`, `ACT_RU_BATCH.SEED_JOB_DEF_ID_` / `.MONITOR_JOB_DEF_ID_` / `.BATCH_JOB_DEF_ID_`.

#### ACT_RU_TASK

Every human task currently waiting for someone to complete it. The row is deleted on completion or
deletion; the audit copy lives in `ACT_HI_TASKINST`.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Task identifier. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `EXECUTION_ID_` | varchar(64) | Execution that created and is waiting on this task. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. **FK →** `ACT_RE_PROCDEF(ID_)` |
| `CASE_EXECUTION_ID_` | varchar(64) | Owning CMMN case execution, for case-driven tasks. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `CASE_INST_ID_` | varchar(64) | Owning CMMN case instance. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition version. **FK →** `ACT_RE_CASE_DEF(ID_)` |
| `NAME_` | varchar(255) | Task name shown in Tasklist. |
| `PARENT_TASK_ID_` | varchar(64) | Parent task, for sub-tasks. |
| `DESCRIPTION_` | varchar(4000) | Task description. |
| `TASK_DEF_KEY_` | varchar(255) | BPMN element id of the user task — the stable key that identifies "which task in the model" this is. |
| `OWNER_` | varchar(255) | User who owns the task (the delegator, when delegated). |
| `ASSIGNEE_` | varchar(255) | User currently responsible for completing the task; `NULL` while unclaimed. |
| `DELEGATION_` | varchar(64) | Delegation state: `PENDING` (delegated, awaiting the delegate) or `RESOLVED`. |
| `PRIORITY_` | integer | Task priority. |
| `CREATE_TIME_` | timestamp | When the task was created. |
| `LAST_UPDATED_` | timestamp | When the task was last modified. |
| `DUE_DATE_` | timestamp | When the task is due. |
| `FOLLOW_UP_DATE_` | timestamp | Follow-up reminder date. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `TASK_STATE_` | varchar(64) | Lifecycle state from `TaskEntity.TaskState`: `Init`, `Created`, `Completed`, `Deleted`, `Updated`. |

**Indexes:** `ACT_IDX_TASK_CREATE(CREATE_TIME_)`, `ACT_IDX_TASK_LAST_UPDATED(LAST_UPDATED_)`, `ACT_IDX_TASK_ASSIGNEE(ASSIGNEE_)`, `ACT_IDX_TASK_OWNER(OWNER_)`, `ACT_IDX_TASK_TENANT_ID(TENANT_ID_)`, `ACT_IDX_TASK_EXEC(EXECUTION_ID_)`, `ACT_IDX_TASK_PROCINST(PROC_INST_ID_)`, `ACT_IDX_TASK_PROCDEF(PROC_DEF_ID_)`, `ACT_IDX_TASK_CASE_EXEC(CASE_EXECUTION_ID_)`, `ACT_IDX_TASK_CASE_DEF_ID(CASE_DEF_ID_)`.

#### ACT_RU_IDENTITYLINK

Who is associated with a live task (or a process definition) and in what role — candidates,
assignees and owners.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Identity link identifier. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `GROUP_ID_` | varchar(255) | Group side of the link; mutually exclusive with `USER_ID_`. |
| `TYPE_` | varchar(255) | Link type: `candidate`, `assignee`, `owner`. |
| `USER_ID_` | varchar(255) | User side of the link; mutually exclusive with `GROUP_ID_`. |
| `TASK_ID_` | varchar(64) | Task the link applies to. **FK →** `ACT_RU_TASK(ID_)` |
| `PROC_DEF_ID_` | varchar(64) | Process definition the link applies to, for definition-level candidate starters. **FK →** `ACT_RE_PROCDEF(ID_)` |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_IDENT_LNK_USER(USER_ID_)`, `ACT_IDX_IDENT_LNK_GROUP(GROUP_ID_)`, `ACT_IDX_TSKASS_TASK(TASK_ID_)`, `ACT_IDX_ATHRZ_PROCEDEF(PROC_DEF_ID_)`.

#### ACT_RU_VARIABLE

The current value of every process, case, task or batch variable. Exactly one row per variable per
scope; updating a variable overwrites this row and appends a new `ACT_HI_DETAIL` entry.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Variable instance identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `TYPE_` | varchar(255) | Value serializer name: `string`, `long`, `double`, `boolean`, `date`, `json`, `object`, `bytes`, `file`, … It determines which of the value columns below is populated. **NOT NULL** |
| `NAME_` | varchar(255) | Variable name. **NOT NULL** |
| `EXECUTION_ID_` | varchar(64) | Execution the variable is local to. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_DEF_ID_` | varchar(64) | Process definition version, denormalised. |
| `CASE_EXECUTION_ID_` | varchar(64) | Owning CMMN case execution. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `CASE_INST_ID_` | varchar(64) | Owning CMMN case instance. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `TASK_ID_` | varchar(64) | Owning task, for task-local variables. |
| `BATCH_ID_` | varchar(64) | Owning batch, for batch configuration variables. **FK →** `ACT_RU_BATCH(ID_)` |
| `BYTEARRAY_ID_` | varchar(64) | Blob holding the serialized value when it does not fit the inline columns (object, JSON, bytes, file). **FK →** `ACT_GE_BYTEARRAY(ID_)` |
| `DOUBLE_` | double precision | Value when numeric and floating point. |
| `LONG_` | bigint | Value when integral, boolean (0/1) or a date (epoch millis). |
| `TEXT_` | varchar(4000) | Value when short text; for object variables, the value's Java type name. |
| `TEXT2_` | varchar(4000) | Secondary text slot: for object/file variables it carries the serialization data format and metadata; for typed values it holds the value-info payload. |
| `VAR_SCOPE_` | varchar(64) | Id of the scope that owns this variable (execution, task, case execution or batch id). Backs the `ACT_UNIQ_VARIABLE` constraint, which is what prevents two rows for the same variable name in the same scope. |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter ordering this variable's history events. |
| `IS_CONCURRENT_LOCAL_` | boolean | True when the variable is local to a concurrent execution rather than hoisted to the parent scope. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Unique constraints:** `ACT_UNIQ_VARIABLE (VAR_SCOPE_, NAME_)`.

**Indexes:** `ACT_IDX_VARIABLE_TASK_ID(TASK_ID_)`, `ACT_IDX_VARIABLE_TENANT_ID(TENANT_ID_)`, `ACT_IDX_VARIABLE_TASK_NAME_TYPE(TASK_ID_, NAME_, TYPE_)`, `ACT_IDX_VAR_EXE(EXECUTION_ID_)`, `ACT_IDX_VAR_PROCINST(PROC_INST_ID_)`, `ACT_IDX_VAR_BYTEARRAY(BYTEARRAY_ID_)`, `ACT_IDX_VAR_CASE_EXE(CASE_EXECUTION_ID_)`, `ACT_IDX_VAR_CASE_INST_ID(CASE_INST_ID_)`, `ACT_IDX_BATCH_ID(BATCH_ID_)`.

#### ACT_RU_EVENT_SUBSCR

Every event an execution is currently waiting for — message and signal catch events, and
compensation handlers registered for later.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Subscription identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `EVENT_TYPE_` | varchar(255) | `message`, `signal`, `compensate`, or `conditional`. **NOT NULL** |
| `EVENT_NAME_` | varchar(255) | Name of the message or signal being awaited — the value a correlation call must match. |
| `EXECUTION_ID_` | varchar(64) | Execution waiting on the event. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `ACTIVITY_ID_` | varchar(255) | BPMN element id of the catching event. |
| `CONFIGURATION_` | varchar(255) | Subscription-specific payload — for compensation, the id of the execution to compensate; for start-event subscriptions, the process definition id. |
| `CREATED_` | timestamp | When the subscription was created. **NOT NULL** |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_EVENT_SUBSCR_CONFIG_(CONFIGURATION_)`, `ACT_IDX_EVENT_SUBSCR_TENANT_ID(TENANT_ID_)`, `ACT_IDX_EVENT_SUBSCR(EXECUTION_ID_)`, `ACT_IDX_EVENT_SUBSCR_EVT_NAME(EVENT_NAME_)`.

#### ACT_RU_INCIDENT

Open incidents — a job that exhausted its retries, an unresolvable external task, or a failed
BPMN error escalation. The row is deleted when the incident is resolved.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Incident identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. **NOT NULL** |
| `INCIDENT_TIMESTAMP_` | timestamp | When the incident was raised. **NOT NULL** |
| `INCIDENT_MSG_` | varchar(4000) | Failure message. |
| `INCIDENT_TYPE_` | varchar(255) | Incident type: `failedJob`, `failedExternalTask`, or a custom type. **NOT NULL** |
| `EXECUTION_ID_` | varchar(64) | Execution the incident is attached to. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `ACTIVITY_ID_` | varchar(255) | BPMN element id the incident is attached to. |
| `FAILED_ACTIVITY_ID_` | varchar(255) | BPMN element id where the failure actually occurred — can differ from `ACTIVITY_ID_` when the incident is propagated to a parent scope. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. **FK →** `ACT_RE_PROCDEF(ID_)` |
| `CAUSE_INCIDENT_ID_` | varchar(64) | Incident one level down that caused this one, when an incident propagates from a called process. **FK →** `ACT_RU_INCIDENT(ID_)` |
| `ROOT_CAUSE_INCIDENT_ID_` | varchar(64) | Originating incident at the bottom of that chain. **FK →** `ACT_RU_INCIDENT(ID_)` |
| `CONFIGURATION_` | varchar(255) | Reference to the failing entity — the job id for `failedJob`, the external task id for `failedExternalTask`. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `JOB_DEF_ID_` | varchar(64) | Job definition of the failing job. **FK →** `ACT_RU_JOBDEF(ID_)` |
| `ANNOTATION_` | varchar(4000) | Free-text operator annotation added from Cockpit. |

**Indexes:** `ACT_IDX_INC_CONFIGURATION(CONFIGURATION_)`, `ACT_IDX_INC_TENANT_ID(TENANT_ID_)`, `ACT_IDX_INC_JOB_DEF(JOB_DEF_ID_)`, `ACT_IDX_INC_CAUSEINCID(CAUSE_INCIDENT_ID_)`, `ACT_IDX_INC_EXID(EXECUTION_ID_)`, `ACT_IDX_INC_PROCDEFID(PROC_DEF_ID_)`, `ACT_IDX_INC_PROCINSTID(PROC_INST_ID_)`, `ACT_IDX_INC_ROOTCAUSEINCID(ROOT_CAUSE_INCIDENT_ID_)`.

#### ACT_RU_AUTHORIZATION

The engine's access-control list: which user or group may do what to which resource. Long-lived
configuration, not instance state. Active because `cadenzaflow.bpm.authorization.enabled: true`.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Authorization identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. **NOT NULL** |
| `TYPE_` | integer | Authorization type: `0` = global, `1` = grant, `2` = revoke. **NOT NULL** |
| `GROUP_ID_` | varchar(255) | Group the authorization applies to; mutually exclusive with `USER_ID_`. |
| `USER_ID_` | varchar(255) | User the authorization applies to; `*` means everyone. |
| `RESOURCE_TYPE_` | integer | Numeric resource kind (process definition, process instance, task, deployment, user, group, …) from the `Resources` enum. **NOT NULL** |
| `RESOURCE_ID_` | varchar(255) | Specific resource id, or `*` for all resources of that type. |
| `PERMS_` | integer | Bitmask of granted/revoked permissions (READ, UPDATE, CREATE, DELETE, …). |
| `REMOVAL_TIME_` | timestamp | For instance-scoped authorizations created alongside a process instance, the time history cleanup may remove this row. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Root process instance the authorization was created for, so cleanup can remove it with the instance. |

**Unique constraints:** `ACT_UNIQ_AUTH_USER (TYPE_, USER_ID_, RESOURCE_TYPE_, RESOURCE_ID_)`, `ACT_UNIQ_AUTH_GROUP (TYPE_, GROUP_ID_, RESOURCE_TYPE_, RESOURCE_ID_)`.

**Indexes:** `ACT_IDX_AUTH_GROUP_ID(GROUP_ID_)`, `ACT_IDX_AUTH_RESOURCE_ID(RESOURCE_ID_)`, `ACT_IDX_AUTH_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_AUTH_RM_TIME(REMOVAL_TIME_)`.

#### ACT_RU_FILTER

Saved Tasklist filters — persisted query definitions, not instance state. Our configuration seeds
one named `All tasks` (`cadenzaflow.bpm.filter.create`).

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Filter identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. **NOT NULL** |
| `RESOURCE_TYPE_` | varchar(255) | What the filter queries; currently only `Task`. **NOT NULL** |
| `NAME_` | varchar(255) | Filter name shown in Tasklist. **NOT NULL** |
| `OWNER_` | varchar(255) | User who owns the filter. |
| `QUERY_` | TEXT | The serialized query (JSON) the filter runs. **NOT NULL** |
| `PROPERTIES_` | TEXT | Serialized display properties — colour, description, visible columns. |

#### ACT_RU_METER_LOG

Engine telemetry counters (root process instances started, activity instances executed, executed
decision elements) aggregated into time buckets for licensing and load reporting.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Meter log entry identifier. **PK**, **NOT NULL** |
| `NAME_` | varchar(64) | Metric name, e.g. `root-process-instance-start`, `activity-instance-start`, `executed-decision-elements`. **NOT NULL** |
| `REPORTER_` | varchar(255) | Identifier of the engine node that reported the value. |
| `VALUE_` | bigint | Counter value for this bucket. |
| `TIMESTAMP_` | timestamp | Legacy bucket timestamp column. |
| `MILLISECONDS_` | bigint | Bucket timestamp as epoch milliseconds — the column current queries use. Default `0`. |

**Indexes:** `ACT_IDX_METER_LOG_MS(MILLISECONDS_)`, `ACT_IDX_METER_LOG_NAME_MS(NAME_, MILLISECONDS_)`, `ACT_IDX_METER_LOG_REPORT(NAME_, REPORTER_, MILLISECONDS_)`, `ACT_IDX_METER_LOG_TIME(TIMESTAMP_)`, `ACT_IDX_METER_LOG(NAME_, TIMESTAMP_)`.

#### ACT_RU_TASK_METER_LOG

Unique-task-worker telemetry: one row per (assignee, time) observation, storing only a hash of the
assignee so distinct workers can be counted without retaining user identities.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Entry identifier. **PK**, **NOT NULL** |
| `ASSIGNEE_HASH_` | bigint | Hash of the assignee's user id (`TaskMeterLogEntity.createHashAsLong`). Deliberately one-way — it supports distinct counting, not identification. |
| `TIMESTAMP_` | timestamp | When the assignment was observed. |

**Indexes:** `ACT_IDX_TASK_METER_LOG_TIME(TIMESTAMP_)`.

#### ACT_RU_EXT_TASK

External tasks currently available to, or locked by, an external worker — the polling integration
point for non-Java workers.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | External task identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. **NOT NULL** |
| `WORKER_ID_` | varchar(255) | Worker that currently holds the lock; `NULL` when unlocked and available for fetch. |
| `TOPIC_NAME_` | varchar(255) | Topic workers subscribe to in order to fetch this task. |
| `RETRIES_` | integer | Attempts remaining before the failure becomes an incident. |
| `ERROR_MSG_` | varchar(4000) | Truncated message of the last reported failure. |
| `ERROR_DETAILS_ID_` | varchar(64) | Blob holding the full error details reported by the worker. **FK →** `ACT_GE_BYTEARRAY(ID_)` |
| `LOCK_EXP_TIME_` | timestamp | When the worker's lock expires and the task becomes fetchable again. |
| `CREATE_TIME_` | timestamp | When the external task was created. |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. |
| `EXECUTION_ID_` | varchar(64) | Execution waiting on this external task. **FK →** `ACT_RU_EXECUTION(ID_)` |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `ACT_ID_` | varchar(255) | BPMN element id of the service task. |
| `ACT_INST_ID_` | varchar(64) | Activity instance id of that element. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `PRIORITY_` | bigint | Fetch priority; higher is handed out first. **NOT NULL**, default `0` |
| `LAST_FAILURE_LOG_ID_` | varchar(64) | `ACT_HI_EXT_TASK_LOG` entry for the most recent failure. |

**Indexes:** `ACT_IDX_EXT_TASK_TOPIC(TOPIC_NAME_)`, `ACT_IDX_EXT_TASK_TENANT_ID(TENANT_ID_)`, `ACT_IDX_EXT_TASK_PRIORITY(PRIORITY_)`, `ACT_IDX_EXT_TASK_ERR_DETAILS(ERROR_DETAILS_ID_)`, `ACT_IDX_EXT_TASK_EXEC(EXECUTION_ID_)`.

#### ACT_RU_BATCH

Running batch operations — bulk instance migration, modification, deletion, restart or
set-retries — tracking seeding and completion progress.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Batch identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. **NOT NULL** |
| `TYPE_` | varchar(255) | Batch operation type, e.g. `instance-migration`, `instance-deletion`, `process-set-removal-time`. |
| `TOTAL_JOBS_` | integer | Total execution jobs this batch will produce. |
| `JOBS_CREATED_` | integer | How many have been created so far by the seed job. |
| `JOBS_PER_SEED_` | integer | How many execution jobs each run of the seed job creates. |
| `INVOCATIONS_PER_JOB_` | integer | How many individual items each execution job processes. |
| `SEED_JOB_DEF_ID_` | varchar(64) | Job definition of the seed job that creates the execution jobs. **FK →** `ACT_RU_JOBDEF(ID_)` |
| `BATCH_JOB_DEF_ID_` | varchar(64) | Job definition of the execution jobs that do the work. **FK →** `ACT_RU_JOBDEF(ID_)` |
| `MONITOR_JOB_DEF_ID_` | varchar(64) | Job definition of the monitor job that detects completion and cleans up. **FK →** `ACT_RU_JOBDEF(ID_)` |
| `SUSPENSION_STATE_` | integer | `1` = active, `2` = suspended. |
| `CONFIGURATION_` | varchar(255) | Reference to the batch's configuration payload, stored as a batch-scoped variable. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_USER_ID_` | varchar(255) | User who started the batch. |
| `START_TIME_` | timestamp | When the batch was created. |
| `EXEC_START_TIME_` | timestamp | When the first execution job actually ran. |

**Indexes:** `ACT_IDX_BATCH_SEED_JOB_DEF(SEED_JOB_DEF_ID_)`, `ACT_IDX_BATCH_MONITOR_JOB_DEF(MONITOR_JOB_DEF_ID_)`, `ACT_IDX_BATCH_JOB_DEF(BATCH_JOB_DEF_ID_)`.

#### ACT_RU_CASE_EXECUTION

The live CMMN case tree — the case instance and each of its plan items. *(Created because CMMN is
enabled by default; empty unless CMMN definitions are deployed.)*

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Case execution identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CASE_INST_ID_` | varchar(64) | Case instance this execution belongs to; equals `ID_` on the instance row. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `SUPER_CASE_EXEC_` | varchar(64) | Case task in a calling case that started this case instance. |
| `SUPER_EXEC_` | varchar(64) | BPMN execution that started this case instance, when launched from a process. |
| `BUSINESS_KEY_` | varchar(255) | Caller-supplied domain correlation key. |
| `PARENT_ID_` | varchar(64) | Parent case execution in the plan-item tree. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `CASE_DEF_ID_` | varchar(64) | Case definition version being executed. **FK →** `ACT_RE_CASE_DEF(ID_)` |
| `ACT_ID_` | varchar(255) | CMMN plan-item id this execution represents. |
| `PREV_STATE_` | integer | Previous CMMN state code, retained to drive state-transition listeners. |
| `CURRENT_STATE_` | integer | Current CMMN state code (available, enabled, active, suspended, completed, terminated, …). |
| `REQUIRED_` | boolean | Whether the plan item's required rule evaluated true — the parent stage cannot complete until it finishes. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_CASE_EXEC_BUSKEY(BUSINESS_KEY_)`, `ACT_IDX_CASE_EXE_CASE_INST(CASE_INST_ID_)`, `ACT_IDX_CASE_EXE_PARENT(PARENT_ID_)`, `ACT_IDX_CASE_EXE_CASE_DEF(CASE_DEF_ID_)`, `ACT_IDX_CASE_EXEC_TENANT_ID(TENANT_ID_)`.

#### ACT_RU_CASE_SENTRY_PART

The individual on-parts and if-parts of a CMMN sentry, tracking which conditions of an entry/exit
criterion have already been satisfied.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Sentry part identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision. |
| `CASE_INST_ID_` | varchar(64) | Owning case instance. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `CASE_EXEC_ID_` | varchar(64) | Case execution the sentry guards. **FK →** `ACT_RU_CASE_EXECUTION(ID_)` |
| `SENTRY_ID_` | varchar(255) | CMMN sentry id this part belongs to. |
| `TYPE_` | varchar(255) | Part type: `ifPart`, `onPart` (plan-item), or variable-on-part. |
| `SOURCE_CASE_EXEC_ID_` | varchar(64) | Case execution whose state change this on-part is watching. |
| `STANDARD_EVENT_` | varchar(255) | CMMN standard event awaited on the source, e.g. `complete`, `occur`, `terminate`. |
| `SOURCE_` | varchar(255) | Plan-item id of the source element, as written in the model. |
| `VARIABLE_EVENT_` | varchar(255) | Variable event awaited, for variable-on-parts: `create`, `update`, `delete`. |
| `VARIABLE_NAME_` | varchar(255) | Variable name watched by a variable-on-part. |
| `SATISFIED_` | boolean | Whether this part's condition has already been met; the sentry fires when all parts are satisfied. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_CASE_SENTRY_CASE_INST(CASE_INST_ID_)`, `ACT_IDX_CASE_SENTRY_CASE_EXEC(CASE_EXEC_ID_)`.

---

## ACT_HI_* — history (audit trail)

The append-mostly audit trail. Every meaningful runtime event produces a history row: a process
instance starts, an activity is entered and left, a task is created, claimed and completed, a
variable changes, a job fails, a decision is evaluated. Rows here are written *in addition to* the
runtime rows and deliberately **outlive** them — the `ACT_HI_*` copy is what remains after the
`ACT_RU_*` row is deleted at completion. Which rows get written depends on the history level; at
this service's level (`full`, the Spring Boot starter default) all of them do, including
`ACT_HI_DETAIL` variable updates and the `ACT_HI_OP_LOG` user-operation log. Rows are removed only
by **history cleanup**: when an instance ends, the engine stamps `REMOVAL_TIME_` on its history rows
from the definition's `HISTORY_TTL_` (or the engine default `P30D`), and the cleanup job — running
between 01:00 and 05:00 UTC with a degree-of-parallelism of 2 — deletes rows whose removal time has
passed. Unbounded growth here almost always means a definition without a TTL, or a batch window
that never opens. Nearly every table carries `ROOT_PROC_INST_ID_` and `REMOVAL_TIME_` for exactly
this purpose.

#### ACT_HI_PROCINST

One row per process instance, running or finished — the top-level audit record of a process run.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | History record identifier (equal to the process instance id). **PK**, **NOT NULL** |
| `PROC_INST_ID_` | varchar(64) | The process instance id. **NOT NULL**, **UNIQUE** |
| `BUSINESS_KEY_` | varchar(255) | Caller-supplied domain correlation key. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version the instance ran on. **NOT NULL** |
| `START_TIME_` | timestamp | When the instance started. **NOT NULL** |
| `END_TIME_` | timestamp | When the instance ended; `NULL` while running. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this instance's history; computed from `END_TIME_` + TTL. |
| `DURATION_` | bigint | Run duration in milliseconds. |
| `START_USER_ID_` | varchar(255) | User who started the instance, when started through an authenticated API call. |
| `START_ACT_ID_` | varchar(255) | BPMN element id of the start event that fired. |
| `END_ACT_ID_` | varchar(255) | BPMN element id of the end event reached. |
| `SUPER_PROCESS_INSTANCE_ID_` | varchar(64) | Calling process instance, when started by a call activity. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `SUPER_CASE_INSTANCE_ID_` | varchar(64) | Calling CMMN case instance, when started from a case. |
| `CASE_INST_ID_` | varchar(64) | Case instance the process belongs to. |
| `DELETE_REASON_` | varchar(4000) | Reason recorded when the instance was deleted rather than completed. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `STATE_` | varchar(255) | Final or current state: `ACTIVE`, `SUSPENDED`, `COMPLETED`, `EXTERNALLY_TERMINATED`, `INTERNALLY_TERMINATED`. |
| `RESTARTED_PROC_INST_ID_` | varchar(64) | If this instance was created by restarting a finished one, the id of the instance it was restarted from. |

**Indexes:** `ACT_IDX_HI_PRO_INST_END(END_TIME_)`, `ACT_IDX_HI_PRO_I_BUSKEY(BUSINESS_KEY_)`, `ACT_IDX_HI_PRO_INST_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_PRO_INST_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_PRO_INST_PROC_TIME(START_TIME_, END_TIME_)`, `ACT_IDX_HI_PI_PDEFID_END_TIME(PROC_DEF_ID_, END_TIME_)`, `ACT_IDX_HI_PRO_INST_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_PRO_INST_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_PRO_RST_PRO_INST_ID(RESTARTED_PROC_INST_ID_)`.

#### ACT_HI_ACTINST

One row per activity instance — every BPMN element a process instance entered, and how long it took.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Activity instance identifier. **PK**, **NOT NULL** |
| `PARENT_ACT_INST_ID_` | varchar(64) | Enclosing activity instance (sub-process, multi-instance body). |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. **NOT NULL** |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. **NOT NULL** |
| `EXECUTION_ID_` | varchar(64) | Execution that ran this activity. **NOT NULL** |
| `ACT_ID_` | varchar(255) | BPMN element id. **NOT NULL** |
| `TASK_ID_` | varchar(64) | Task created by this activity, for user tasks. |
| `CALL_PROC_INST_ID_` | varchar(64) | Process instance started by this activity, for call activities. |
| `CALL_CASE_INST_ID_` | varchar(64) | Case instance started by this activity, for case tasks. |
| `ACT_NAME_` | varchar(255) | BPMN element name. |
| `ACT_TYPE_` | varchar(255) | BPMN element type, e.g. `userTask`, `serviceTask`, `exclusiveGateway`, `startEvent`. **NOT NULL** |
| `ASSIGNEE_` | varchar(255) | Assignee at the time, for user-task activities. |
| `START_TIME_` | timestamp | When the activity was entered. **NOT NULL** |
| `END_TIME_` | timestamp | When it was left; `NULL` while still active. |
| `DURATION_` | bigint | Time spent in the activity, in milliseconds. |
| `ACT_INST_STATE_` | integer | Terminal state code of the activity instance — distinguishes normally completed from cancelled/terminated. |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter that orders events from the same execution. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_ACTINST_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_ACT_INST_START_END(START_TIME_, END_TIME_)`, `ACT_IDX_HI_ACT_INST_END(END_TIME_)`, `ACT_IDX_HI_ACT_INST_PROCINST(PROC_INST_ID_, ACT_ID_)`, `ACT_IDX_HI_ACT_INST_COMP(EXECUTION_ID_, ACT_ID_, END_TIME_, ID_)`, `ACT_IDX_HI_ACT_INST_STATS(PROC_DEF_ID_, PROC_INST_ID_, ACT_ID_, END_TIME_, ACT_INST_STATE_)`, `ACT_IDX_HI_ACT_INST_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_ACT_INST_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_AI_PDEFID_END_TIME(PROC_DEF_ID_, END_TIME_)`, `ACT_IDX_HI_ACT_INST_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_TASKINST

One row per user task ever created, with its final assignee, timings and outcome.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Task identifier (same value as the runtime task's `ID_`). **PK**, **NOT NULL** |
| `TASK_DEF_KEY_` | varchar(255) | BPMN element id of the user task in the model. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `EXECUTION_ID_` | varchar(64) | Execution that created the task. |
| `CASE_DEF_KEY_` | varchar(255) | CMMN case definition key, for case tasks. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition version. |
| `CASE_INST_ID_` | varchar(64) | Owning case instance. |
| `CASE_EXECUTION_ID_` | varchar(64) | Owning case execution. |
| `ACT_INST_ID_` | varchar(64) | Activity instance that created the task. |
| `NAME_` | varchar(255) | Task name. |
| `PARENT_TASK_ID_` | varchar(64) | Parent task, for sub-tasks. |
| `DESCRIPTION_` | varchar(4000) | Task description. |
| `OWNER_` | varchar(255) | Task owner at the end. |
| `ASSIGNEE_` | varchar(255) | Assignee at the end. |
| `START_TIME_` | timestamp | When the task was created. **NOT NULL** |
| `END_TIME_` | timestamp | When it was completed or deleted; `NULL` while open. |
| `DURATION_` | bigint | Lifetime in milliseconds. |
| `DELETE_REASON_` | varchar(4000) | `completed`, or the reason the task was deleted. |
| `PRIORITY_` | integer | Priority at the end. |
| `DUE_DATE_` | timestamp | Due date at the end. |
| `FOLLOW_UP_DATE_` | timestamp | Follow-up date at the end. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `TASK_STATE_` | varchar(64) | Final lifecycle state (`Completed`, `Deleted`, …). |

**Indexes:** `ACT_IDX_HI_TASKINST_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_TASK_INST_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_TASK_INST_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_TASKINST_PROCINST(PROC_INST_ID_)`, `ACT_IDX_HI_TASKINSTID_PROCINST(ID_, PROC_INST_ID_)`, `ACT_IDX_HI_TASK_INST_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_TASK_INST_START(START_TIME_)`, `ACT_IDX_HI_TASK_INST_END(END_TIME_)`.

#### ACT_HI_VARINST

One row per variable instance, holding its **latest** value — the history-side mirror of
`ACT_RU_VARIABLE` that survives instance completion.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Variable instance identifier. **PK**, **NOT NULL** |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `EXECUTION_ID_` | varchar(64) | Execution the variable was local to. |
| `ACT_INST_ID_` | varchar(64) | Activity instance in scope when the variable was created. |
| `CASE_DEF_KEY_` | varchar(255) | CMMN case definition key. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition version. |
| `CASE_INST_ID_` | varchar(64) | Owning case instance. |
| `CASE_EXECUTION_ID_` | varchar(64) | Owning case execution. |
| `TASK_ID_` | varchar(64) | Owning task, for task-local variables. |
| `NAME_` | varchar(255) | Variable name. **NOT NULL** |
| `VAR_TYPE_` | varchar(100) | Value serializer name; determines which value column is populated. |
| `CREATE_TIME_` | timestamp | When the variable was first set. |
| `REV_` | integer | Optimistic-locking revision — present here because this row *is* updated in place on each variable change. |
| `BYTEARRAY_ID_` | varchar(64) | Blob holding the serialized value, when not inline-able. |
| `DOUBLE_` | double precision | Value when floating point. |
| `LONG_` | bigint | Value when integral, boolean or date. |
| `TEXT_` | varchar(4000) | Value when short text; for object variables, the Java type name. |
| `TEXT2_` | varchar(4000) | Secondary text slot — serialization data format / value-info metadata. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `STATE_` | varchar(20) | Variable state: `CREATED` or `DELETED`. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_VARINST_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_PROCVAR_PROC_INST(PROC_INST_ID_)`, `ACT_IDX_HI_PROCVAR_NAME_TYPE(NAME_, VAR_TYPE_)`, `ACT_IDX_HI_CASEVAR_CASE_INST(CASE_INST_ID_)`, `ACT_IDX_HI_VAR_INST_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_VAR_INST_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_VARINST_BYTEAR(BYTEARRAY_ID_)`, `ACT_IDX_HI_VARINST_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_VAR_PI_NAME_TYPE(PROC_INST_ID_, NAME_, VAR_TYPE_)`, `ACT_IDX_HI_VARINST_NAME(NAME_)`, `ACT_IDX_HI_VARINST_ACT_INST_ID(ACT_INST_ID_)`.

#### ACT_HI_DETAIL

One row per *individual variable update* and per form-property submission — the full change log
behind `ACT_HI_VARINST`. Only written at history level `full`, which is this service's level; this
is typically the largest table in the schema.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Detail entry identifier. **PK**, **NOT NULL** |
| `TYPE_` | varchar(255) | Detail kind: `VariableUpdate` or `FormProperty`. **NOT NULL** |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `EXECUTION_ID_` | varchar(64) | Execution in which the update happened. |
| `CASE_DEF_KEY_` | varchar(255) | CMMN case definition key. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition version. |
| `CASE_INST_ID_` | varchar(64) | Owning case instance. |
| `CASE_EXECUTION_ID_` | varchar(64) | Owning case execution. |
| `TASK_ID_` | varchar(64) | Task in scope, for task-local updates and form submissions. |
| `ACT_INST_ID_` | varchar(64) | Activity instance in scope at the time of the update. |
| `VAR_INST_ID_` | varchar(64) | The `ACT_HI_VARINST` row this update belongs to — the chain that turns detail rows into a variable's value history. |
| `NAME_` | varchar(255) | Variable or form-property name. **NOT NULL** |
| `VAR_TYPE_` | varchar(64) | Value serializer name for this revision of the value. |
| `REV_` | integer | Revision number of the variable *at this update* — a sequence within the variable's history, not an optimistic lock on this (append-only) row. |
| `TIME_` | timestamp | When the update occurred. **NOT NULL** |
| `BYTEARRAY_ID_` | varchar(64) | Blob holding the serialized value of this revision. |
| `DOUBLE_` | double precision | Value when floating point. |
| `LONG_` | bigint | Value when integral, boolean or date. |
| `TEXT_` | varchar(4000) | Value when short text; for object variables, the Java type name. |
| `TEXT2_` | varchar(4000) | Secondary text slot — serialization data format / value-info metadata. |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter that orders updates written within the same millisecond. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `OPERATION_ID_` | varchar(64) | Correlates this update with the `ACT_HI_OP_LOG` operation that caused it, when a user changed the variable via the API. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `INITIAL_` | boolean | True when this row records the variable's initial value, set at process instance start (`HistoricVariableUpdateEventEntity.isInitial`). Lets queries retrieve start variables without scanning the whole change log. |

**Indexes:** `ACT_IDX_HI_DETAIL_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_DETAIL_PROC_INST(PROC_INST_ID_)`, `ACT_IDX_HI_DETAIL_ACT_INST(ACT_INST_ID_)`, `ACT_IDX_HI_DETAIL_CASE_INST(CASE_INST_ID_)`, `ACT_IDX_HI_DETAIL_CASE_EXEC(CASE_EXECUTION_ID_)`, `ACT_IDX_HI_DETAIL_TIME(TIME_)`, `ACT_IDX_HI_DETAIL_NAME(NAME_)`, `ACT_IDX_HI_DETAIL_TASK_ID(TASK_ID_)`, `ACT_IDX_HI_DETAIL_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_DETAIL_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_DETAIL_BYTEAR(BYTEARRAY_ID_)`, `ACT_IDX_HI_DETAIL_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_DETAIL_TASK_BYTEAR(BYTEARRAY_ID_, TASK_ID_)`, `ACT_IDX_HI_DETAIL_VAR_INST_ID(VAR_INST_ID_)`.

#### ACT_HI_IDENTITYLINK

Audit trail of who was linked to a task and when — every candidate added or removed, every claim
and assignment.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | History entry identifier. **PK**, **NOT NULL** |
| `TIMESTAMP_` | timestamp | When the link was added or removed. **NOT NULL** |
| `TYPE_` | varchar(255) | Link type: `candidate`, `assignee`, `owner`. |
| `USER_ID_` | varchar(255) | User side of the link. |
| `GROUP_ID_` | varchar(255) | Group side of the link. |
| `TASK_ID_` | varchar(64) | Task the link applied to. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version, for definition-level links. |
| `OPERATION_TYPE_` | varchar(64) | Whether the link was `add`ed or `delete`d. |
| `ASSIGNER_ID_` | varchar(64) | User who performed the assignment. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_IDENT_LNK_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_IDENT_LNK_USER(USER_ID_)`, `ACT_IDX_HI_IDENT_LNK_GROUP(GROUP_ID_)`, `ACT_IDX_HI_IDENT_LNK_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_IDENT_LNK_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_IDENT_LINK_TASK(TASK_ID_)`, `ACT_IDX_HI_IDENT_LINK_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_IDENT_LNK_TIMESTAMP(TIMESTAMP_)`.

#### ACT_HI_COMMENT

User comments and engine-generated event notes attached to a task or process instance.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Comment identifier. **PK**, **NOT NULL** |
| `TYPE_` | varchar(255) | `comment` for a user comment, `event` for an engine-generated lifecycle note. |
| `TIME_` | timestamp | When the comment was made. **NOT NULL** |
| `USER_ID_` | varchar(255) | Author. |
| `TASK_ID_` | varchar(64) | Task the comment is attached to. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Process instance the comment is attached to. |
| `ACTION_` | varchar(255) | For `event` rows, the action recorded (e.g. `AddUserLink`, `AddAttachment`). |
| `MESSAGE_` | varchar(4000) | Truncated comment text, for display in lists. |
| `FULL_MSG_` | bytea | The full comment text, stored inline as bytes. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `REV_` | integer | Optimistic-locking revision — comments are editable, so this row can be updated. **NOT NULL**, default `1` |

**Indexes:** `ACT_IDX_HI_COMMENT_TASK(TASK_ID_)`, `ACT_IDX_HI_COMMENT_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_COMMENT_PROCINST(PROC_INST_ID_)`, `ACT_IDX_HI_COMMENT_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_ATTACHMENT

Files and URLs attached to a task or process instance.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Attachment identifier. **PK**, **NOT NULL** |
| `REV_` | integer | Optimistic-locking revision — attachments are editable. |
| `USER_ID_` | varchar(255) | User who attached it. |
| `NAME_` | varchar(255) | Attachment name. |
| `DESCRIPTION_` | varchar(4000) | Attachment description. |
| `TYPE_` | varchar(255) | Caller-supplied content type / classification. |
| `TASK_ID_` | varchar(64) | Task the attachment belongs to. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Process instance the attachment belongs to. |
| `URL_` | varchar(4000) | External URL, for link-type attachments. |
| `CONTENT_ID_` | varchar(64) | Blob in `ACT_GE_BYTEARRAY` holding the file bytes, for content-type attachments. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_TIME_` | timestamp | When the attachment was created. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_ATTACHMENT_CONTENT(CONTENT_ID_)`, `ACT_IDX_HI_ATTACHMENT_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_ATTACHMENT_PROCINST(PROC_INST_ID_)`, `ACT_IDX_HI_ATTACHMENT_TASK(TASK_ID_)`, `ACT_IDX_HI_ATTACHMENT_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_OP_LOG

The user operation log: every administrative change a human made through the API or webapps —
suspending an instance, reassigning a task, setting retries, deleting a deployment. One row per
changed property, correlated by `OPERATION_ID_`. Written only at history level `full`.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Log entry identifier. **PK**, **NOT NULL** |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment affected, for deployment operations. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version affected. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key affected. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Process instance affected. |
| `EXECUTION_ID_` | varchar(64) | Execution affected. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition affected. |
| `CASE_INST_ID_` | varchar(64) | CMMN case instance affected. |
| `CASE_EXECUTION_ID_` | varchar(64) | CMMN case execution affected. |
| `TASK_ID_` | varchar(64) | Task affected. |
| `JOB_ID_` | varchar(64) | Job affected. |
| `JOB_DEF_ID_` | varchar(64) | Job definition affected. |
| `BATCH_ID_` | varchar(64) | Batch affected. |
| `USER_ID_` | varchar(255) | Authenticated user who performed the operation. |
| `TIMESTAMP_` | timestamp | When the operation was performed. **NOT NULL** |
| `OPERATION_TYPE_` | varchar(64) | Operation performed, e.g. `Claim`, `Assign`, `Suspend`, `SetJobRetries`, `Delete`. |
| `OPERATION_ID_` | varchar(64) | Groups all property rows written by one logical operation. |
| `ENTITY_TYPE_` | varchar(30) | Entity kind acted on: `Task`, `ProcessInstance`, `Job`, `Attachment`, … |
| `PROPERTY_` | varchar(64) | Name of the property that changed. |
| `ORG_VALUE_` | varchar(4000) | Value before the change. |
| `NEW_VALUE_` | varchar(4000) | Value after the change. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `CATEGORY_` | varchar(64) | Operation category, e.g. `TaskWorker`, `Operator`, `Admin` — lets authorization filter the log by who may see what. |
| `EXTERNAL_TASK_ID_` | varchar(64) | External task affected. |
| `ANNOTATION_` | varchar(4000) | Free-text operator annotation attached to the operation. |

**Indexes:** `ACT_IDX_HI_OP_LOG_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_OP_LOG_PROCINST(PROC_INST_ID_)`, `ACT_IDX_HI_OP_LOG_PROCDEF(PROC_DEF_ID_)`, `ACT_IDX_HI_OP_LOG_TASK(TASK_ID_)`, `ACT_IDX_HI_OP_LOG_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_OP_LOG_TIMESTAMP(TIMESTAMP_)`, `ACT_IDX_HI_OP_LOG_USER_ID(USER_ID_)`, `ACT_IDX_HI_OP_LOG_OP_TYPE(OPERATION_TYPE_)`, `ACT_IDX_HI_OP_LOG_ENTITY_TYPE(ENTITY_TYPE_)`.

#### ACT_HI_INCIDENT

One row per incident ever raised, including resolved and deleted ones — the audit counterpart of
`ACT_RU_INCIDENT`.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Incident identifier (same as the runtime incident). **PK**, **NOT NULL** |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `EXECUTION_ID_` | varchar(64) | Execution the incident was attached to. |
| `CREATE_TIME_` | timestamp | When the incident was raised. **NOT NULL** |
| `END_TIME_` | timestamp | When it was resolved or deleted; `NULL` while open. |
| `INCIDENT_MSG_` | varchar(4000) | Failure message. |
| `INCIDENT_TYPE_` | varchar(255) | Incident type, e.g. `failedJob`, `failedExternalTask`. **NOT NULL** |
| `ACTIVITY_ID_` | varchar(255) | BPMN element id the incident was attached to. |
| `FAILED_ACTIVITY_ID_` | varchar(255) | BPMN element id where the failure actually occurred. |
| `CAUSE_INCIDENT_ID_` | varchar(64) | Incident that caused this one. |
| `ROOT_CAUSE_INCIDENT_ID_` | varchar(64) | Originating incident at the bottom of the chain. |
| `CONFIGURATION_` | varchar(255) | Reference to the failing entity (job id, external task id). |
| `HISTORY_CONFIGURATION_` | varchar(255) | Reference to the corresponding history record of the failing entity — e.g. the `ACT_HI_JOB_LOG` id — since the runtime row referenced by `CONFIGURATION_` may be gone. |
| `INCIDENT_STATE_` | integer | Terminal state: `0` = open, `1` = deleted, `2` = resolved. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `JOB_DEF_ID_` | varchar(64) | Job definition of the failing job. |
| `ANNOTATION_` | varchar(4000) | Free-text operator annotation. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_INCIDENT_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_INCIDENT_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_IDX_HI_INCIDENT_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_INCIDENT_PROCINST(PROC_INST_ID_)`, `ACT_IDX_HI_INCIDENT_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_INCIDENT_CREATE_TIME(CREATE_TIME_)`, `ACT_IDX_HI_INCIDENT_END_TIME(END_TIME_)`.

#### ACT_HI_JOB_LOG

One row per job lifecycle event — created, executed successfully, failed, deleted — giving the full
retry history of every job.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Log entry identifier. **PK**, **NOT NULL** |
| `TIMESTAMP_` | timestamp | When the event occurred. **NOT NULL** |
| `JOB_ID_` | varchar(64) | The job this event concerns. **NOT NULL** |
| `JOB_DUEDATE_` | timestamp | The job's due date at the time of the event. |
| `JOB_RETRIES_` | integer | Retries remaining at the time of the event. |
| `JOB_PRIORITY_` | bigint | The job's priority at the time of the event. **NOT NULL**, default `0` |
| `JOB_EXCEPTION_MSG_` | varchar(4000) | Truncated failure message, for failure events. |
| `JOB_EXCEPTION_STACK_ID_` | varchar(64) | Blob holding the full stack trace of the failure. |
| `JOB_STATE_` | integer | Event/state code: created, failed, successful, deleted. |
| `JOB_DEF_ID_` | varchar(64) | Job definition the job came from. |
| `JOB_DEF_TYPE_` | varchar(255) | Job definition type, e.g. `timer`, `async-continuation`. |
| `JOB_DEF_CONFIGURATION_` | varchar(255) | Job definition configuration at the time. |
| `ACT_ID_` | varchar(255) | BPMN element id the job belongs to. |
| `FAILED_ACT_ID_` | varchar(255) | BPMN element id where the failure occurred. |
| `EXECUTION_ID_` | varchar(64) | Execution the job belonged to. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROCESS_INSTANCE_ID_` | varchar(64) | Owning process instance. |
| `PROCESS_DEF_ID_` | varchar(64) | Process definition version. |
| `PROCESS_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `DEPLOYMENT_ID_` | varchar(64) | Deployment the job belonged to. |
| `SEQUENCE_COUNTER_` | bigint | Monotonic counter ordering events of the same job. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `HOSTNAME_` | varchar(255) | Host of the engine node that produced the event — useful for pinning a failure to a pod. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `BATCH_ID_` | varchar(64) | Batch the job belonged to. |

**Indexes:** `ACT_IDX_HI_JOB_LOG_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_JOB_LOG_PROCINST(PROCESS_INSTANCE_ID_)`, `ACT_IDX_HI_JOB_LOG_PROCDEF(PROCESS_DEF_ID_)`, `ACT_IDX_HI_JOB_LOG_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_JOB_LOG_JOB_DEF_ID(JOB_DEF_ID_)`, `ACT_IDX_HI_JOB_LOG_PROC_DEF_KEY(PROCESS_DEF_KEY_)`, `ACT_IDX_HI_JOB_LOG_EX_STACK(JOB_EXCEPTION_STACK_ID_)`, `ACT_IDX_HI_JOB_LOG_RM_TIME(REMOVAL_TIME_)`, `ACT_IDX_HI_JOB_LOG_JOB_CONF(JOB_DEF_CONFIGURATION_)`.

#### ACT_HI_BATCH

One row per batch operation, retained after the batch finishes and its `ACT_RU_BATCH` row is
deleted. Retention is governed by `batchOperationHistoryTimeToLive` (`P30D` here).

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Batch identifier. **PK**, **NOT NULL** |
| `TYPE_` | varchar(255) | Batch operation type. |
| `TOTAL_JOBS_` | integer | Total execution jobs the batch produced. |
| `JOBS_PER_SEED_` | integer | Execution jobs created per seed job run. |
| `INVOCATIONS_PER_JOB_` | integer | Items processed per execution job. |
| `SEED_JOB_DEF_ID_` | varchar(64) | Job definition of the seed job. |
| `MONITOR_JOB_DEF_ID_` | varchar(64) | Job definition of the monitor job. |
| `BATCH_JOB_DEF_ID_` | varchar(64) | Job definition of the execution jobs. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_USER_ID_` | varchar(255) | User who started the batch. |
| `START_TIME_` | timestamp | When the batch was created. **NOT NULL** |
| `END_TIME_` | timestamp | When the batch completed. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `EXEC_START_TIME_` | timestamp | When the first execution job ran. |

**Indexes:** `ACT_HI_BAT_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_EXT_TASK_LOG

One row per external-task lifecycle event — created, fetched/locked, completed, failed, deleted.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Log entry identifier. **PK**, **NOT NULL** |
| `TIMESTAMP_` | timestamp | When the event occurred. **NOT NULL** |
| `EXT_TASK_ID_` | varchar(64) | The external task this event concerns. **NOT NULL** |
| `RETRIES_` | integer | Retries remaining at the time of the event. |
| `TOPIC_NAME_` | varchar(255) | Topic the external task was published on. |
| `WORKER_ID_` | varchar(255) | Worker involved in the event. |
| `PRIORITY_` | bigint | Priority at the time of the event. **NOT NULL**, default `0` |
| `ERROR_MSG_` | varchar(4000) | Truncated failure message reported by the worker. |
| `ERROR_DETAILS_ID_` | varchar(64) | Blob holding the full error details. |
| `ACT_ID_` | varchar(255) | BPMN element id of the service task. |
| `ACT_INST_ID_` | varchar(64) | Activity instance id of that element. |
| `EXECUTION_ID_` | varchar(64) | Execution the external task belonged to. |
| `PROC_INST_ID_` | varchar(64) | Owning process instance. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most instance of the call hierarchy. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key, denormalised. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `STATE_` | integer | Event/state code: created, failed, successful, deleted. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_HI_EXT_TASK_LOG_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_HI_EXT_TASK_LOG_PROCINST(PROC_INST_ID_)`, `ACT_HI_EXT_TASK_LOG_PROCDEF(PROC_DEF_ID_)`, `ACT_HI_EXT_TASK_LOG_PROC_DEF_KEY(PROC_DEF_KEY_)`, `ACT_HI_EXT_TASK_LOG_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_EXTTASKLOG_ERRORDET(ERROR_DETAILS_ID_)`, `ACT_HI_EXT_TASK_LOG_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_CASEINST

One row per CMMN case instance, running or closed. *(Empty unless CMMN definitions are deployed.)*

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | History record identifier (equal to the case instance id). **PK**, **NOT NULL** |
| `CASE_INST_ID_` | varchar(64) | The case instance id. **NOT NULL**, **UNIQUE** |
| `BUSINESS_KEY_` | varchar(255) | Caller-supplied domain correlation key. |
| `CASE_DEF_ID_` | varchar(64) | Case definition version the instance ran on. **NOT NULL** |
| `CREATE_TIME_` | timestamp | When the case instance was created. **NOT NULL** |
| `CLOSE_TIME_` | timestamp | When it was closed; `NULL` while open. |
| `DURATION_` | bigint | Lifetime in milliseconds. |
| `STATE_` | integer | Final CMMN case state code (active, completed, terminated, closed, …). |
| `CREATE_USER_ID_` | varchar(255) | User who created the case instance. |
| `SUPER_CASE_INSTANCE_ID_` | varchar(64) | Calling case instance, when started from a case task. |
| `SUPER_PROCESS_INSTANCE_ID_` | varchar(64) | Calling process instance, when started from a BPMN process. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_HI_CAS_I_CLOSE(CLOSE_TIME_)`, `ACT_IDX_HI_CAS_I_BUSKEY(BUSINESS_KEY_)`, `ACT_IDX_HI_CAS_I_TENANT_ID(TENANT_ID_)`.

#### ACT_HI_CASEACTINST

One row per CMMN plan-item (case activity) instance. *(Empty unless CMMN definitions are deployed.)*

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Case activity instance identifier. **PK**, **NOT NULL** |
| `PARENT_ACT_INST_ID_` | varchar(64) | Enclosing plan-item instance (the containing stage). |
| `CASE_DEF_ID_` | varchar(64) | Case definition version. **NOT NULL** |
| `CASE_INST_ID_` | varchar(64) | Owning case instance. **NOT NULL** |
| `CASE_ACT_ID_` | varchar(255) | CMMN plan-item id. **NOT NULL** |
| `TASK_ID_` | varchar(64) | Task created by this plan item, for human tasks. |
| `CALL_PROC_INST_ID_` | varchar(64) | Process instance started by this plan item, for process tasks. |
| `CALL_CASE_INST_ID_` | varchar(64) | Case instance started by this plan item, for case tasks. |
| `CASE_ACT_NAME_` | varchar(255) | Plan-item name. |
| `CASE_ACT_TYPE_` | varchar(255) | Plan-item type, e.g. `humanTask`, `stage`, `milestone`, `processTask`. |
| `CREATE_TIME_` | timestamp | When the plan-item instance was created. **NOT NULL** |
| `END_TIME_` | timestamp | When it ended; `NULL` while active. |
| `DURATION_` | bigint | Lifetime in milliseconds. |
| `STATE_` | integer | Final CMMN plan-item state code. |
| `REQUIRED_` | boolean | Whether the required rule evaluated true for this plan item. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_HI_CAS_A_I_CREATE(CREATE_TIME_)`, `ACT_IDX_HI_CAS_A_I_END(END_TIME_)`, `ACT_IDX_HI_CAS_A_I_COMP(CASE_ACT_ID_, END_TIME_, ID_)`, `ACT_IDX_HI_CAS_A_I_TENANT_ID(TENANT_ID_)`.

#### ACT_HI_DECINST

One row per DMN decision evaluation — every time a business rule task or the decision API evaluated
a decision.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Decision instance identifier. **PK**, **NOT NULL** |
| `DEC_DEF_ID_` | varchar(64) | Decision definition version evaluated. **NOT NULL** |
| `DEC_DEF_KEY_` | varchar(255) | Decision definition key, denormalised. **NOT NULL** |
| `DEC_DEF_NAME_` | varchar(255) | Decision name at evaluation time. |
| `PROC_DEF_KEY_` | varchar(255) | Process definition key of the calling process, if any. |
| `PROC_DEF_ID_` | varchar(64) | Process definition version of the caller. |
| `PROC_INST_ID_` | varchar(64) | Process instance that triggered the evaluation. |
| `CASE_DEF_KEY_` | varchar(255) | CMMN case definition key of the caller, if any. |
| `CASE_DEF_ID_` | varchar(64) | CMMN case definition version of the caller. |
| `CASE_INST_ID_` | varchar(64) | Case instance that triggered the evaluation. |
| `ACT_INST_ID_` | varchar(64) | Activity instance of the business rule task. |
| `ACT_ID_` | varchar(255) | BPMN element id of the business rule task. |
| `EVAL_TIME_` | timestamp | When the decision was evaluated. **NOT NULL** |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |
| `COLLECT_VALUE_` | double precision | Aggregated result when the decision table's hit policy is COLLECT with an aggregator (SUM/MIN/MAX/COUNT). |
| `USER_ID_` | varchar(255) | User who triggered a standalone evaluation via the API. |
| `ROOT_DEC_INST_ID_` | varchar(64) | The top-level decision evaluation, when this one was reached through a required-decision chain in a DRD. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most process instance of the call hierarchy. |
| `DEC_REQ_ID_` | varchar(64) | Decision-requirements definition the decision belongs to. |
| `DEC_REQ_KEY_` | varchar(255) | Key of that decision-requirements definition. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |

**Indexes:** `ACT_IDX_HI_DEC_INST_ID(DEC_DEF_ID_)`, `ACT_IDX_HI_DEC_INST_KEY(DEC_DEF_KEY_)`, `ACT_IDX_HI_DEC_INST_PI(PROC_INST_ID_)`, `ACT_IDX_HI_DEC_INST_CI(CASE_INST_ID_)`, `ACT_IDX_HI_DEC_INST_ACT(ACT_ID_)`, `ACT_IDX_HI_DEC_INST_ACT_INST(ACT_INST_ID_)`, `ACT_IDX_HI_DEC_INST_TIME(EVAL_TIME_)`, `ACT_IDX_HI_DEC_INST_TENANT_ID(TENANT_ID_)`, `ACT_IDX_HI_DEC_INST_ROOT_ID(ROOT_DEC_INST_ID_)`, `ACT_IDX_HI_DEC_INST_REQ_ID(DEC_REQ_ID_)`, `ACT_IDX_HI_DEC_INST_REQ_KEY(DEC_REQ_KEY_)`, `ACT_IDX_HI_DEC_INST_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_DEC_INST_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_DEC_IN

The input values a decision evaluation was given — one row per input clause of one evaluation.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Input entry identifier. **PK**, **NOT NULL** |
| `DEC_INST_ID_` | varchar(64) | The `ACT_HI_DECINST` evaluation this input belongs to. **NOT NULL** |
| `CLAUSE_ID_` | varchar(64) | DMN input clause id. |
| `CLAUSE_NAME_` | varchar(255) | DMN input clause label. |
| `VAR_TYPE_` | varchar(100) | Value serializer name; determines which value column is populated. |
| `BYTEARRAY_ID_` | varchar(64) | Blob holding the serialized value, when not inline-able. |
| `DOUBLE_` | double precision | Value when floating point. |
| `LONG_` | bigint | Value when integral, boolean or date. |
| `TEXT_` | varchar(4000) | Value when short text; for object values, the Java type name. |
| `TEXT2_` | varchar(4000) | Secondary text slot — serialization data format / value-info metadata. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_TIME_` | timestamp | When the input was recorded. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most process instance of the call hierarchy. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_DEC_IN_INST(DEC_INST_ID_)`, `ACT_IDX_HI_DEC_IN_CLAUSE(DEC_INST_ID_, CLAUSE_ID_)`, `ACT_IDX_HI_DEC_IN_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_DEC_IN_RM_TIME(REMOVAL_TIME_)`.

#### ACT_HI_DEC_OUT

The output values a decision evaluation produced — one row per output clause per matched rule.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Output entry identifier. **PK**, **NOT NULL** |
| `DEC_INST_ID_` | varchar(64) | The `ACT_HI_DECINST` evaluation this output belongs to. **NOT NULL** |
| `CLAUSE_ID_` | varchar(64) | DMN output clause id. |
| `CLAUSE_NAME_` | varchar(255) | DMN output clause label. |
| `RULE_ID_` | varchar(64) | Id of the decision-table rule that produced this output. |
| `RULE_ORDER_` | integer | Position of that rule in the table — orders the outputs when several rules matched. |
| `VAR_NAME_` | varchar(255) | Output variable name the value is assigned to. |
| `VAR_TYPE_` | varchar(100) | Value serializer name; determines which value column is populated. |
| `BYTEARRAY_ID_` | varchar(64) | Blob holding the serialized value, when not inline-able. |
| `DOUBLE_` | double precision | Value when floating point. |
| `LONG_` | bigint | Value when integral, boolean or date. |
| `TEXT_` | varchar(4000) | Value when short text; for object values, the Java type name. |
| `TEXT2_` | varchar(4000) | Secondary text slot — serialization data format / value-info metadata. |
| `TENANT_ID_` | varchar(64) | Tenant discriminator. |
| `CREATE_TIME_` | timestamp | When the output was recorded. |
| `ROOT_PROC_INST_ID_` | varchar(64) | Top-most process instance of the call hierarchy. |
| `REMOVAL_TIME_` | timestamp | Earliest time history cleanup may delete this row. |

**Indexes:** `ACT_IDX_HI_DEC_OUT_INST(DEC_INST_ID_)`, `ACT_IDX_HI_DEC_OUT_RULE(RULE_ORDER_, CLAUSE_ID_)`, `ACT_IDX_HI_DEC_OUT_ROOT_PI(ROOT_PROC_INST_ID_)`, `ACT_IDX_HI_DEC_OUT_RM_TIME(REMOVAL_TIME_)`.

---

## ACT_ID_* — identity

The engine's built-in user/group/tenant store. These tables are created because
`isDbIdentityUsed()` defaults to `true` and nothing in this service turns it off — the Keycloak
plugin's `preInit` only replaces the *read* path
(`setIdentityProviderSessionFactory(keycloakIdentityProviderFactory)`), it does not disable the
database identity schema.

**In this deployment they are effectively unused.** Users and groups are resolved live from
Keycloak (or Entra ID when `plugin.identity.provider: entra`), so `ACT_ID_USER`, `ACT_ID_GROUP`
and `ACT_ID_MEMBERSHIP` stay empty: no local accounts are created, no passwords are stored, and
the `PWD_` / `SALT_` columns are never populated. Rows would only appear if someone created an
identity through the engine's own Identity Service API, which the read-only Keycloak provider does
not support. `ACT_ID_TENANT` / `ACT_ID_TENANT_MEMBER` would only be populated by explicitly
configuring multi-tenancy, which this service does not do. The tables are documented here for
completeness and because operators will see them in the schema.

#### ACT_ID_GROUP

Engine-local user groups.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Group identifier — the value BPMN candidate-group expressions match against. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `NAME_` | varchar(255) | Human-readable group name. |
| `TYPE_` | varchar(255) | Group classification, e.g. `WORKFLOW` or `SECURITY-ROLE`. |

**Referenced by:** `ACT_ID_MEMBERSHIP.GROUP_ID_`, `ACT_ID_TENANT_MEMBER.GROUP_ID_`.

#### ACT_ID_MEMBERSHIP

The user-to-group join table.

| Column | Type | Description |
|---|---|---|
| `USER_ID_` | varchar(64) | Member user. **PK** (composite), **FK →** `ACT_ID_USER(ID_)` |
| `GROUP_ID_` | varchar(64) | Group joined. **PK** (composite), **FK →** `ACT_ID_GROUP(ID_)` |

**Indexes:** `ACT_IDX_MEMB_GROUP(GROUP_ID_)`, `ACT_IDX_MEMB_USER(USER_ID_)`.

#### ACT_ID_USER

Engine-local user accounts, including the hashed-password fields used by the engine's own form
login. Unused here — authentication is OIDC via Keycloak or Entra ID.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | User identifier — the value that appears as a task assignee. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `FIRST_` | varchar(255) | Given name. |
| `LAST_` | varchar(255) | Family name. |
| `EMAIL_` | varchar(255) | Email address. |
| `PWD_` | varchar(255) | Salted password hash. Never populated in this deployment. |
| `SALT_` | varchar(255) | Per-user password salt. Never populated in this deployment. |
| `LOCK_EXP_TIME_` | timestamp | When a lockout from repeated failed logins expires. |
| `ATTEMPTS_` | integer | Consecutive failed login attempts. |
| `PICTURE_ID_` | varchar(64) | Blob in `ACT_GE_BYTEARRAY` holding the user's profile picture. |

**Referenced by:** `ACT_ID_MEMBERSHIP.USER_ID_`, `ACT_ID_TENANT_MEMBER.USER_ID_`.

#### ACT_ID_INFO

Additional per-user account information — account credentials for external systems and arbitrary
user attributes.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Info entry identifier. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `USER_ID_` | varchar(64) | User the entry belongs to. |
| `TYPE_` | varchar(64) | Entry kind: `userAccount` for an external account, `userInfo` for a plain attribute. |
| `KEY_` | varchar(255) | Attribute key, or the external account name. |
| `VALUE_` | varchar(255) | Attribute value, or the external username. |
| `PASSWORD_` | bytea | Encrypted password of the external account. |
| `PARENT_ID_` | varchar(255) | Owning `userAccount` entry for detail rows, which is how one account row carries several key/value details. Note the width: it references `ACT_ID_INFO.ID_`, which is `varchar(64)`. |

#### ACT_ID_TENANT

Tenants, for engine multi-tenancy. Empty in this single-tenant deployment.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Tenant identifier — the value written into every `TENANT_ID_` column. **PK** |
| `REV_` | integer | Optimistic-locking revision. |
| `NAME_` | varchar(255) | Human-readable tenant name. |

**Referenced by:** `ACT_ID_TENANT_MEMBER.TENANT_ID_`.

#### ACT_ID_TENANT_MEMBER

Which users and groups belong to which tenant. Each row links a tenant to exactly one of a user or
a group.

| Column | Type | Description |
|---|---|---|
| `ID_` | varchar(64) | Membership identifier. **PK**, **NOT NULL** |
| `TENANT_ID_` | varchar(64) | Tenant. **NOT NULL**, **FK →** `ACT_ID_TENANT(ID_)` |
| `USER_ID_` | varchar(64) | Member user; mutually exclusive with `GROUP_ID_`. **FK →** `ACT_ID_USER(ID_)` |
| `GROUP_ID_` | varchar(64) | Member group; mutually exclusive with `USER_ID_`. **FK →** `ACT_ID_GROUP(ID_)` |

**Unique constraints:** `ACT_UNIQ_TENANT_MEMB_USER (TENANT_ID_, USER_ID_)`, `ACT_UNIQ_TENANT_MEMB_GROUP (TENANT_ID_, GROUP_ID_)`.

**Indexes:** `ACT_IDX_TENANT_MEMB(TENANT_ID_)`, `ACT_IDX_TENANT_MEMB_USER(USER_ID_)`, `ACT_IDX_TENANT_MEMB_GROUP(GROUP_ID_)`.

---

## Notes and caveats

- **Types are verbatim from the PostgreSQL DDL**, including upstream quirks reproduced rather than
  corrected — e.g. `ACT_ID_INFO.PARENT_ID_` is `varchar(255)` while the `ID_` it references is
  `varchar(64)`.
- **Most `*_ID_` columns are not database foreign keys.** The FK markers above list only the
  constraints the DDL actually declares. In particular, no `ACT_HI_*` table declares a single
  foreign key: history must survive the deletion of the runtime rows it describes, so those
  references are maintained by the engine alone.
- **Only three tables carry unique constraints beyond their primary key:**
  `ACT_RU_AUTHORIZATION` (two), `ACT_RU_VARIABLE` (`VAR_SCOPE_ + NAME_`), and
  `ACT_ID_TENANT_MEMBER` (two). `ACT_HI_PROCINST` and `ACT_HI_CASEINST` additionally declare an
  inline unique on their instance-id column.
- **The engine owns this schema.** Do not add application tables to `cadenzaflow`, and do not alter
  `ACT_*` tables by hand.
- **`schema-update: true` creates; it does not migrate.** On boot the engine runs
  `dbSchemaUpdate()`, which creates only the sub-schemas whose tables are *absent* and leaves every
  existing table untouched — it issues no `ALTER` and performs no version comparison. An engine
  upgrade that ships new DDL therefore needs a deliberate upgrade step; a restart alone will not
  apply it, and the mismatch surfaces at runtime rather than at boot. Setting `schema-update: false`
  switches the engine to a read-only assertion instead (`dbSchemaCheckVersion()`), which is the
  right setting when the schema is applied out of band by a migration job.
- **Liquibase is not a usable alternative here.** The changelog shipped inside the engine jar has
  had its baseline changeset removed, so it cannot create this schema from empty — see
  [README §7](../README.md#7-data).
