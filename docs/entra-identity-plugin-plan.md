# CadenzaFlow Entra ID Identity Plugin — Implementation Plan

**Goal:** an Entra ID (formerly Azure AD) identity provider for CadenzaFlow with
the **same scope as `cadenzaflow-keycloak-4`**: the engine fetches users and
groups from Entra ID exactly as it does from Keycloak today — user resolution at
login, group memberships for authorization, and user/group enumeration for the
Cockpit/Admin webapps — backed by Microsoft Graph.

Relates to [identity-provider-analysis.md](identity-provider-analysis.md)
Scenario A, workstream "Graph identity provider plugin".

> **Status: delivered — this document is kept as the record of the original plan.**
> The plugin was adopted by the CadenzaFlow project and now lives at
> [cadenzaflow/cadenzaflow-entra-identity](https://github.com/cadenzaflow/cadenzaflow-entra-identity),
> released on Maven Central under `org.cadenzaflow.bpm.extension` in the same three
> lines as the Keycloak plugin: `cadenzaflow-entra-identity` (Spring 6),
> **`cadenzaflow-entra-identity-4`** (Spring 7 / Boot 4 — what this service uses) and
> `cadenzaflow-entra-identity-java11`. The packaging row below (an opentmf-owned
> repository and `org.opentmf.cadenzaflow` coordinates) is therefore superseded;
> every other decision — displayName group ids, SSO-only, hand-rolled Graph client —
> shipped as written.

---

## 1. Decisions (agreed)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Packaging | **Separate opentmf repository** (`opentmf/cadenzaflow-entra-identity`), released to Maven Central like other opentmf libraries; opentmf-cadenzaflow consumes it as a dependency | Reusable by any CadenzaFlow user; upstream-contribution candidate |
| Group id mapping | **Entra group `displayName`** as the engine group id (matches `administrator-group-name: camunda-admin` style configs). Uniqueness guard: duplicate display names are logged and rejected deterministically. Config flag to switch to object GUIDs per deployment | Human-readable authorizations, parity with Keycloak group-path UX |
| Password login (`checkPassword`) | **SSO-only** — always returns false; the webapps' built-in form cannot authenticate | Entra ROPC is legacy: rejected for MFA / conditional-access / federated accounts (near-all corporate tenants) |
| Graph client | **Hand-rolled REST** (Spring RestTemplate/RestClient + Jackson), mirroring how the Keycloak plugin calls the Admin API | Zero new dependencies; full control of paging/throttling; no Graph-SDK compatibility risk on Spring FW 7 / Java 25 |

A useful engine fact underpinning the design: the identity SPI is a **lazy
read-only facade** — no background sync, no bulk import. Graph is called only
at plugin startup (resolve the admin group), at login (user + memberships),
during authorization checks (memberships, cached), and when the webapps or
REST clients explicitly enumerate users/groups (paginated).

---

## 2. Scope: SPI surface to mirror

Class-for-class mapping from `cadenzaflow-keycloak-4` (verified against the
1.1.2 jar):

| Keycloak plugin | Entra plugin | Notes |
|-----------------|--------------|-------|
| `plugin/KeycloakIdentityProviderPlugin` | `plugin/EntraIdentityProviderPlugin` | ProcessEnginePlugin; startup admin-group resolution; registers the session factory |
| `KeycloakIdentityProviderFactory` | `EntraIdentityProviderFactory` | |
| `KeycloakIdentityProviderSession` | `EntraIdentityProviderSession` | `ReadOnlyIdentityProvider` implementation |
| `KeycloakConfiguration` | `EntraConfiguration` | properties (§4) |
| `KeycloakContext` / `KeycloakContextProvider` | `GraphTokenContext` / `GraphTokenProvider` | client-credentials token acquisition + refresh-before-expiry (no refresh-token dance: just re-request `client_credentials` with scope `https://graph.microsoft.com/.default`) |
| `KeycloakUserService` / `KeycloakGroupService` | `GraphUserService` / `GraphGroupService` | Graph REST calls (§3) |
| `KeycloakUserQuery` / `KeycloakGroupQuery` / `KeycloakTenantQuery` | `EntraUserQuery` / `EntraGroupQuery` / `EntraTenantQuery` | tenant query stays unsupported/empty, as in the Keycloak plugin |
| `CacheableKeycloakUserQuery` / `...GroupQuery` | same pattern | Caffeine query cache, same cache configuration surface |
| `CacheableKeycloakCheckPasswordCall` | *(none)* | SSO-only: `checkPassword` returns false unconditionally |
| `rest/KeycloakRestTemplate` | `rest/GraphRestClient` | auth header injection, 429 `Retry-After` back-off, `@odata.nextLink` paging |
| `util/KeycloakPluginLogger` | `util/EntraPluginLogger` | same coded-message style (`ENTRA-01xxx`) |

---

## 3. Microsoft Graph mapping

App registration (client-credentials): **application permissions**
`User.Read.All` + `GroupMember.Read.All`, admin consent granted. Token
endpoint `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token`
(authority host configurable for sovereign clouds).

| Engine operation | Graph call |
|------------------|-----------|
| User by id (email mode) | `GET /users?$filter=mail eq '{email}'` (fallback `userPrincipalName eq` — flag-controlled) |
| User by id (upn mode) | `GET /users/{upn-or-objectId}` |
| User search (first/last name, email wildcard) | `GET /users?$filter=startswith(...)` / `$search` with `ConsistencyLevel: eventual` + `$count=true` (required for advanced queries) |
| List users (paged) | `GET /users?$top={n}&$select=id,mail,userPrincipalName,givenName,surname,displayName` + `@odata.nextLink` |
| Group by id (displayName mode) | `GET /groups?$filter=displayName eq '{name}'` — **error if >1 match** (uniqueness guard) |
| List groups (paged) | `GET /groups?$top={n}&$select=id,displayName` (+ optional `securityEnabled eq true` filter) |
| User's groups (authorization checks) | `GET /users/{id}/transitiveMemberOf/microsoft.graph.group` — transitive by default so nested Entra groups grant correctly; flag to use direct `memberOf` |
| Group members | `GET /groups/{id}/transitiveMembers/microsoft.graph.user` (same flag → `/members`) |

Cross-cutting client behavior: `$select` always set (lean payloads), `$top`
≤ 999, `@odata.nextLink` followed up to the engine query's `maxResults`,
HTTP 429 honored via `Retry-After` with bounded retries, all responses mapped
with Jackson records.

Query-capability note (parity honesty): Graph cannot express every engine
query combination (e.g. arbitrary `like` on all attributes). Follow the
Keycloak plugin's precedent — support the filters the webapps and common REST
queries use; unsupported combinations return empty with a debug log, never an
exception.

---

## 4. Configuration surface

Prefix `plugin.identity.entra` (sibling of `plugin.identity.keycloak`):

```yaml
plugin.identity.entra:
  tenant-id: <guid>                       # required
  client-id: <app-registration client id> # required
  client-secret: <secret>                 # required (cert support = later enhancement)
  authority-host: https://login.microsoftonline.com   # sovereign-cloud override
  graph-base-url: https://graph.microsoft.com/v1.0    # sovereign-cloud override
  use-email-as-camunda-user-id: true      # mail claim/field; false -> userPrincipalName
  fallback-to-upn-when-mail-empty: true   # guests / users without Exchange mail
  use-group-display-name-as-camunda-group-id: true    # false -> object GUID
  transitive-group-membership: true
  security-groups-only: true              # exclude M365/dynamic distribution noise
  administrator-group-name: camunda-admin
  administrator-user-id:                   # optional, as in Keycloak plugin
  cache-enabled: true
  cache-max-size: 500
  cache-expiration-timeout: PT15M
  max-http-connections: 50
```

**Mutual exclusivity:** the engine mounts exactly one identity provider.
Auto-configuration activates the Entra plugin on presence of
`plugin.identity.entra.tenant-id`, the Keycloak plugin on its issuer-url, and
**fails fast with a clear message if both are configured**.

---

## 5. Repository setup

- `github.com/opentmf/cadenzaflow-entra-identity`, Maven coords
  `org.opentmf.cadenzaflow:cadenzaflow-entra-identity`.
- Depends on `org.cadenzaflow.bpm:cadenzaflow-engine` (provided scope),
  Spring Framework 7 line, Caffeine, Jackson — matching the versions the
  `-4` starter tree ships.
- Bytecode target Java 17 (library-friendly; the Keycloak plugin lineage
  targets even lower) even though opentmf-cadenzaflow runs Java 25.
- Standard opentmf release profile (sources/javadoc/gpg/central-publishing),
  CHANGELOG, README with the app-registration + admin-consent runbook.

## 6. Changes in opentmf-cadenzaflow (consumer side)

1. Add the plugin dependency; document the `plugin.identity.entra.*` block in
   `config-cadenzaflow.yml` (commented alternative to the Keycloak block).
2. Webapp SSO: add/replace client registration `azure`
   (`issuer-uri: https://login.microsoftonline.com/{tenant}/v2.0`,
   `user-name-attribute: email`, scopes `openid,profile,email`).
3. `SsoLogoutSuccessHandler`: handle Entra end-session — its authorization
   endpoint ends `/authorize` (not `/auth`), logout at `/oauth2/v2.0/logout`;
   prefer discovery metadata when the registration has an issuer-uri, keep the
   suffix-derivation for the split-DNS Keycloak case. (Backport note: the
   camunda7 sibling's handler gets the same treatment.)
4. `opentmf.security.*` (REST layer): point `jwk-set-uri` at
   `https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys`, map
   `authorities-claim` to Entra App Roles (`roles` claim) when the API should
   accept Entra tokens; unchanged when the API stays on Keycloak (split-IdP
   deployment — both combinations must be documented).

## 7. Milestones

| # | Milestone | Contents | Exit criterion |
|---|-----------|----------|----------------|
| M1 | Skeleton + auth + login path | Repo scaffold, `GraphTokenProvider`, user-by-email/upn, user's transitive groups, admin-group startup resolution, plugin/factory/session wiring | Engine boots with Entra plugin; a mocked login resolves user + groups |
| M2 | Enumeration + queries | User/group list queries with filters, paging, sorting; group members; uniqueness guard; unsupported-filter policy | Admin webapp user/group pages render against Graph mock |
| M3 | Robustness | Caffeine query cache (config parity), 429 back-off, `$select` discipline, coded logger, negative-path hardening | Load/soak test against mock incl. 429 injection passes |
| M4 | Consumer wiring + ITs | opentmf-cadenzaflow config blocks, SSO registration, logout handling; WireMock-based Graph fixtures IT in the plugin repo; service-level IT variant with the Entra plugin active | Full IT suite green without Docker-external IdP |
| M5 | Real-tenant verification + release | Runbook against a real Entra tenant (app registration, consent, App Roles), README/CHANGELOG, 1.0.0 to Central; docs update in opentmf-cadenzaflow | Cockpit SSO login + authorization + enumeration verified against live Entra |

Estimated effort: **3–5 weeks** (M1–M3 ≈ 2–3 wks, M4 ≈ 1 wk, M5 ≈ tenant-dependent).

## 8. Testing strategy

- **Unit**: query translation (engine filter → Graph URL), displayName
  uniqueness guard, token refresh, paging assembly, cache behavior.
- **Integration (plugin repo)**: WireMock Graph fixtures — canned `/users`,
  `/groups`, `/transitiveMemberOf` responses incl. `@odata.nextLink` chains and
  429 responses. Runs in-JVM: **no Docker required** (fits current resource
  constraints).
- **Integration (service repo)**: `OpenTmfCadenzaFlowApplicationIT` variant
  booting with the Entra plugin against the WireMock fixture set.
- **Manual runbook (M5)**: live tenant — login, admin rights via
  `camunda-admin` Entra group, user/group browsing, deprovisioned-user
  behavior, throttling observation.

## 9. Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Duplicate group displayNames in tenant | Uniqueness guard (deterministic failure + log); GUID mode flag as escape hatch |
| Users without `mail` (guests, non-Exchange) | `fallback-to-upn-when-mail-empty`; documented claim-alignment requirement with the SSO `email` claim |
| Graph throttling on large tenants | Cache + `$select` + paging discipline; enumeration is user-triggered only (no background sync) |
| Advanced-query constraints (`ConsistencyLevel`) | Encapsulated in `GraphRestClient`; covered by fixtures |
| Both identity plugins configured | Fail-fast startup guard |
| ROPC expectation from Keycloak parity | Explicitly documented as unsupported (SSO-only decision) |
