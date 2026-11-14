# ENGINE-JOBS-IDLE-IN-TRANSACTION-1: measurement procedure

This is the procedure for the measurement the row asks for. It is written by the engine wrapper's lane for the
lane that runs the stack and the load: the sessions of the engine's database role in `idle in transaction`, on
engine ≥ 1.5.4, at rest and under the W1 load profile, each with its statement and its age. Whoever runs it reports
the numbers. The row closes or names a code path by the rule in §5.

## 1. Background

The W1 load run of 2026-10-13 (`test-reports/2026-10-13-box-load-phase2/README.md`, F-12) counted **8–22**
`idle in transaction` sessions for the engine's user. The two flavours, `camunda` (serving) and `camunda-jobs`,
share one user. The sampler recorded only the user and the state, not the statement.

That day the stack ran engine 1.4.1. That build set the Hikari `schema`, and the pool runs with auto-commit off
(`src/main/resources/application.yml`: `spring.datasource.hikari.auto-commit: false`). That combination is the
cause found for the adapters. CF-SCHEMA-FROM-URL-1 (released 1.5.0) moved the schema into the JDBC URL
(`currentSchema`) and removed the Hikari `schema` setting. Auto-commit is still off by design: the engine runs its
own transactions.

One reading at rest exists: Gökhan's stack, 2026-11-12T16:27Z, engine preview 5841aa2. It found 10 sessions idle
and none idle in transaction.

A container stress run measures the same thing on every build (Cezmi's ruling, 2026-11-13):
`EngineIdleInTransactionStressIT` runs a burst of engine jobs on the image's pool against a Testcontainers
PostgreSQL. It samples the role every second at rest, under the burst and in the drain, with the finding rule
of §5. `EngineIdleInTransactionControlIT` is the same scenario with the 1.4.1 Hikari `schema` put back, and
there the sampler must report findings. This procedure stays the recipe for a reading on a deployed stack under
the W1 profile, which the container run does not replace.

## 2. Preconditions (record each in the report)

| What | How to read it | Needed |
|---|---|---|
| Engine version of both flavours | the image tag of the `camunda` and `camunda-jobs` pods | ≥ 1.5.4 (1.5.5 when cut) |
| Database role | `SPRING_DATASOURCE_USERNAME` of both deployments (dnms-deploy `services/camunda*/…/values.yaml`; `cadenzaflow` in every environment there) | the same for both |
| No Hikari `schema` | `SPRING_DATASOURCE_HIKARI_SCHEMA` absent in both pods' env | absent |
| Pod → address map | `kubectl get pods -o wide` for both deployments, at the start AND after any restart | to tell serving from jobs |
| Reader rights | the reading role is a superuser or holds `pg_read_all_stats` | otherwise `query` reads `<insufficient privilege>` |

## 3. The query

Run it in the stack's PostgreSQL database, the one the engine connects to. Set `:'role'` to the role from §2.

```sql
-- one row per client backend of the engine's role
select now()                                              as sampled_at,
       pid,
       client_addr,
       application_name,
       state,
       wait_event_type,
       wait_event,
       round(extract(epoch from now() - xact_start))      as xact_age_s,
       round(extract(epoch from now() - state_change))    as state_age_s,
       left(regexp_replace(query, '\s+', ' ', 'g'), 300)  as last_query
from   pg_stat_activity
where  usename = :'role'
and    backend_type = 'client backend'
order  by state, state_age_s desc;
```

For an idle session, `query` is the LAST statement the session ran. In `idle in transaction` that is the statement
the open transaction last executed, which is the attribution the 2026-10-13 run could not make.

Run it from the PostgreSQL pod so no port-forward sits in the path. Append each sample to one CSV:

```bash
kubectl exec -n <ns> <postgres-pod> -- \
  psql -U <reader> -d <db> -v role=<role> -At -F ',' -f - < engine-iit.sql >> engine-iit.csv
```

## 4. Cadence

| Phase | Duration | Interval |
|---|---|---|
| **At rest (before)**: stack up, no load, after the engine pods have been Ready for ≥ 5 min | 10 min | every 5 s |
| **Under load**: the W1 profile, the same use cases and rate ladder as 2026-10-13 (25 req/s at least), whole run | the run | every 5 s |
| **Drain (after)**: the generator has stopped and the outbox and inbox gates read zero | 10 min | every 5 s |

Five seconds is below the shortest age that counts (§5) and costs one cheap catalog read per sample.

## 5. What counts, and the rule

**A finding** is a row with `state = 'idle in transaction'` and `state_age_s >= 5`. An engine command's
transaction does not sit idle for seconds between its own statements. A younger row is a transaction caught
between two statements, so it is reported in the counts but is not a finding.

**Report per phase:**

- the maximum number of findings in one sample, plus the total sample count;
- per distinct `last_query`: the count of findings, the maximum `state_age_s` and the maximum `xact_age_s`;
- the flavour, from `client_addr` mapped to a pod (§2).

**Rule:**

- **Zero findings in all three phases.** The row CLOSES against CF-SCHEMA-FROM-URL-1 by name: the sessions of
  2026-10-13 were the Hikari `schema` with auto-commit off, removed in 1.5.0. Name the engine version, the role,
  the sample count per phase and the load run id in the closing entry.
- **Any finding.** The row stays OPEN with the statement and the flavour. The engine lane reads that code path.
  - If the statement is the job acquisition (a `select` on `ACT_RU_JOB` as the executor acquires due jobs, in
    `camunda-jobs`), the lane reads the acquisition transaction, as the row says.
  - If it is a statement of this wrapper's own JDBC (the `/engine-rest/extensions/incident/*` report or retry
    queries), the lane reads that path: it runs on the auto-commit-off pool outside an engine command.
  - If it is a health or validation probe, the lane reads the probe.
  - A finding is not explained away by a knob (`idle_in_transaction_session_timeout` would hide it, not fix it).

## 6. What this procedure does not need

- No change to the stack or the engine, no extra logging, no restart.
- No access beyond reading `pg_stat_activity`. The sampler only reads.
