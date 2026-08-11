# Identity Provider Strategy — Implementation Plan

Companion to [identity-provider-analysis.md](identity-provider-analysis.md).
The plan is phased so that each phase delivers a usable outcome on its own and
later phases build on, rather than rework, earlier ones. Scenario letters
(A / B / C) refer to the analysis document.

---

## Phase 0 — Common groundwork (required for every scenario)

**Outcome:** hardening and prerequisites that all scenarios depend on.

| # | Task | Repo / component | Notes |
|---|------|------------------|-------|
| 0.1 | **Audit audience (`aud`) validation** in the JWT resource-server path; add configurable audience checking if absent | `openid-rbac-security` | Closes the token-confusion vector; prerequisite for declaring Scenario B trustworthy, valuable everywhere |
| 0.2 | Entra ID test tenant + **app registration** | Entra portal | Web platform redirect URI `{baseUrl}/login/oauth2/code/azure`; ID tokens enabled; `email` claim configured; client secret (or cert) issued |
| 0.3 | Define **App Roles** `ebu-federation-reader` / `-writer` / `-admin` (or agreed names) on the app registration; assign test users | Entra portal | Avoids the groups-overage problem and GUID↔name mapping entirely |
| 0.4 | Verify **`email` claim population** for all in-scope Entra users (UPN ≠ mail) | Entra tenant | Join-key correctness for Scenarios B/C |
| 0.5 | Decide access-token lifetime policy (5–15 min) | Both IdPs | Bounds the deprovisioning window in Scenario B |

**Acceptance:** a test user can obtain an Entra ID token whose decoded claims
show the expected `email` and role/app-role values; rbac rejects a valid token
minted for a different audience.

---

## Phase 1 — Scenario C: Cockpit on Entra ID, engine-rest on Keycloak

**Outcome:** corporate webapp login via Microsoft; zero change for API clients.
Smallest useful increment; also serves as the live proof that layers 1 and 2
are independent.

| # | Task | File / component |
|---|------|------------------|
| 1.1 | Add/replace client registration `azure` (issuer-uri discovery, client-id/secret via env, `user-name-attribute: email`, scopes `openid,profile,email`) | `config-cadenzaflow.yml` (`spring.security.oauth2.*`) |
| 1.2 | Extend `SsoLogoutSuccessHandler`: handle Entra end-session (authorization endpoint suffix `/authorize` → `/oauth2/v2.0/logout`, or prefer discovery metadata when the registration has an issuer-uri) | `org.opentmf.cadenzaflow.config.sso.SsoLogoutSuccessHandler` (+ unit tests per provider) |
| 1.3 | Keep `opentmf.security.*` and `plugin.identity.keycloak.*` unchanged; document the split explicitly | `config-security.yml`, README |
| 1.4 | **User mirroring decision + setup**: provisioning, LDAP/SCIM sync, or Keycloak broker auto-creation — every Entra webapp user must exist in Keycloak with the same email; Cockpit rights via Keycloak groups | Ops / Keycloak realm |
| 1.5 | If "both" webapp providers wanted: keep `keycloak` registration alongside `azure` → Spring provider-selection page; verify logout for each | `config-cadenzaflow.yml` |

**Verification**

- Browser: Cockpit login lands on the Microsoft page; login as an Entra user
  mirrored in Keycloak succeeds; Cockpit shows the user with correct admin
  rights (Keycloak group); logout terminates the Entra session and returns to
  the app.
- API: existing Keycloak-token curl checks and the dsync stack (BPMN sync,
  external tasks, flow execution) run unchanged.
- Negative: Entra user *not* mirrored in Keycloak is rejected with a clear log
  (identity plugin user-not-found), not a stack trace.

**Effort:** ~2–4 days engineering + the mirroring process decision.

---

## Phase 2 — Scenario B: "trust the token" identity provider

**Outcome:** Keycloak becomes optional; any OIDC IdP can back the webapps
without a directory-query plugin. Ships behind a configuration switch, so
existing Keycloak-plugin deployments are untouched.

| # | Task | Component |
|---|------|-----------|
| 2.1 | New engine plugin `TokenClaimsIdentityProviderPlugin`: read-only identity provider materializing **only the current authenticated principal** (user id, name, email, groups) from the verified session/token claims; every other id resolves not-found; password checks always fail | new package `org.opentmf.cadenzaflow.config.identity.tokenclaims` (inlined, like the incident logger) |
| 2.2 | Config switch `opentmf.identity.provider: keycloak | token-claims` selecting exactly one of the two plugins at startup | auto-configuration + `config-cadenzaflow.yml` |
| 2.3 | Groups/admin mapping from claims (`authorities-claim`, `administrator-group-name` semantics preserved) | same plugin |
| 2.4 | Disable/hide the webapp's built-in login form when `token-claims` is active (SSO-only enforcement) | webapp security config |
| 2.5 | Documentation: capabilities matrix (what Cockpit loses: browse/pickers), security rationale (verified-token trust anchor, Phase 0.1 audience validation), deprovisioning window statement | README / docs |
| 2.6 | Tests: unit (plugin SPI semantics incl. foreign-id rejection), IT variant running the full stack **without** the Keycloak identity plugin — login via Entra (or a second Keycloak playing "foreign IdP"), engine resolves the user from claims | `src/test` |

**Verification**

- Cockpit login with an IdP user that exists **nowhere** in Keycloak succeeds;
  admin rights follow the token's group/role claims.
- `engine-rest` behavior unchanged (it already trusts validated tokens).
- Forged/expired/wrong-audience tokens rejected with 401 (regression tests).
- Cockpit user/group browse pages degrade gracefully (empty, no errors).

**Effort:** ~1–2 weeks including tests and docs.

---

## Phase 3 — Scenario A: full provider-agnostic support

**Outcome:** feature-complete multi-provider identity, including Cockpit
directory UX, without Keycloak. Undertake only when directory browsing or
instant deprovisioning become hard requirements.

### Workstream 3a — Multi-issuer REST security (`openid-rbac-security`)

| # | Task |
|---|------|
| 3a.1 | Issuer-keyed decoder registry (`JwtIssuerAuthenticationManagerResolver`); config shape `opentmf.security.issuers[]` with per-issuer `jwk-set-uri`, `user-claim`, `authorities-claim`, audience |
| 3a.2 | Backward compatibility: single-issuer configuration keeps working unchanged (2.x minor release, no breaking change) |
| 3a.3 | Same treatment for the management-port chain |

### Workstream 3b — Microsoft Graph identity provider plugin

| # | Task |
|---|------|
| 3b.1 | New plugin (own repo or module) implementing the engine's read-only identity SPI against Microsoft Graph: user query/search, group query, membership (`/users`, `/groups`, `/memberOf`) |
| 3b.2 | Client-credentials Graph app: `User.Read.All`, `GroupMember.Read.All` application permissions, admin-consent process documented |
| 3b.3 | Robustness: Graph paging, 429 throttling back-off, bounded cache (mirroring the Keycloak plugin's `cache-enabled` semantics) |
| 3b.4 | Id mapping: email as engine user id (consistent with `use-email-as-camunda-user-id`), Entra group → engine group id strategy |
| 3b.5 | Testcontainers-style IT against a Graph mock (e.g. WireMock fixtures) + a manual verification runbook against a real tenant |

### Workstream 3c — Simultaneous providers

| # | Task |
|---|------|
| 3c.1 | Composite/delegating identity provider (engine mounts one plugin): ordered lookup across providers, email as the collision key, deterministic precedence |
| 3c.2 | Webapp: multiple client registrations + provider-selection UX; per-provider logout (Phase 1.2 groundwork reused) |
| 3c.3 | End-to-end IT: user from provider 1 and user from provider 2 both log into Cockpit and both call `engine-rest`, each with correct roles |

**Verification (phase gate):** the full dsync verification suite (BPMN sync,
external tasks, flow execution) passes with (a) Entra-only identity, and
(b) Keycloak + Entra simultaneously; Cockpit directory pages list users/groups
from the active provider(s).

**Effort:** ≈ 5–9 weeks total (3a ≈ 1 wk, 3b ≈ 3–6 wks, 3c ≈ 1–2 wks),
parallelizable across 3a and 3b.

---

## Sequencing, decision gates and rollback

```
Phase 0 ──► Phase 1 (Scenario C, days)
               │  gate: is user-mirroring acceptable long-term?
               ├── yes ──► stop here (or add Keycloak broker for auto-creation)
               └── no ───► Phase 2 (Scenario B, 1–2 wks)
                              │  gate: is degraded Cockpit directory UX acceptable?
                              ├── yes ──► stop here
                              └── no ───► Phase 3 (Scenario A, 5–9 wks)
```

- Every phase is switch-guarded (`spring` registration ids, `opentmf.identity.provider`,
  rbac issuer list), so rollback is configuration, not code reverts.
- The camunda7 sibling (`opentmf-camunda7`) can receive Phase 1/2 backports with
  the same file-level changes if needed; Phase 3 workstream 3a lands in the
  shared `openid-rbac-security` library and benefits both automatically.

## Out of scope

- SCIM-based user provisioning pipelines (Phase 1.4 selects an approach; its
  implementation is an ops project).
- dsync-suite services' own security (they consume `openid-rbac-security`
  directly and inherit workstream 3a when they upgrade).
