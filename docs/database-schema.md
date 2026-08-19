# Database schema reference

`opentmf-cadenzaflow` embeds the CadenzaFlow BPM engine (a Camunda 7 derivative) and lets the
engine own its own schema. On start-up the engine creates every table it needs; the application
declares no JPA entities and ships no Flyway/Liquibase migrations of its own.

> ## ⚠ This schema is the engine's private storage, not an API
>
> **Upstream says so in as many words.** From CadenzaFlow's own manual, on the
> [database schema page](https://docs.cadenzaflow.org/manual/latest/user-guide/process-engine/database/database-schema/):
>
> > "The database is not part of the public API. The database schema may change for MINOR
> > and MAJOR version updates."
>
> That is not a turn of phrase — "public API" is a defined term. The
> [public API page](https://docs.cadenzaflow.org/manual/latest/introduction/public-api/)
> limits it to the non-`impl` Java packages of the engine modules and the REST API's HTTP
> interface, and backwards compatibility is promised for those alone across MINOR updates.
> The tables are deliberately outside that promise. Only *patch* releases are guaranteed
> not to change the schema.
>
> So everything below can change in any minor release — columns added or dropped, types
> widened, tables introduced or removed, indexes reworked. Neither this service nor OpenTMF
> controls their shape.
>
> **What upstream does and does not say.** It declares the schema outside the public API
> and free to change; it does *not* separately forbid reading the tables. The advice below
> is therefore ours, drawn from that fact rather than quoted from upstream — but the
> consequence is real: anything you build directly on these tables is built on something
> the engine may change without it being a breaking change.
>
> **Do not build on it.** Concretely, do not:
> - write to any `ACT_*` table, ever — the engine caches and version-checks rows through
>   `REV_`, so an outside `UPDATE` corrupts state that looks fine until an
>   `OptimisticLockingException` appears somewhere unrelated;
> - point a reporting tool, ETL job or dashboard at these tables and expect it to survive an
>   engine upgrade;
> - create your own tables inside the `cadenzaflow` schema, or add foreign keys, triggers or
>   indexes to the engine's tables — the engine's own migration steps do not know about them.
>
> **The supported ways in are the engine REST API (`/engine-rest`), the history and metrics
> endpoints it exposes, and Cockpit.** Everything in this document is stable enough to
> *understand and troubleshoot* a running system, which is exactly what it is for: reading
> the schema to diagnose an incident, size a database, or understand what history cleanup
> deletes. It is not a licence to integrate against it.
>
> **This document describes the schema as created by the engine version this service
> currently embeds** (`cadenzaflow.version` in `pom.xml`), derived from that engine's own
> PostgreSQL `db/create/activiti.postgres.create.*.sql` scripts. After an engine upgrade,
> treat it as stale until regenerated. To check what a *live* database is actually at, read
> `ACT_GE_SCHEMA_LOG` — the engine's supported query for it is
> `managementService.createSchemaLogQuery()`.

### How this relates to upstream documentation

Upstream publishes a
[database schema page](https://docs.cadenzaflow.org/manual/latest/user-guide/process-engine/database/database-schema/)
(a rebrand of Camunda 7's, which CibSeven also carries). It is worth reading, but it is
deliberately shallow: it explains the five table-name prefixes, gives a sentence or two of
prose for **eight** tables out of the forty-nine, and links a set of entity-relationship
diagrams.

**There is no official per-column reference for these tables — from any of the three
forks.** The upstream ERDs do list every column with a type, but they are rendered images
with no selectable text and no descriptions, and upstream generates them **from the MySQL
schema**, warning that "for other databases the diagram may be slightly different". This
document is derived from the **PostgreSQL** DDL, which is what this service actually runs,
so a type that differs from the upstream diagram is expected rather than an error.

That gap is why this file exists. It is additive to upstream documentation, not a
restatement of it.

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

One specification table per database table, in the shape used by the platform's
component-design cards.

| Field | Meaning |
|---|---|
| **Column** | Column name, exactly as created |
| **isPK?** | `Y` when the column is part of the table's primary key |
| **Type** | SQL datatype verbatim from the PostgreSQL DDL |
| **nullable?** | `N` when the DDL declares `not null`, otherwise `Y` |
| **isUnique** | `Y` when a unique constraint covers the column (the constraint is named under the table) |
| **default** | Default value from the DDL, blank when none |
| **Foreign Key** | The declared referential constraint, blank when none — most `*_ID_` columns are *logical* references the engine maintains itself and carry no database constraint |
| **Notes** | What the column holds |

Columns appear in DDL order. Two columns recur across almost every table and are worth
understanding once:

- **`REV_`** is the optimistic-locking revision counter. The engine reads it with the
  row, increments it on update, and adds `WHERE REV_ = <read value>`; a zero-row update
  means another node changed the row first and the engine raises
  `OptimisticLockingException`. It is not a business version number and never appears in
  the REST API. History tables mostly have no `REV_`, because history rows are
  append-only.
- **`TENANT_ID_`** is the multi-tenancy discriminator, `NULL` in a single-tenant
  deployment, which is what this service runs. It is a soft key: no foreign key to
  `ACT_ID_TENANT` exists on the runtime or history tables.

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

#### Table-1: `ACT_GE_PROPERTY`

Engine-wide key/value properties, including the schema version, the ID generator's next-block
pointer, and the cluster-wide advisory locks the engine takes on start-up, deployment and history
cleanup.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `NAME_` | Y | `varchar(64)` | N |  |  |  | Property key. Seeded with `schema.version`, `schema.history`, `next.dbid`, `deployment.lock`, `history.cleanup.job.lock`, `startup.lock`, `installationId.lock` |
| `VALUE_` |  | `varchar(300)` | Y |  |  |  | Property value. For the `*.lock` rows the value is irrelevant — the row exists so nodes can serialise on it via `SELECT ... FOR UPDATE` |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |

#### Table-2: `ACT_GE_BYTEARRAY`

The engine's blob store: every byte payload owned by any other table lives here, keyed by an ID the
owning row holds.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Blob identifier, referenced by the owning row |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Logical name — for deployment resources, the resource path inside the deployment (e.g. `order-process.bpmn`) |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_DEPLOYMENT.ID_` | Owning deployment, for deployment resources; `NULL` for variable/exception blobs |
| `BYTES_` |  | `bytea` | Y |  |  |  | The binary payload itself |
| `GENERATED_` |  | `boolean` | Y |  |  |  | True when the engine produced the resource rather than the deployer — e.g. an auto-generated process diagram |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `TYPE_` |  | `integer` | Y |  |  |  | Resource scope, from `ResourceTypes`: `1` = REPOSITORY (deployment resource), `2` = RUNTIME, `3` = HISTORY. Determines whether history cleanup may remove the row |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the blob was written |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Root process instance the blob ultimately belongs to, denormalised so history cleanup can find and remove a whole instance's blobs in one pass |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row; set for `HISTORY`-typed blobs from the owning instance's TTL |

**Indexes:** `ACT_IDX_BYTEAR_DEPL` (DEPLOYMENT_ID_), `ACT_IDX_BYTEARRAY_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_BYTEARRAY_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_BYTEARRAY_NAME` (NAME_)

#### Table-3: `ACT_GE_SCHEMA_LOG`

Append-only log of schema versions applied to this database, one row per create or upgrade step.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Log entry identifier. Seeded with `'0'` for the initial create |
| `TIMESTAMP_` |  | `timestamp` | Y |  |  |  | When this schema version was applied |
| `VERSION_` |  | `varchar(255)` | Y |  |  |  | Engine schema version applied (initial row: `1.2.0`) |

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

#### Table-4: `ACT_RE_DEPLOYMENT`

One row per deployment operation — the unit of packaging that carries one or more definition
resources.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Deployment identifier |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Deployment name supplied by the deployer |
| `DEPLOY_TIME_` |  | `timestamp` | Y |  |  |  | When the deployment was made |
| `SOURCE_` |  | `varchar(255)` | Y |  |  |  | Origin marker, e.g. `process application`, or the value passed by the REST caller |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_DEPLOYMENT_NAME` (NAME_), `ACT_IDX_DEPLOYMENT_TENANT_ID` (TENANT_ID_)

#### Table-5: `ACT_RE_PROCDEF`

One row per deployed, versioned BPMN process definition.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Process definition identifier, conventionally `key:version:dbid` |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CATEGORY_` |  | `varchar(255)` | Y |  |  |  | The BPMN `targetNamespace` of the defining XML |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable process name from the BPMN |
| `KEY_` |  | `varchar(255)` | N |  |  |  | Stable process key (the BPMN `process id`); constant across versions |
| `VERSION_` |  | `integer` | N |  |  |  | Version number within `KEY_` + tenant, incremented on each redeploy |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment that introduced this version |
| `RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Name of the deployment resource holding the BPMN XML, resolved against `ACT_GE_BYTEARRAY` |
| `DGRM_RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Name of the deployment resource holding the rendered diagram image, if any |
| `HAS_START_FORM_KEY_` |  | `boolean` | Y |  |  |  | True when the start event declares a form key — lets the webapp skip a lookup |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended. A suspended definition starts no new instances |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator; versioning is per key *and* tenant |
| `VERSION_TAG_` |  | `varchar(64)` | Y |  |  |  | Free-text `camunda:versionTag`, a deployer-controlled label independent of `VERSION_` |
| `HISTORY_TTL_` |  | `integer` | Y |  |  |  | Retention in days for this definition's finished instances. Set from `camunda:historyTimeToLive` or the REST endpoint; falls back to the engine default `P30D` when absent |
| `STARTABLE_` |  | `boolean` | N |  | `TRUE` |  | Whether the definition may be started from Tasklist (`camunda:isStartableInTasklist`) |

**Indexes:** `ACT_IDX_PROCDEF_DEPLOYMENT_ID` (DEPLOYMENT_ID_), `ACT_IDX_PROCDEF_TENANT_ID` (TENANT_ID_), `ACT_IDX_PROCDEF_VER_TAG` (VERSION_TAG_)

#### Table-6: `ACT_RE_CAMFORMDEF`

One row per deployed, versioned Camunda Form definition (`.form` resources).

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Form definition identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `KEY_` |  | `varchar(255)` | N |  |  |  | Stable form key, constant across versions |
| `VERSION_` |  | `integer` | N |  |  |  | Version number within `KEY_` + tenant |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment that introduced this version |
| `RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the form JSON |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

#### Table-7: `ACT_RE_CASE_DEF`

One row per deployed, versioned CMMN case definition. *(Created because CMMN is enabled by default;
this service deploys no CMMN today, so the table stays empty in practice.)*

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Case definition identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CATEGORY_` |  | `varchar(255)` | Y |  |  |  | The CMMN `targetNamespace` |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable case name |
| `KEY_` |  | `varchar(255)` | N |  |  |  | Stable case key, constant across versions |
| `VERSION_` |  | `integer` | N |  |  |  | Version number within `KEY_` + tenant |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment that introduced this version |
| `RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the CMMN XML |
| `DGRM_RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the rendered diagram, if any |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `HISTORY_TTL_` |  | `integer` | Y |  |  |  | Retention in days for this definition's closed case instances |

**Indexes:** `ACT_IDX_CASE_DEF_TENANT_ID` (TENANT_ID_)

#### Table-8: `ACT_RE_DECISION_DEF`

One row per deployed, versioned DMN decision (a single decision table / literal expression).

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Decision definition identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CATEGORY_` |  | `varchar(255)` | Y |  |  |  | The DMN `namespace` |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable decision name |
| `KEY_` |  | `varchar(255)` | N |  |  |  | Stable decision key (the DMN `decision id`) |
| `VERSION_` |  | `integer` | N |  |  |  | Version number within `KEY_` + tenant |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment that introduced this version |
| `RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the DMN XML |
| `DGRM_RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the rendered diagram, if any |
| `DEC_REQ_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_DECISION_REQ_DEF.ID_` | Decision-requirements definition this decision belongs to, when the DMN file contains more than one decision |
| `DEC_REQ_KEY_` |  | `varchar(255)` | Y |  |  |  | Key of that decision-requirements definition, denormalised |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `HISTORY_TTL_` |  | `integer` | Y |  |  |  | Retention in days for this decision's historic evaluations |
| `VERSION_TAG_` |  | `varchar(64)` | Y |  |  |  | Free-text version label, independent of `VERSION_` |

**Indexes:** `ACT_IDX_DEC_DEF_TENANT_ID` (TENANT_ID_), `ACT_IDX_DEC_DEF_REQ_ID` (DEC_REQ_ID_)

#### Table-9: `ACT_RE_DECISION_REQ_DEF`

One row per deployed DMN decision-requirements graph (DRD) — the container when a `.dmn` file
declares several interrelated decisions.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | DRD identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CATEGORY_` |  | `varchar(255)` | Y |  |  |  | The DMN `namespace` |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable DRD name |
| `KEY_` |  | `varchar(255)` | N |  |  |  | Stable DRD key |
| `VERSION_` |  | `integer` | N |  |  |  | Version number within `KEY_` + tenant |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment that introduced this version |
| `RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the DMN XML |
| `DGRM_RESOURCE_NAME_` |  | `varchar(4000)` | Y |  |  |  | Deployment resource holding the rendered diagram, if any |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_DEC_REQ_DEF_TENANT_ID` (TENANT_ID_)

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

#### Table-10: `ACT_RU_EXECUTION`

The execution tree of every running process instance — one row for the instance itself plus one per
live concurrent branch, sub-process scope or event scope.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Execution identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most process instance of the call-activity hierarchy this execution sits in; denormalised so history cleanup can address a whole tree |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | The process instance this execution belongs to; equals `ID_` on the instance row itself |
| `BUSINESS_KEY_` |  | `varchar(255)` | Y |  |  |  | Caller-supplied domain correlation key (order number, ticket id, …). Set on the instance row |
| `PARENT_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Parent execution in the tree; `NULL` on the instance root |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_PROCDEF.ID_` | Process definition version this instance is running |
| `SUPER_EXEC_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | The call-activity execution in the *calling* process that started this instance |
| `SUPER_CASE_EXEC_` |  | `varchar(64)` | Y |  |  |  | The CMMN case execution that started this process instance, when launched from a case |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Case instance this execution belongs to, when launched from CMMN |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id the execution is currently sitting at |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Current activity-instance id — the runtime instance of `ACT_ID_`, distinguishing repeated visits to the same element |
| `IS_ACTIVE_` |  | `boolean` | Y |  |  |  | Whether this execution is currently executing rather than parked (an inactive parent whose children are running) |
| `IS_CONCURRENT_` |  | `boolean` | Y |  |  |  | Whether this execution is one of several parallel siblings |
| `IS_SCOPE_` |  | `boolean` | Y |  |  |  | Whether this execution defines a variable/event scope (sub-process, multi-instance body) |
| `IS_EVENT_SCOPE_` |  | `boolean` | Y |  |  |  | Whether this execution exists only to keep compensation/boundary-event handlers reachable after its activity ended |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended |
| `CACHED_ENT_STATE_` |  | `integer` | Y |  |  |  | Bitmask recording which related collections (tasks, jobs, event subscriptions, incidents, variables, external tasks, sub-instances) are non-empty, so the engine can skip queries that would return nothing. Pure performance metadata |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter used to order history events emitted by this execution |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised from `ACT_RE_PROCDEF` to avoid a join on hot query paths |

**Indexes:** `ACT_IDX_EXE_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_EXEC_BUSKEY` (BUSINESS_KEY_), `ACT_IDX_EXEC_TENANT_ID` (TENANT_ID_), `ACT_IDX_EXE_PROCINST` (PROC_INST_ID_), `ACT_IDX_EXE_PARENT` (PARENT_ID_), `ACT_IDX_EXE_SUPER` (SUPER_EXEC_), `ACT_IDX_EXE_PROCDEF` (PROC_DEF_ID_)

#### Table-11: `ACT_RU_JOB`

Every unit of work the job executor still has to run: timers, async continuations, message jobs,
batch seed/monitor/execution jobs and the history-cleanup job.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Job identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `TYPE_` |  | `varchar(255)` | N |  |  |  | Job discriminator: `timer`, `message`, `event` |
| `LOCK_EXP_TIME_` |  | `timestamp` | Y |  |  |  | When the current executor's lock on this job expires; another node may take it after that |
| `LOCK_OWNER_` |  | `varchar(255)` | Y |  |  |  | Identifier of the job executor instance currently holding the job. This is how the cluster avoids running the same job twice |
| `EXCLUSIVE_` |  | `boolean` | Y |  |  |  | When true, this job will not run concurrently with another exclusive job of the same process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution the job belongs to. Indexed, but no database foreign key — the engine maintains the reference itself |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Root process instance of the hierarchy, denormalised |
| `PROCESS_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance the job belongs to |
| `PROCESS_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version the job belongs to |
| `PROCESS_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `RETRIES_` |  | `integer` | Y |  |  |  | Attempts remaining. Reaching `0` turns the failure into an incident and the job stops being acquired |
| `EXCEPTION_STACK_ID_` |  | `varchar(64)` | Y |  |  | `ACT_GE_BYTEARRAY.ID_` | Blob holding the full stack trace of the last failure |
| `EXCEPTION_MSG_` |  | `varchar(4000)` | Y |  |  |  | Truncated message of the last failure |
| `FAILED_ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id at which the last failure occurred |
| `DUEDATE_` |  | `timestamp` | Y |  |  |  | Earliest time the job may be acquired; the timer fire time for timer jobs |
| `REPEAT_` |  | `varchar(255)` | Y |  |  |  | Recurrence expression for cycle timers (ISO-8601 repeating interval or cron) |
| `REPEAT_OFFSET_` |  | `bigint` | Y |  | `0` |  | Millisecond offset applied when computing the next occurrence of a repeating timer, so drift does not accumulate. Default `0` |
| `HANDLER_TYPE_` |  | `varchar(255)` | Y |  |  |  | Handler that will execute the job, e.g. `async-continuation`, `timer-transition`, `history-cleanup` |
| `HANDLER_CFG_` |  | `varchar(4000)` | Y |  |  |  | Handler-specific configuration payload |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment the job belongs to; used by deployment-aware job execution to keep a job on a node that has the definition |
| `SUSPENSION_STATE_` |  | `integer` | N |  | `1` |  | `1` = active, `2` = suspended |
| `JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition this job is an instance of |
| `PRIORITY_` |  | `bigint` | N |  | `0` |  | Acquisition priority; higher is acquired first |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter ordering the job's history events |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the job row was created |
| `LAST_FAILURE_LOG_ID_` |  | `varchar(64)` | Y |  |  |  | `ACT_HI_JOB_LOG` entry recording the most recent failure, for direct navigation from an incident |
| `BATCH_ID_` |  | `varchar(64)` | Y |  |  |  | Batch this job belongs to, for batch operations |

**Indexes:** `ACT_IDX_JOB_EXECUTION_ID` (EXECUTION_ID_), `ACT_IDX_JOB_HANDLER` (HANDLER_TYPE_, HANDLER_CFG_), `ACT_IDX_JOB_PROCINST` (PROCESS_INSTANCE_ID_), `ACT_IDX_JOB_ROOT_PROCINST` (ROOT_PROC_INST_ID_), `ACT_IDX_JOB_TENANT_ID` (TENANT_ID_), `ACT_IDX_JOB_JOB_DEF_ID` (JOB_DEF_ID_), `ACT_IDX_JOB_EXCEPTION` (EXCEPTION_STACK_ID_), `ACT_IDX_JOB_HANDLER_TYPE` (HANDLER_TYPE_)

#### Table-12: `ACT_RU_JOBDEF`

The static "job slots" derived from a deployed definition — one row per asynchronous element or
timer in the BPMN — used to suspend or re-prioritise all jobs of a kind at once.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Job definition identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version this job definition was derived from |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id the jobs are created for |
| `JOB_TYPE_` |  | `varchar(255)` | N |  |  |  | Job type produced: `timer`, `async-continuation`, `message` |
| `JOB_CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Type-specific configuration, e.g. the timer's cycle expression or `async-before` / `async-after` |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended. Suspending here suspends every job of this definition |
| `JOB_PRIORITY_` |  | `bigint` | Y |  |  |  | Overriding priority applied to jobs created from this definition |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment this job definition came from |

**Indexes:** `ACT_IDX_JOBDEF_TENANT_ID` (TENANT_ID_), `ACT_IDX_JOBDEF_PROC_DEF_ID` (PROC_DEF_ID_)

#### Table-13: `ACT_RU_TASK`

Every human task currently waiting for someone to complete it. The row is deleted on completion or
deletion; the audit copy lives in `ACT_HI_TASKINST`.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Task identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Execution that created and is waiting on this task |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Owning process instance |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_PROCDEF.ID_` | Process definition version |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Owning CMMN case execution, for case-driven tasks |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning CMMN case instance |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_CASE_DEF.ID_` | CMMN case definition version |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Task name shown in Tasklist |
| `PARENT_TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Parent task, for sub-tasks |
| `DESCRIPTION_` |  | `varchar(4000)` | Y |  |  |  | Task description |
| `TASK_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the user task — the stable key that identifies "which task in the model" this is |
| `OWNER_` |  | `varchar(255)` | Y |  |  |  | User who owns the task (the delegator, when delegated) |
| `ASSIGNEE_` |  | `varchar(255)` | Y |  |  |  | User currently responsible for completing the task; `NULL` while unclaimed |
| `DELEGATION_` |  | `varchar(64)` | Y |  |  |  | Delegation state: `PENDING` (delegated, awaiting the delegate) or `RESOLVED` |
| `PRIORITY_` |  | `integer` | Y |  |  |  | Task priority |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the task was created |
| `LAST_UPDATED_` |  | `timestamp` | Y |  |  |  | When the task was last modified |
| `DUE_DATE_` |  | `timestamp` | Y |  |  |  | When the task is due |
| `FOLLOW_UP_DATE_` |  | `timestamp` | Y |  |  |  | Follow-up reminder date |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `TASK_STATE_` |  | `varchar(64)` | Y |  |  |  | Lifecycle state from `TaskEntity.TaskState`: `Init`, `Created`, `Completed`, `Deleted`, `Updated` |

**Indexes:** `ACT_IDX_TASK_CASE_EXEC` (CASE_EXECUTION_ID_), `ACT_IDX_TASK_CASE_DEF_ID` (CASE_DEF_ID_), `ACT_IDX_TASK_CREATE` (CREATE_TIME_), `ACT_IDX_TASK_LAST_UPDATED` (LAST_UPDATED_), `ACT_IDX_TASK_ASSIGNEE` (ASSIGNEE_), `ACT_IDX_TASK_OWNER` (OWNER_), `ACT_IDX_TASK_TENANT_ID` (TENANT_ID_), `ACT_IDX_TASK_EXEC` (EXECUTION_ID_), `ACT_IDX_TASK_PROCINST` (PROC_INST_ID_), `ACT_IDX_TASK_PROCDEF` (PROC_DEF_ID_)

#### Table-14: `ACT_RU_IDENTITYLINK`

Who is associated with a live task (or a process definition) and in what role — candidates,
assignees and owners.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Identity link identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `GROUP_ID_` |  | `varchar(255)` | Y |  |  |  | Group side of the link; mutually exclusive with `USER_ID_` |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Link type: `candidate`, `assignee`, `owner` |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | User side of the link; mutually exclusive with `GROUP_ID_` |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_TASK.ID_` | Task the link applies to |
| `PROC_DEF_ID_` |  | `varchar (64)` | Y |  |  | `ACT_RE_PROCDEF.ID_` | Process definition the link applies to, for definition-level candidate starters |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_IDENT_LNK_USER` (USER_ID_), `ACT_IDX_IDENT_LNK_GROUP` (GROUP_ID_), `ACT_IDX_TSKASS_TASK` (TASK_ID_), `ACT_IDX_ATHRZ_PROCEDEF` (PROC_DEF_ID_)

#### Table-15: `ACT_RU_VARIABLE`

The current value of every process, case, task or batch variable. Exactly one row per variable per
scope; updating a variable overwrites this row and appends a new `ACT_HI_DETAIL` entry.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Variable instance identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `TYPE_` |  | `varchar(255)` | N |  |  |  | Value serializer name: `string`, `long`, `double`, `boolean`, `date`, `json`, `object`, `bytes`, `file`, … It determines which of the value columns below is populated |
| `NAME_` |  | `varchar(255)` | N | Y |  |  | Variable name |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Execution the variable is local to |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Owning process instance |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version, denormalised |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Owning CMMN case execution |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Owning CMMN case instance |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Owning task, for task-local variables |
| `BATCH_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_BATCH.ID_` | Owning batch, for batch configuration variables |
| `BYTEARRAY_ID_` |  | `varchar(64)` | Y |  |  | `ACT_GE_BYTEARRAY.ID_` | Blob holding the serialized value when it does not fit the inline columns (object, JSON, bytes, file) |
| `DOUBLE_` |  | `double precision` | Y |  |  |  | Value when numeric and floating point |
| `LONG_` |  | `bigint` | Y |  |  |  | Value when integral, boolean (0/1) or a date (epoch millis) |
| `TEXT_` |  | `varchar(4000)` | Y |  |  |  | Value when short text; for object variables, the value's Java type name |
| `TEXT2_` |  | `varchar(4000)` | Y |  |  |  | Secondary text slot: for object/file variables it carries the serialization data format and metadata; for typed values it holds the value-info payload |
| `VAR_SCOPE_` |  | `varchar(64)` | Y | Y |  |  | Id of the scope that owns this variable (execution, task, case execution or batch id). Backs the `ACT_UNIQ_VARIABLE` constraint, which is what prevents two rows for the same variable name in the same scope |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter ordering this variable's history events |
| `IS_CONCURRENT_LOCAL_` |  | `boolean` | Y |  |  |  | True when the variable is local to a concurrent execution rather than hoisted to the parent scope |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Unique constraints:** `ACT_UNIQ_VARIABLE` (VAR_SCOPE_, NAME_)

**Indexes:** `ACT_IDX_VAR_CASE_EXE` (CASE_EXECUTION_ID_), `ACT_IDX_VAR_CASE_INST_ID` (CASE_INST_ID_), `ACT_IDX_VARIABLE_TASK_ID` (TASK_ID_), `ACT_IDX_VARIABLE_TENANT_ID` (TENANT_ID_), `ACT_IDX_VARIABLE_TASK_NAME_TYPE` (TASK_ID_, NAME_, TYPE_), `ACT_IDX_VAR_EXE` (EXECUTION_ID_), `ACT_IDX_VAR_PROCINST` (PROC_INST_ID_), `ACT_IDX_VAR_BYTEARRAY` (BYTEARRAY_ID_), `ACT_IDX_BATCH_ID` (BATCH_ID_)

#### Table-16: `ACT_RU_EVENT_SUBSCR`

Every event an execution is currently waiting for — message and signal catch events, and
compensation handlers registered for later.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Subscription identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `EVENT_TYPE_` |  | `varchar(255)` | N |  |  |  | `message`, `signal`, `compensate`, or `conditional` |
| `EVENT_NAME_` |  | `varchar(255)` | Y |  |  |  | Name of the message or signal being awaited — the value a correlation call must match |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Execution waiting on the event |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `ACTIVITY_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the catching event |
| `CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Subscription-specific payload — for compensation, the id of the execution to compensate; for start-event subscriptions, the process definition id |
| `CREATED_` |  | `timestamp` | N |  |  |  | When the subscription was created |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_EVENT_SUBSCR_CONFIG_` (CONFIGURATION_), `ACT_IDX_EVENT_SUBSCR_TENANT_ID` (TENANT_ID_), `ACT_IDX_EVENT_SUBSCR` (EXECUTION_ID_), `ACT_IDX_EVENT_SUBSCR_EVT_NAME` (EVENT_NAME_)

#### Table-17: `ACT_RU_INCIDENT`

Open incidents — a job that exhausted its retries, an unresolvable external task, or a failed
BPMN error escalation. The row is deleted when the incident is resolved.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Incident identifier |
| `REV_` |  | `integer` | N |  |  |  | Optimistic-locking revision |
| `INCIDENT_TIMESTAMP_` |  | `timestamp` | N |  |  |  | When the incident was raised |
| `INCIDENT_MSG_` |  | `varchar(4000)` | Y |  |  |  | Failure message |
| `INCIDENT_TYPE_` |  | `varchar(255)` | N |  |  |  | Incident type: `failedJob`, `failedExternalTask`, or a custom type |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Execution the incident is attached to |
| `ACTIVITY_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id the incident is attached to |
| `FAILED_ACTIVITY_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id where the failure actually occurred — can differ from `ACTIVITY_ID_` when the incident is propagated to a parent scope |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Owning process instance |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_PROCDEF.ID_` | Process definition version |
| `CAUSE_INCIDENT_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_INCIDENT.ID_` | Incident one level down that caused this one, when an incident propagates from a called process |
| `ROOT_CAUSE_INCIDENT_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_INCIDENT.ID_` | Originating incident at the bottom of that chain |
| `CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Reference to the failing entity — the job id for `failedJob`, the external task id for `failedExternalTask` |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_JOBDEF.ID_` | Job definition of the failing job |
| `ANNOTATION_` |  | `varchar(4000)` | Y |  |  |  | Free-text operator annotation added from Cockpit |

**Indexes:** `ACT_IDX_INC_CONFIGURATION` (CONFIGURATION_), `ACT_IDX_INC_TENANT_ID` (TENANT_ID_), `ACT_IDX_INC_JOB_DEF` (JOB_DEF_ID_), `ACT_IDX_INC_CAUSEINCID` (CAUSE_INCIDENT_ID_), `ACT_IDX_INC_EXID` (EXECUTION_ID_), `ACT_IDX_INC_PROCDEFID` (PROC_DEF_ID_), `ACT_IDX_INC_PROCINSTID` (PROC_INST_ID_), `ACT_IDX_INC_ROOTCAUSEINCID` (ROOT_CAUSE_INCIDENT_ID_)

#### Table-18: `ACT_RU_AUTHORIZATION`

The engine's access-control list: which user or group may do what to which resource. Long-lived
configuration, not instance state. Active because `cadenzaflow.bpm.authorization.enabled: true`.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Authorization identifier |
| `REV_` |  | `integer` | N |  |  |  | Optimistic-locking revision |
| `TYPE_` |  | `integer` | N | Y |  |  | Authorization type: `0` = global, `1` = grant, `2` = revoke |
| `GROUP_ID_` |  | `varchar(255)` | Y | Y |  |  | Group the authorization applies to; mutually exclusive with `USER_ID_` |
| `USER_ID_` |  | `varchar(255)` | Y | Y |  |  | User the authorization applies to; `*` means everyone |
| `RESOURCE_TYPE_` |  | `integer` | N | Y |  |  | Numeric resource kind (process definition, process instance, task, deployment, user, group, …) from the `Resources` enum |
| `RESOURCE_ID_` |  | `varchar(255)` | Y | Y |  |  | Specific resource id, or `*` for all resources of that type |
| `PERMS_` |  | `integer` | Y |  |  |  | Bitmask of granted/revoked permissions (READ, UPDATE, CREATE, DELETE, …) |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | For instance-scoped authorizations created alongside a process instance, the time history cleanup may remove this row |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Root process instance the authorization was created for, so cleanup can remove it with the instance |

**Unique constraints:** `ACT_UNIQ_AUTH_USER` (TYPE_, USER_ID_, RESOURCE_TYPE_, RESOURCE_ID_), `ACT_UNIQ_AUTH_GROUP` (TYPE_, GROUP_ID_, RESOURCE_TYPE_, RESOURCE_ID_)

**Indexes:** `ACT_IDX_AUTH_GROUP_ID` (GROUP_ID_), `ACT_IDX_AUTH_RESOURCE_ID` (RESOURCE_ID_), `ACT_IDX_AUTH_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_AUTH_RM_TIME` (REMOVAL_TIME_)

#### Table-19: `ACT_RU_FILTER`

Saved Tasklist filters — persisted query definitions, not instance state. Our configuration seeds
one named `All tasks` (`cadenzaflow.bpm.filter.create`).

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Filter identifier |
| `REV_` |  | `integer` | N |  |  |  | Optimistic-locking revision |
| `RESOURCE_TYPE_` |  | `varchar(255)` | N |  |  |  | What the filter queries; currently only `Task` |
| `NAME_` |  | `varchar(255)` | N |  |  |  | Filter name shown in Tasklist |
| `OWNER_` |  | `varchar(255)` | Y |  |  |  | User who owns the filter |
| `QUERY_` |  | `TEXT` | N |  |  |  | The serialized query (JSON) the filter runs |
| `PROPERTIES_` |  | `TEXT` | Y |  |  |  | Serialized display properties — colour, description, visible columns |

#### Table-20: `ACT_RU_METER_LOG`

Engine telemetry counters (root process instances started, activity instances executed, executed
decision elements) aggregated into time buckets for licensing and load reporting.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Meter log entry identifier |
| `NAME_` |  | `varchar(64)` | N |  |  |  | Metric name, e.g. `root-process-instance-start`, `activity-instance-start`, `executed-decision-elements` |
| `REPORTER_` |  | `varchar(255)` | Y |  |  |  | Identifier of the engine node that reported the value |
| `VALUE_` |  | `bigint` | Y |  |  |  | Counter value for this bucket |
| `TIMESTAMP_` |  | `timestamp` | Y |  |  |  | Legacy bucket timestamp column |
| `MILLISECONDS_` |  | `bigint` | Y |  | `0` |  | Bucket timestamp as epoch milliseconds — the column current queries use. Default `0` |

**Indexes:** `ACT_IDX_METER_LOG_MS` (MILLISECONDS_), `ACT_IDX_METER_LOG_NAME_MS` (NAME_, MILLISECONDS_), `ACT_IDX_METER_LOG_REPORT` (NAME_, REPORTER_, MILLISECONDS_), `ACT_IDX_METER_LOG_TIME` (TIMESTAMP_), `ACT_IDX_METER_LOG` (NAME_, TIMESTAMP_)

#### Table-21: `ACT_RU_TASK_METER_LOG`

Unique-task-worker telemetry: one row per (assignee, time) observation, storing only a hash of the
assignee so distinct workers can be counted without retaining user identities.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Entry identifier |
| `ASSIGNEE_HASH_` |  | `bigint` | Y |  |  |  | Hash of the assignee's user id (`TaskMeterLogEntity.createHashAsLong`). Deliberately one-way — it supports distinct counting, not identification |
| `TIMESTAMP_` |  | `timestamp` | Y |  |  |  | When the assignment was observed |

**Indexes:** `ACT_IDX_TASK_METER_LOG_TIME` (TIMESTAMP_)

#### Table-22: `ACT_RU_EXT_TASK`

External tasks currently available to, or locked by, an external worker — the polling integration
point for non-Java workers.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | External task identifier |
| `REV_` |  | `integer` | N |  |  |  | Optimistic-locking revision |
| `WORKER_ID_` |  | `varchar(255)` | Y |  |  |  | Worker that currently holds the lock; `NULL` when unlocked and available for fetch |
| `TOPIC_NAME_` |  | `varchar(255)` | Y |  |  |  | Topic workers subscribe to in order to fetch this task |
| `RETRIES_` |  | `integer` | Y |  |  |  | Attempts remaining before the failure becomes an incident |
| `ERROR_MSG_` |  | `varchar(4000)` | Y |  |  |  | Truncated message of the last reported failure |
| `ERROR_DETAILS_ID_` |  | `varchar(64)` | Y |  |  | `ACT_GE_BYTEARRAY.ID_` | Blob holding the full error details reported by the worker |
| `LOCK_EXP_TIME_` |  | `timestamp` | Y |  |  |  | When the worker's lock expires and the task becomes fetchable again |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the external task was created |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_EXECUTION.ID_` | Execution waiting on this external task |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the service task |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance id of that element |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `PRIORITY_` |  | `bigint` | N |  | `0` |  | Fetch priority; higher is handed out first |
| `LAST_FAILURE_LOG_ID_` |  | `varchar(64)` | Y |  |  |  | `ACT_HI_EXT_TASK_LOG` entry for the most recent failure |

**Indexes:** `ACT_IDX_EXT_TASK_TOPIC` (TOPIC_NAME_), `ACT_IDX_EXT_TASK_TENANT_ID` (TENANT_ID_), `ACT_IDX_EXT_TASK_PRIORITY` (PRIORITY_), `ACT_IDX_EXT_TASK_ERR_DETAILS` (ERROR_DETAILS_ID_), `ACT_IDX_EXT_TASK_EXEC` (EXECUTION_ID_)

#### Table-23: `ACT_RU_BATCH`

Running batch operations — bulk instance migration, modification, deletion, restart or
set-retries — tracking seeding and completion progress.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Batch identifier |
| `REV_` |  | `integer` | N |  |  |  | Optimistic-locking revision |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Batch operation type, e.g. `instance-migration`, `instance-deletion`, `process-set-removal-time` |
| `TOTAL_JOBS_` |  | `integer` | Y |  |  |  | Total execution jobs this batch will produce |
| `JOBS_CREATED_` |  | `integer` | Y |  |  |  | How many have been created so far by the seed job |
| `JOBS_PER_SEED_` |  | `integer` | Y |  |  |  | How many execution jobs each run of the seed job creates |
| `INVOCATIONS_PER_JOB_` |  | `integer` | Y |  |  |  | How many individual items each execution job processes |
| `SEED_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_JOBDEF.ID_` | Job definition of the seed job that creates the execution jobs |
| `BATCH_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_JOBDEF.ID_` | Job definition of the execution jobs that do the work |
| `MONITOR_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_JOBDEF.ID_` | Job definition of the monitor job that detects completion and cleans up |
| `SUSPENSION_STATE_` |  | `integer` | Y |  |  |  | `1` = active, `2` = suspended |
| `CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Reference to the batch's configuration payload, stored as a batch-scoped variable |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who started the batch |
| `START_TIME_` |  | `timestamp` | Y |  |  |  | When the batch was created |
| `EXEC_START_TIME_` |  | `timestamp` | Y |  |  |  | When the first execution job actually ran |

**Indexes:** `ACT_IDX_BATCH_SEED_JOB_DEF` (SEED_JOB_DEF_ID_), `ACT_IDX_BATCH_MONITOR_JOB_DEF` (MONITOR_JOB_DEF_ID_), `ACT_IDX_BATCH_JOB_DEF` (BATCH_JOB_DEF_ID_)

#### Table-24: `ACT_RU_CASE_EXECUTION`

The live CMMN case tree — the case instance and each of its plan items. *(Created because CMMN is
enabled by default; empty unless CMMN definitions are deployed.)*

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Case execution identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Case instance this execution belongs to; equals `ID_` on the instance row |
| `SUPER_CASE_EXEC_` |  | `varchar(64)` | Y |  |  |  | Case task in a calling case that started this case instance |
| `SUPER_EXEC_` |  | `varchar(64)` | Y |  |  |  | BPMN execution that started this case instance, when launched from a process |
| `BUSINESS_KEY_` |  | `varchar(255)` | Y |  |  |  | Caller-supplied domain correlation key |
| `PARENT_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Parent case execution in the plan-item tree |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RE_CASE_DEF.ID_` | Case definition version being executed |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | CMMN plan-item id this execution represents |
| `PREV_STATE_` |  | `integer` | Y |  |  |  | Previous CMMN state code, retained to drive state-transition listeners |
| `CURRENT_STATE_` |  | `integer` | Y |  |  |  | Current CMMN state code (available, enabled, active, suspended, completed, terminated, …) |
| `REQUIRED_` |  | `boolean` | Y |  |  |  | Whether the plan item's required rule evaluated true — the parent stage cannot complete until it finishes |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_CASE_EXEC_BUSKEY` (BUSINESS_KEY_), `ACT_IDX_CASE_EXE_CASE_INST` (CASE_INST_ID_), `ACT_IDX_CASE_EXE_PARENT` (PARENT_ID_), `ACT_IDX_CASE_EXE_CASE_DEF` (CASE_DEF_ID_), `ACT_IDX_CASE_EXEC_TENANT_ID` (TENANT_ID_)

#### Table-25: `ACT_RU_CASE_SENTRY_PART`

The individual on-parts and if-parts of a CMMN sentry, tracking which conditions of an entry/exit
criterion have already been satisfied.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Sentry part identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Owning case instance |
| `CASE_EXEC_ID_` |  | `varchar(64)` | Y |  |  | `ACT_RU_CASE_EXECUTION.ID_` | Case execution the sentry guards |
| `SENTRY_ID_` |  | `varchar(255)` | Y |  |  |  | CMMN sentry id this part belongs to |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Part type: `ifPart`, `onPart` (plan-item), or variable-on-part |
| `SOURCE_CASE_EXEC_ID_` |  | `varchar(64)` | Y |  |  |  | Case execution whose state change this on-part is watching |
| `STANDARD_EVENT_` |  | `varchar(255)` | Y |  |  |  | CMMN standard event awaited on the source, e.g. `complete`, `occur`, `terminate` |
| `SOURCE_` |  | `varchar(255)` | Y |  |  |  | Plan-item id of the source element, as written in the model |
| `VARIABLE_EVENT_` |  | `varchar(255)` | Y |  |  |  | Variable event awaited, for variable-on-parts: `create`, `update`, `delete` |
| `VARIABLE_NAME_` |  | `varchar(255)` | Y |  |  |  | Variable name watched by a variable-on-part |
| `SATISFIED_` |  | `boolean` | Y |  |  |  | Whether this part's condition has already been met; the sentry fires when all parts are satisfied |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_CASE_SENTRY_CASE_INST` (CASE_INST_ID_), `ACT_IDX_CASE_SENTRY_CASE_EXEC` (CASE_EXEC_ID_)

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

#### Table-26: `ACT_HI_PROCINST`

One row per process instance, running or finished — the top-level audit record of a process run.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | History record identifier (equal to the process instance id) |
| `PROC_INST_ID_` |  | `varchar(64)` | N | Y |  |  | The process instance id. **UNIQUE** |
| `BUSINESS_KEY_` |  | `varchar(255)` | Y |  |  |  | Caller-supplied domain correlation key |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | N |  |  |  | Process definition version the instance ran on |
| `START_TIME_` |  | `timestamp` | N |  |  |  | When the instance started |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When the instance ended; `NULL` while running |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this instance's history; computed from `END_TIME_` + TTL |
| `DURATION_` |  | `bigint` | Y |  |  |  | Run duration in milliseconds |
| `START_USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who started the instance, when started through an authenticated API call |
| `START_ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the start event that fired |
| `END_ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the end event reached |
| `SUPER_PROCESS_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Calling process instance, when started by a call activity |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `SUPER_CASE_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Calling CMMN case instance, when started from a case |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Case instance the process belongs to |
| `DELETE_REASON_` |  | `varchar(4000)` | Y |  |  |  | Reason recorded when the instance was deleted rather than completed |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `STATE_` |  | `varchar(255)` | Y |  |  |  | Final or current state: `ACTIVE`, `SUSPENDED`, `COMPLETED`, `EXTERNALLY_TERMINATED`, `INTERNALLY_TERMINATED` |
| `RESTARTED_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | If this instance was created by restarting a finished one, the id of the instance it was restarted from |

**Unique constraints:** inline (PROC_INST_ID_)

**Indexes:** `ACT_IDX_HI_PRO_INST_END` (END_TIME_), `ACT_IDX_HI_PRO_I_BUSKEY` (BUSINESS_KEY_), `ACT_IDX_HI_PRO_INST_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_PRO_INST_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_PRO_INST_PROC_TIME` (START_TIME_, END_TIME_), `ACT_IDX_HI_PI_PDEFID_END_TIME` (PROC_DEF_ID_, END_TIME_), `ACT_IDX_HI_PRO_INST_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_PRO_INST_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_PRO_RST_PRO_INST_ID` (RESTARTED_PROC_INST_ID_)

#### Table-27: `ACT_HI_ACTINST`

One row per activity instance — every BPMN element a process instance entered, and how long it took.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Activity instance identifier |
| `PARENT_ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Enclosing activity instance (sub-process, multi-instance body) |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | N |  |  |  | Process definition version |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | N |  |  |  | Owning process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | N |  |  |  | Execution that ran this activity |
| `ACT_ID_` |  | `varchar(255)` | N |  |  |  | BPMN element id |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task created by this activity, for user tasks |
| `CALL_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance started by this activity, for call activities |
| `CALL_CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Case instance started by this activity, for case tasks |
| `ACT_NAME_` |  | `varchar(255)` | Y |  |  |  | BPMN element name |
| `ACT_TYPE_` |  | `varchar(255)` | N |  |  |  | BPMN element type, e.g. `userTask`, `serviceTask`, `exclusiveGateway`, `startEvent` |
| `ASSIGNEE_` |  | `varchar(255)` | Y |  |  |  | Assignee at the time, for user-task activities |
| `START_TIME_` |  | `timestamp` | N |  |  |  | When the activity was entered |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When it was left; `NULL` while still active |
| `DURATION_` |  | `bigint` | Y |  |  |  | Time spent in the activity, in milliseconds |
| `ACT_INST_STATE_` |  | `integer` | Y |  |  |  | Terminal state code of the activity instance — distinguishes normally completed from cancelled/terminated |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter that orders events from the same execution |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_ACTINST_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_ACT_INST_START_END` (START_TIME_, END_TIME_), `ACT_IDX_HI_ACT_INST_END` (END_TIME_), `ACT_IDX_HI_ACT_INST_PROCINST` (PROC_INST_ID_, ACT_ID_), `ACT_IDX_HI_ACT_INST_COMP` (EXECUTION_ID_, ACT_ID_, END_TIME_, ID_), `ACT_IDX_HI_ACT_INST_STATS` (PROC_DEF_ID_, PROC_INST_ID_, ACT_ID_, END_TIME_, ACT_INST_STATE_), `ACT_IDX_HI_ACT_INST_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_ACT_INST_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_AI_PDEFID_END_TIME` (PROC_DEF_ID_, END_TIME_), `ACT_IDX_HI_ACT_INST_RM_TIME` (REMOVAL_TIME_)

#### Table-28: `ACT_HI_TASKINST`

One row per user task ever created, with its final assignee, timings and outcome.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Task identifier (same value as the runtime task's `ID_`) |
| `TASK_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the user task in the model |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution that created the task |
| `CASE_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | CMMN case definition key, for case tasks |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case definition version |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case instance |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case execution |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance that created the task |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Task name |
| `PARENT_TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Parent task, for sub-tasks |
| `DESCRIPTION_` |  | `varchar(4000)` | Y |  |  |  | Task description |
| `OWNER_` |  | `varchar(255)` | Y |  |  |  | Task owner at the end |
| `ASSIGNEE_` |  | `varchar(255)` | Y |  |  |  | Assignee at the end |
| `START_TIME_` |  | `timestamp` | N |  |  |  | When the task was created |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When it was completed or deleted; `NULL` while open |
| `DURATION_` |  | `bigint` | Y |  |  |  | Lifetime in milliseconds |
| `DELETE_REASON_` |  | `varchar(4000)` | Y |  |  |  | `completed`, or the reason the task was deleted |
| `PRIORITY_` |  | `integer` | Y |  |  |  | Priority at the end |
| `DUE_DATE_` |  | `timestamp` | Y |  |  |  | Due date at the end |
| `FOLLOW_UP_DATE_` |  | `timestamp` | Y |  |  |  | Follow-up date at the end |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `TASK_STATE_` |  | `varchar(64)` | Y |  |  |  | Final lifecycle state (`Completed`, `Deleted`, …) |

**Indexes:** `ACT_IDX_HI_TASKINST_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_TASK_INST_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_TASK_INST_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_TASKINST_PROCINST` (PROC_INST_ID_), `ACT_IDX_HI_TASKINSTID_PROCINST` (ID_, PROC_INST_ID_), `ACT_IDX_HI_TASK_INST_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_TASK_INST_START` (START_TIME_), `ACT_IDX_HI_TASK_INST_END` (END_TIME_)

#### Table-29: `ACT_HI_VARINST`

One row per variable instance, holding its **latest** value — the history-side mirror of
`ACT_RU_VARIABLE` that survives instance completion.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Variable instance identifier |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution the variable was local to |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance in scope when the variable was created |
| `CASE_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | CMMN case definition key |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case definition version |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case instance |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case execution |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Owning task, for task-local variables |
| `NAME_` |  | `varchar(255)` | N |  |  |  | Variable name |
| `VAR_TYPE_` |  | `varchar(100)` | Y |  |  |  | Value serializer name; determines which value column is populated |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the variable was first set |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision — present here because this row *is* updated in place on each variable change |
| `BYTEARRAY_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the serialized value, when not inline-able |
| `DOUBLE_` |  | `double precision` | Y |  |  |  | Value when floating point |
| `LONG_` |  | `bigint` | Y |  |  |  | Value when integral, boolean or date |
| `TEXT_` |  | `varchar(4000)` | Y |  |  |  | Value when short text; for object variables, the Java type name |
| `TEXT2_` |  | `varchar(4000)` | Y |  |  |  | Secondary text slot — serialization data format / value-info metadata |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `STATE_` |  | `varchar(20)` | Y |  |  |  | Variable state: `CREATED` or `DELETED` |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_VARINST_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_PROCVAR_PROC_INST` (PROC_INST_ID_), `ACT_IDX_HI_PROCVAR_NAME_TYPE` (NAME_, VAR_TYPE_), `ACT_IDX_HI_CASEVAR_CASE_INST` (CASE_INST_ID_), `ACT_IDX_HI_VAR_INST_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_VAR_INST_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_VARINST_BYTEAR` (BYTEARRAY_ID_), `ACT_IDX_HI_VARINST_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_VAR_PI_NAME_TYPE` (PROC_INST_ID_, NAME_, VAR_TYPE_), `ACT_IDX_HI_VARINST_NAME` (NAME_), `ACT_IDX_HI_VARINST_ACT_INST_ID` (ACT_INST_ID_)

#### Table-30: `ACT_HI_DETAIL`

One row per *individual variable update* and per form-property submission — the full change log
behind `ACT_HI_VARINST`. Only written at history level `full`, which is this service's level; this
is typically the largest table in the schema.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Detail entry identifier |
| `TYPE_` |  | `varchar(255)` | N |  |  |  | Detail kind: `VariableUpdate` or `FormProperty` |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution in which the update happened |
| `CASE_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | CMMN case definition key |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case definition version |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case instance |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Owning case execution |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task in scope, for task-local updates and form submissions |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance in scope at the time of the update |
| `VAR_INST_ID_` |  | `varchar(64)` | Y |  |  |  | The `ACT_HI_VARINST` row this update belongs to — the chain that turns detail rows into a variable's value history |
| `NAME_` |  | `varchar(255)` | N |  |  |  | Variable or form-property name |
| `VAR_TYPE_` |  | `varchar(64)` | Y |  |  |  | Value serializer name for this revision of the value |
| `REV_` |  | `integer` | Y |  |  |  | Revision number of the variable *at this update* — a sequence within the variable's history, not an optimistic lock on this (append-only) row |
| `TIME_` |  | `timestamp` | N |  |  |  | When the update occurred |
| `BYTEARRAY_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the serialized value of this revision |
| `DOUBLE_` |  | `double precision` | Y |  |  |  | Value when floating point |
| `LONG_` |  | `bigint` | Y |  |  |  | Value when integral, boolean or date |
| `TEXT_` |  | `varchar(4000)` | Y |  |  |  | Value when short text; for object variables, the Java type name |
| `TEXT2_` |  | `varchar(4000)` | Y |  |  |  | Secondary text slot — serialization data format / value-info metadata |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter that orders updates written within the same millisecond |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `OPERATION_ID_` |  | `varchar(64)` | Y |  |  |  | Correlates this update with the `ACT_HI_OP_LOG` operation that caused it, when a user changed the variable via the API |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `INITIAL_` |  | `boolean` | Y |  |  |  | True when this row records the variable's initial value, set at process instance start (`HistoricVariableUpdateEventEntity.isInitial`). Lets queries retrieve start variables without scanning the whole change log |

**Indexes:** `ACT_IDX_HI_DETAIL_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_DETAIL_PROC_INST` (PROC_INST_ID_), `ACT_IDX_HI_DETAIL_ACT_INST` (ACT_INST_ID_), `ACT_IDX_HI_DETAIL_CASE_INST` (CASE_INST_ID_), `ACT_IDX_HI_DETAIL_CASE_EXEC` (CASE_EXECUTION_ID_), `ACT_IDX_HI_DETAIL_TIME` (TIME_), `ACT_IDX_HI_DETAIL_NAME` (NAME_), `ACT_IDX_HI_DETAIL_TASK_ID` (TASK_ID_), `ACT_IDX_HI_DETAIL_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_DETAIL_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_DETAIL_BYTEAR` (BYTEARRAY_ID_), `ACT_IDX_HI_DETAIL_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_DETAIL_TASK_BYTEAR` (BYTEARRAY_ID_, TASK_ID_), `ACT_IDX_HI_DETAIL_VAR_INST_ID` (VAR_INST_ID_)

#### Table-31: `ACT_HI_IDENTITYLINK`

Audit trail of who was linked to a task and when — every candidate added or removed, every claim
and assignment.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | History entry identifier |
| `TIMESTAMP_` |  | `timestamp` | N |  |  |  | When the link was added or removed |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Link type: `candidate`, `assignee`, `owner` |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | User side of the link |
| `GROUP_ID_` |  | `varchar(255)` | Y |  |  |  | Group side of the link |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task the link applied to |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version, for definition-level links |
| `OPERATION_TYPE_` |  | `varchar(64)` | Y |  |  |  | Whether the link was `add`ed or `delete`d |
| `ASSIGNER_ID_` |  | `varchar(64)` | Y |  |  |  | User who performed the assignment |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_IDENT_LNK_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_IDENT_LNK_USER` (USER_ID_), `ACT_IDX_HI_IDENT_LNK_GROUP` (GROUP_ID_), `ACT_IDX_HI_IDENT_LNK_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_IDENT_LNK_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_IDENT_LINK_TASK` (TASK_ID_), `ACT_IDX_HI_IDENT_LINK_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_IDENT_LNK_TIMESTAMP` (TIMESTAMP_)

#### Table-32: `ACT_HI_COMMENT`

User comments and engine-generated event notes attached to a task or process instance.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Comment identifier |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | `comment` for a user comment, `event` for an engine-generated lifecycle note |
| `TIME_` |  | `timestamp` | N |  |  |  | When the comment was made |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | Author |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task the comment is attached to |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance the comment is attached to |
| `ACTION_` |  | `varchar(255)` | Y |  |  |  | For `event` rows, the action recorded (e.g. `AddUserLink`, `AddAttachment`) |
| `MESSAGE_` |  | `varchar(4000)` | Y |  |  |  | Truncated comment text, for display in lists |
| `FULL_MSG_` |  | `bytea` | Y |  |  |  | The full comment text, stored inline as bytes |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `REV_` |  | `integer` | N |  | `1` |  | Optimistic-locking revision — comments are editable, so this row can be updated |

**Indexes:** `ACT_IDX_HI_COMMENT_TASK` (TASK_ID_), `ACT_IDX_HI_COMMENT_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_COMMENT_PROCINST` (PROC_INST_ID_), `ACT_IDX_HI_COMMENT_RM_TIME` (REMOVAL_TIME_)

#### Table-33: `ACT_HI_ATTACHMENT`

Files and URLs attached to a task or process instance.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Attachment identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision — attachments are editable |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who attached it |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Attachment name |
| `DESCRIPTION_` |  | `varchar(4000)` | Y |  |  |  | Attachment description |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Caller-supplied content type / classification |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task the attachment belongs to |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance the attachment belongs to |
| `URL_` |  | `varchar(4000)` | Y |  |  |  | External URL, for link-type attachments |
| `CONTENT_ID_` |  | `varchar(64)` | Y |  |  |  | Blob in `ACT_GE_BYTEARRAY` holding the file bytes, for content-type attachments |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the attachment was created |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_ATTACHMENT_CONTENT` (CONTENT_ID_), `ACT_IDX_HI_ATTACHMENT_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_ATTACHMENT_PROCINST` (PROC_INST_ID_), `ACT_IDX_HI_ATTACHMENT_TASK` (TASK_ID_), `ACT_IDX_HI_ATTACHMENT_RM_TIME` (REMOVAL_TIME_)

#### Table-34: `ACT_HI_OP_LOG`

The user operation log: every administrative change a human made through the API or webapps —
suspending an instance, reassigning a task, setting retries, deleting a deployment. One row per
changed property, correlated by `OPERATION_ID_`. Written only at history level `full`.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Log entry identifier |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment affected, for deployment operations |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version affected |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key affected |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance affected |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution affected |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case definition affected |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case instance affected |
| `CASE_EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case execution affected |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task affected |
| `JOB_ID_` |  | `varchar(64)` | Y |  |  |  | Job affected |
| `JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition affected |
| `BATCH_ID_` |  | `varchar(64)` | Y |  |  |  | Batch affected |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | Authenticated user who performed the operation |
| `TIMESTAMP_` |  | `timestamp` | N |  |  |  | When the operation was performed |
| `OPERATION_TYPE_` |  | `varchar(64)` | Y |  |  |  | Operation performed, e.g. `Claim`, `Assign`, `Suspend`, `SetJobRetries`, `Delete` |
| `OPERATION_ID_` |  | `varchar(64)` | Y |  |  |  | Groups all property rows written by one logical operation |
| `ENTITY_TYPE_` |  | `varchar(30)` | Y |  |  |  | Entity kind acted on: `Task`, `ProcessInstance`, `Job`, `Attachment`, … |
| `PROPERTY_` |  | `varchar(64)` | Y |  |  |  | Name of the property that changed |
| `ORG_VALUE_` |  | `varchar(4000)` | Y |  |  |  | Value before the change |
| `NEW_VALUE_` |  | `varchar(4000)` | Y |  |  |  | Value after the change |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `CATEGORY_` |  | `varchar(64)` | Y |  |  |  | Operation category, e.g. `TaskWorker`, `Operator`, `Admin` — lets authorization filter the log by who may see what |
| `EXTERNAL_TASK_ID_` |  | `varchar(64)` | Y |  |  |  | External task affected |
| `ANNOTATION_` |  | `varchar(4000)` | Y |  |  |  | Free-text operator annotation attached to the operation |

**Indexes:** `ACT_IDX_HI_OP_LOG_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_OP_LOG_PROCINST` (PROC_INST_ID_), `ACT_IDX_HI_OP_LOG_PROCDEF` (PROC_DEF_ID_), `ACT_IDX_HI_OP_LOG_TASK` (TASK_ID_), `ACT_IDX_HI_OP_LOG_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_OP_LOG_TIMESTAMP` (TIMESTAMP_), `ACT_IDX_HI_OP_LOG_USER_ID` (USER_ID_), `ACT_IDX_HI_OP_LOG_OP_TYPE` (OPERATION_TYPE_), `ACT_IDX_HI_OP_LOG_ENTITY_TYPE` (ENTITY_TYPE_)

#### Table-35: `ACT_HI_INCIDENT`

One row per incident ever raised, including resolved and deleted ones — the audit counterpart of
`ACT_RU_INCIDENT`.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Incident identifier (same as the runtime incident) |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution the incident was attached to |
| `CREATE_TIME_` |  | `timestamp` | N |  |  |  | When the incident was raised |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When it was resolved or deleted; `NULL` while open |
| `INCIDENT_MSG_` |  | `varchar(4000)` | Y |  |  |  | Failure message |
| `INCIDENT_TYPE_` |  | `varchar(255)` | N |  |  |  | Incident type, e.g. `failedJob`, `failedExternalTask` |
| `ACTIVITY_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id the incident was attached to |
| `FAILED_ACTIVITY_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id where the failure actually occurred |
| `CAUSE_INCIDENT_ID_` |  | `varchar(64)` | Y |  |  |  | Incident that caused this one |
| `ROOT_CAUSE_INCIDENT_ID_` |  | `varchar(64)` | Y |  |  |  | Originating incident at the bottom of the chain |
| `CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Reference to the failing entity (job id, external task id) |
| `HISTORY_CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Reference to the corresponding history record of the failing entity — e.g. the `ACT_HI_JOB_LOG` id — since the runtime row referenced by `CONFIGURATION_` may be gone |
| `INCIDENT_STATE_` |  | `integer` | Y |  |  |  | Terminal state: `0` = open, `1` = deleted, `2` = resolved |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition of the failing job |
| `ANNOTATION_` |  | `varchar(4000)` | Y |  |  |  | Free-text operator annotation |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_INCIDENT_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_INCIDENT_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_IDX_HI_INCIDENT_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_INCIDENT_PROCINST` (PROC_INST_ID_), `ACT_IDX_HI_INCIDENT_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_INCIDENT_CREATE_TIME` (CREATE_TIME_), `ACT_IDX_HI_INCIDENT_END_TIME` (END_TIME_)

#### Table-36: `ACT_HI_JOB_LOG`

One row per job lifecycle event — created, executed successfully, failed, deleted — giving the full
retry history of every job.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Log entry identifier |
| `TIMESTAMP_` |  | `timestamp` | N |  |  |  | When the event occurred |
| `JOB_ID_` |  | `varchar(64)` | N |  |  |  | The job this event concerns |
| `JOB_DUEDATE_` |  | `timestamp` | Y |  |  |  | The job's due date at the time of the event |
| `JOB_RETRIES_` |  | `integer` | Y |  |  |  | Retries remaining at the time of the event |
| `JOB_PRIORITY_` |  | `bigint` | N |  | `0` |  | The job's priority at the time of the event |
| `JOB_EXCEPTION_MSG_` |  | `varchar(4000)` | Y |  |  |  | Truncated failure message, for failure events |
| `JOB_EXCEPTION_STACK_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the full stack trace of the failure |
| `JOB_STATE_` |  | `integer` | Y |  |  |  | Event/state code: created, failed, successful, deleted |
| `JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition the job came from |
| `JOB_DEF_TYPE_` |  | `varchar(255)` | Y |  |  |  | Job definition type, e.g. `timer`, `async-continuation` |
| `JOB_DEF_CONFIGURATION_` |  | `varchar(255)` | Y |  |  |  | Job definition configuration at the time |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id the job belongs to |
| `FAILED_ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id where the failure occurred |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution the job belonged to |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROCESS_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `PROCESS_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `PROCESS_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `DEPLOYMENT_ID_` |  | `varchar(64)` | Y |  |  |  | Deployment the job belonged to |
| `SEQUENCE_COUNTER_` |  | `bigint` | Y |  |  |  | Monotonic counter ordering events of the same job |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `HOSTNAME_` |  | `varchar(255)` | Y |  |  |  | Host of the engine node that produced the event — useful for pinning a failure to a pod |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `BATCH_ID_` |  | `varchar(64)` | Y |  |  |  | Batch the job belonged to |

**Indexes:** `ACT_IDX_HI_JOB_LOG_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_JOB_LOG_PROCINST` (PROCESS_INSTANCE_ID_), `ACT_IDX_HI_JOB_LOG_PROCDEF` (PROCESS_DEF_ID_), `ACT_IDX_HI_JOB_LOG_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_JOB_LOG_JOB_DEF_ID` (JOB_DEF_ID_), `ACT_IDX_HI_JOB_LOG_PROC_DEF_KEY` (PROCESS_DEF_KEY_), `ACT_IDX_HI_JOB_LOG_EX_STACK` (JOB_EXCEPTION_STACK_ID_), `ACT_IDX_HI_JOB_LOG_RM_TIME` (REMOVAL_TIME_), `ACT_IDX_HI_JOB_LOG_JOB_CONF` (JOB_DEF_CONFIGURATION_)

#### Table-37: `ACT_HI_BATCH`

One row per batch operation, retained after the batch finishes and its `ACT_RU_BATCH` row is
deleted. Retention is governed by `batchOperationHistoryTimeToLive` (`P30D` here).

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Batch identifier |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Batch operation type |
| `TOTAL_JOBS_` |  | `integer` | Y |  |  |  | Total execution jobs the batch produced |
| `JOBS_PER_SEED_` |  | `integer` | Y |  |  |  | Execution jobs created per seed job run |
| `INVOCATIONS_PER_JOB_` |  | `integer` | Y |  |  |  | Items processed per execution job |
| `SEED_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition of the seed job |
| `MONITOR_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition of the monitor job |
| `BATCH_JOB_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Job definition of the execution jobs |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who started the batch |
| `START_TIME_` |  | `timestamp` | N |  |  |  | When the batch was created |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When the batch completed |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `EXEC_START_TIME_` |  | `timestamp` | Y |  |  |  | When the first execution job ran |

**Indexes:** `ACT_HI_BAT_RM_TIME` (REMOVAL_TIME_)

#### Table-38: `ACT_HI_EXT_TASK_LOG`

One row per external-task lifecycle event — created, fetched/locked, completed, failed, deleted.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Log entry identifier |
| `TIMESTAMP_` |  | `timestamp` | N |  |  |  | When the event occurred |
| `EXT_TASK_ID_` |  | `varchar(64)` | N |  |  |  | The external task this event concerns |
| `RETRIES_` |  | `integer` | Y |  |  |  | Retries remaining at the time of the event |
| `TOPIC_NAME_` |  | `varchar(255)` | Y |  |  |  | Topic the external task was published on |
| `WORKER_ID_` |  | `varchar(255)` | Y |  |  |  | Worker involved in the event |
| `PRIORITY_` |  | `bigint` | N |  | `0` |  | Priority at the time of the event |
| `ERROR_MSG_` |  | `varchar(4000)` | Y |  |  |  | Truncated failure message reported by the worker |
| `ERROR_DETAILS_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the full error details |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the service task |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance id of that element |
| `EXECUTION_ID_` |  | `varchar(64)` | Y |  |  |  | Execution the external task belonged to |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Owning process instance |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most instance of the call hierarchy |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key, denormalised |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `STATE_` |  | `integer` | Y |  |  |  | Event/state code: created, failed, successful, deleted |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_HI_EXT_TASK_LOG_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_HI_EXT_TASK_LOG_PROCINST` (PROC_INST_ID_), `ACT_HI_EXT_TASK_LOG_PROCDEF` (PROC_DEF_ID_), `ACT_HI_EXT_TASK_LOG_PROC_DEF_KEY` (PROC_DEF_KEY_), `ACT_HI_EXT_TASK_LOG_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_EXTTASKLOG_ERRORDET` (ERROR_DETAILS_ID_), `ACT_HI_EXT_TASK_LOG_RM_TIME` (REMOVAL_TIME_)

#### Table-39: `ACT_HI_CASEINST`

One row per CMMN case instance, running or closed. *(Empty unless CMMN definitions are deployed.)*

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | History record identifier (equal to the case instance id) |
| `CASE_INST_ID_` |  | `varchar(64)` | N | Y |  |  | The case instance id. **UNIQUE** |
| `BUSINESS_KEY_` |  | `varchar(255)` | Y |  |  |  | Caller-supplied domain correlation key |
| `CASE_DEF_ID_` |  | `varchar(64)` | N |  |  |  | Case definition version the instance ran on |
| `CREATE_TIME_` |  | `timestamp` | N |  |  |  | When the case instance was created |
| `CLOSE_TIME_` |  | `timestamp` | Y |  |  |  | When it was closed; `NULL` while open |
| `DURATION_` |  | `bigint` | Y |  |  |  | Lifetime in milliseconds |
| `STATE_` |  | `integer` | Y |  |  |  | Final CMMN case state code (active, completed, terminated, closed, …) |
| `CREATE_USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who created the case instance |
| `SUPER_CASE_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Calling case instance, when started from a case task |
| `SUPER_PROCESS_INSTANCE_ID_` |  | `varchar(64)` | Y |  |  |  | Calling process instance, when started from a BPMN process |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Unique constraints:** inline (CASE_INST_ID_)

**Indexes:** `ACT_IDX_HI_CAS_I_CLOSE` (CLOSE_TIME_), `ACT_IDX_HI_CAS_I_BUSKEY` (BUSINESS_KEY_), `ACT_IDX_HI_CAS_I_TENANT_ID` (TENANT_ID_)

#### Table-40: `ACT_HI_CASEACTINST`

One row per CMMN plan-item (case activity) instance. *(Empty unless CMMN definitions are deployed.)*

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Case activity instance identifier |
| `PARENT_ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Enclosing plan-item instance (the containing stage) |
| `CASE_DEF_ID_` |  | `varchar(64)` | N |  |  |  | Case definition version |
| `CASE_INST_ID_` |  | `varchar(64)` | N |  |  |  | Owning case instance |
| `CASE_ACT_ID_` |  | `varchar(255)` | N |  |  |  | CMMN plan-item id |
| `TASK_ID_` |  | `varchar(64)` | Y |  |  |  | Task created by this plan item, for human tasks |
| `CALL_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance started by this plan item, for process tasks |
| `CALL_CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Case instance started by this plan item, for case tasks |
| `CASE_ACT_NAME_` |  | `varchar(255)` | Y |  |  |  | Plan-item name |
| `CASE_ACT_TYPE_` |  | `varchar(255)` | Y |  |  |  | Plan-item type, e.g. `humanTask`, `stage`, `milestone`, `processTask` |
| `CREATE_TIME_` |  | `timestamp` | N |  |  |  | When the plan-item instance was created |
| `END_TIME_` |  | `timestamp` | Y |  |  |  | When it ended; `NULL` while active |
| `DURATION_` |  | `bigint` | Y |  |  |  | Lifetime in milliseconds |
| `STATE_` |  | `integer` | Y |  |  |  | Final CMMN plan-item state code |
| `REQUIRED_` |  | `boolean` | Y |  |  |  | Whether the required rule evaluated true for this plan item |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_HI_CAS_A_I_CREATE` (CREATE_TIME_), `ACT_IDX_HI_CAS_A_I_END` (END_TIME_), `ACT_IDX_HI_CAS_A_I_COMP` (CASE_ACT_ID_, END_TIME_, ID_), `ACT_IDX_HI_CAS_A_I_TENANT_ID` (TENANT_ID_)

#### Table-41: `ACT_HI_DECINST`

One row per DMN decision evaluation — every time a business rule task or the decision API evaluated
a decision.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Decision instance identifier |
| `DEC_DEF_ID_` |  | `varchar(64)` | N |  |  |  | Decision definition version evaluated |
| `DEC_DEF_KEY_` |  | `varchar(255)` | N |  |  |  | Decision definition key, denormalised |
| `DEC_DEF_NAME_` |  | `varchar(255)` | Y |  |  |  | Decision name at evaluation time |
| `PROC_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | Process definition key of the calling process, if any |
| `PROC_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | Process definition version of the caller |
| `PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Process instance that triggered the evaluation |
| `CASE_DEF_KEY_` |  | `varchar(255)` | Y |  |  |  | CMMN case definition key of the caller, if any |
| `CASE_DEF_ID_` |  | `varchar(64)` | Y |  |  |  | CMMN case definition version of the caller |
| `CASE_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Case instance that triggered the evaluation |
| `ACT_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Activity instance of the business rule task |
| `ACT_ID_` |  | `varchar(255)` | Y |  |  |  | BPMN element id of the business rule task |
| `EVAL_TIME_` |  | `timestamp` | N |  |  |  | When the decision was evaluated |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |
| `COLLECT_VALUE_` |  | `double precision` | Y |  |  |  | Aggregated result when the decision table's hit policy is COLLECT with an aggregator (SUM/MIN/MAX/COUNT) |
| `USER_ID_` |  | `varchar(255)` | Y |  |  |  | User who triggered a standalone evaluation via the API |
| `ROOT_DEC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | The top-level decision evaluation, when this one was reached through a required-decision chain in a DRD |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most process instance of the call hierarchy |
| `DEC_REQ_ID_` |  | `varchar(64)` | Y |  |  |  | Decision-requirements definition the decision belongs to |
| `DEC_REQ_KEY_` |  | `varchar(255)` | Y |  |  |  | Key of that decision-requirements definition |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |

**Indexes:** `ACT_IDX_HI_DEC_INST_ID` (DEC_DEF_ID_), `ACT_IDX_HI_DEC_INST_KEY` (DEC_DEF_KEY_), `ACT_IDX_HI_DEC_INST_PI` (PROC_INST_ID_), `ACT_IDX_HI_DEC_INST_CI` (CASE_INST_ID_), `ACT_IDX_HI_DEC_INST_ACT` (ACT_ID_), `ACT_IDX_HI_DEC_INST_ACT_INST` (ACT_INST_ID_), `ACT_IDX_HI_DEC_INST_TIME` (EVAL_TIME_), `ACT_IDX_HI_DEC_INST_TENANT_ID` (TENANT_ID_), `ACT_IDX_HI_DEC_INST_ROOT_ID` (ROOT_DEC_INST_ID_), `ACT_IDX_HI_DEC_INST_REQ_ID` (DEC_REQ_ID_), `ACT_IDX_HI_DEC_INST_REQ_KEY` (DEC_REQ_KEY_), `ACT_IDX_HI_DEC_INST_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_DEC_INST_RM_TIME` (REMOVAL_TIME_)

#### Table-42: `ACT_HI_DEC_IN`

The input values a decision evaluation was given — one row per input clause of one evaluation.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Input entry identifier |
| `DEC_INST_ID_` |  | `varchar(64)` | N |  |  |  | The `ACT_HI_DECINST` evaluation this input belongs to |
| `CLAUSE_ID_` |  | `varchar(64)` | Y |  |  |  | DMN input clause id |
| `CLAUSE_NAME_` |  | `varchar(255)` | Y |  |  |  | DMN input clause label |
| `VAR_TYPE_` |  | `varchar(100)` | Y |  |  |  | Value serializer name; determines which value column is populated |
| `BYTEARRAY_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the serialized value, when not inline-able |
| `DOUBLE_` |  | `double precision` | Y |  |  |  | Value when floating point |
| `LONG_` |  | `bigint` | Y |  |  |  | Value when integral, boolean or date |
| `TEXT_` |  | `varchar(4000)` | Y |  |  |  | Value when short text; for object values, the Java type name |
| `TEXT2_` |  | `varchar(4000)` | Y |  |  |  | Secondary text slot — serialization data format / value-info metadata |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the input was recorded |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most process instance of the call hierarchy |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_DEC_IN_INST` (DEC_INST_ID_), `ACT_IDX_HI_DEC_IN_CLAUSE` (DEC_INST_ID_, CLAUSE_ID_), `ACT_IDX_HI_DEC_IN_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_DEC_IN_RM_TIME` (REMOVAL_TIME_)

#### Table-43: `ACT_HI_DEC_OUT`

The output values a decision evaluation produced — one row per output clause per matched rule.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Output entry identifier |
| `DEC_INST_ID_` |  | `varchar(64)` | N |  |  |  | The `ACT_HI_DECINST` evaluation this output belongs to |
| `CLAUSE_ID_` |  | `varchar(64)` | Y |  |  |  | DMN output clause id |
| `CLAUSE_NAME_` |  | `varchar(255)` | Y |  |  |  | DMN output clause label |
| `RULE_ID_` |  | `varchar(64)` | Y |  |  |  | Id of the decision-table rule that produced this output |
| `RULE_ORDER_` |  | `integer` | Y |  |  |  | Position of that rule in the table — orders the outputs when several rules matched |
| `VAR_NAME_` |  | `varchar(255)` | Y |  |  |  | Output variable name the value is assigned to |
| `VAR_TYPE_` |  | `varchar(100)` | Y |  |  |  | Value serializer name; determines which value column is populated |
| `BYTEARRAY_ID_` |  | `varchar(64)` | Y |  |  |  | Blob holding the serialized value, when not inline-able |
| `DOUBLE_` |  | `double precision` | Y |  |  |  | Value when floating point |
| `LONG_` |  | `bigint` | Y |  |  |  | Value when integral, boolean or date |
| `TEXT_` |  | `varchar(4000)` | Y |  |  |  | Value when short text; for object values, the Java type name |
| `TEXT2_` |  | `varchar(4000)` | Y |  |  |  | Secondary text slot — serialization data format / value-info metadata |
| `TENANT_ID_` |  | `varchar(64)` | Y |  |  |  | Tenant discriminator |
| `CREATE_TIME_` |  | `timestamp` | Y |  |  |  | When the output was recorded |
| `ROOT_PROC_INST_ID_` |  | `varchar(64)` | Y |  |  |  | Top-most process instance of the call hierarchy |
| `REMOVAL_TIME_` |  | `timestamp` | Y |  |  |  | Earliest time history cleanup may delete this row |

**Indexes:** `ACT_IDX_HI_DEC_OUT_INST` (DEC_INST_ID_), `ACT_IDX_HI_DEC_OUT_RULE` (RULE_ORDER_, CLAUSE_ID_), `ACT_IDX_HI_DEC_OUT_ROOT_PI` (ROOT_PROC_INST_ID_), `ACT_IDX_HI_DEC_OUT_RM_TIME` (REMOVAL_TIME_)

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

#### Table-44: `ACT_ID_GROUP`

Engine-local user groups.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Group identifier — the value BPMN candidate-group expressions match against |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable group name |
| `TYPE_` |  | `varchar(255)` | Y |  |  |  | Group classification, e.g. `WORKFLOW` or `SECURITY-ROLE` |

#### Table-45: `ACT_ID_MEMBERSHIP`

The user-to-group join table.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `USER_ID_` | Y | `varchar(64)` | N |  |  | `ACT_ID_USER.ID_` | Member user. (composite) |
| `GROUP_ID_` | Y | `varchar(64)` | N |  |  | `ACT_ID_GROUP.ID_` | Group joined. (composite) |

**Indexes:** `ACT_IDX_MEMB_GROUP` (GROUP_ID_), `ACT_IDX_MEMB_USER` (USER_ID_)

#### Table-46: `ACT_ID_USER`

Engine-local user accounts, including the hashed-password fields used by the engine's own form
login. Unused here — authentication is OIDC via Keycloak or Entra ID.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | User identifier — the value that appears as a task assignee |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `FIRST_` |  | `varchar(255)` | Y |  |  |  | Given name |
| `LAST_` |  | `varchar(255)` | Y |  |  |  | Family name |
| `EMAIL_` |  | `varchar(255)` | Y |  |  |  | Email address |
| `PWD_` |  | `varchar(255)` | Y |  |  |  | Salted password hash. Never populated in this deployment |
| `SALT_` |  | `varchar(255)` | Y |  |  |  | Per-user password salt. Never populated in this deployment |
| `LOCK_EXP_TIME_` |  | `timestamp` | Y |  |  |  | When a lockout from repeated failed logins expires |
| `ATTEMPTS_` |  | `integer` | Y |  |  |  | Consecutive failed login attempts |
| `PICTURE_ID_` |  | `varchar(64)` | Y |  |  |  | Blob in `ACT_GE_BYTEARRAY` holding the user's profile picture |

#### Table-47: `ACT_ID_INFO`

Additional per-user account information — account credentials for external systems and arbitrary
user attributes.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Info entry identifier |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `USER_ID_` |  | `varchar(64)` | Y |  |  |  | User the entry belongs to |
| `TYPE_` |  | `varchar(64)` | Y |  |  |  | Entry kind: `userAccount` for an external account, `userInfo` for a plain attribute |
| `KEY_` |  | `varchar(255)` | Y |  |  |  | Attribute key, or the external account name |
| `VALUE_` |  | `varchar(255)` | Y |  |  |  | Attribute value, or the external username |
| `PASSWORD_` |  | `bytea` | Y |  |  |  | Encrypted password of the external account |
| `PARENT_ID_` |  | `varchar(255)` | Y |  |  |  | Owning `userAccount` entry for detail rows, which is how one account row carries several key/value details. Note the width: it references `ACT_ID_INFO.ID_`, which is `varchar(64)` |

#### Table-48: `ACT_ID_TENANT`

Tenants, for engine multi-tenancy. Empty in this single-tenant deployment.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Tenant identifier — the value written into every `TENANT_ID_` column |
| `REV_` |  | `integer` | Y |  |  |  | Optimistic-locking revision |
| `NAME_` |  | `varchar(255)` | Y |  |  |  | Human-readable tenant name |

#### Table-49: `ACT_ID_TENANT_MEMBER`

Which users and groups belong to which tenant. Each row links a tenant to exactly one of a user or
a group.

| Column | isPK? | Type | nullable? | isUnique | default | Foreign Key | Notes |
|---|---|---|---|---|---|---|---|
| `ID_` | Y | `varchar(64)` | N |  |  |  | Membership identifier |
| `TENANT_ID_` |  | `varchar(64)` | N | Y |  | `ACT_ID_TENANT.ID_` | Tenant |
| `USER_ID_` |  | `varchar(64)` | Y | Y |  | `ACT_ID_USER.ID_` | Member user; mutually exclusive with `GROUP_ID_` |
| `GROUP_ID_` |  | `varchar(64)` | Y | Y |  | `ACT_ID_GROUP.ID_` | Member group; mutually exclusive with `USER_ID_` |

**Unique constraints:** `ACT_UNIQ_TENANT_MEMB_USER` (TENANT_ID_, USER_ID_), `ACT_UNIQ_TENANT_MEMB_GROUP` (TENANT_ID_, GROUP_ID_)

**Indexes:** `ACT_IDX_TENANT_MEMB` (TENANT_ID_), `ACT_IDX_TENANT_MEMB_USER` (USER_ID_), `ACT_IDX_TENANT_MEMB_GROUP` (GROUP_ID_)

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
