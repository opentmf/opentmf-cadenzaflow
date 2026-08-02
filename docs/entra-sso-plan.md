# Implementation plan — Entra ID (Azure AD) direct SSO + claims-based identity for opentmf-cadenzaflow

**Status:** planned 2026-08-02 (Gökhan + Claude, dnotify-analysis session). Not
started. Baseline: **1.0.3** (released 2026-08-02, Keycloak-only). Target: next
minor (**1.1.0**).

**Motivation:** VFDE's production policy (security architect Veit Verstege,
mail 2026-07-31): Keycloak must not broker HUMAN sign-ins in production — user
logins must integrate **directly with Azure AD (Entra ID)**, so that AD's
per-application conditional access, policy engine and sign-in monitoring apply.
Today this app's webapp SSO and Cockpit identity are Keycloak-only. This plan
makes the wrapper **provider-selectable**: `keycloak` (unchanged, default —
non-prod stays fully on Keycloak, which the policy explicitly allows) or
`entra` (direct OIDC login + claims-based identity). This objection class will
recur at other enterprise customers — the feature is product value, not a
VFDE-special.

**Why this repo suffices (verified 2026-08-02):** every affected piece is
WRAPPER code, not the forked engine: `config/sso/*`
(`CadenzaFlowSpringSecurityOAuth2AutoConfiguration`, `AuthorizeTokenFilter`,
`OAuth2AuthenticationProvider`, `OAuth2Properties`, logout plugins),
`config/KeycloakIdentityProvider` (a 12-line `@ConditionalOnProperty` wrapper
over `org.cadenzaflow.bpm.extension.keycloak.plugin.KeycloakIdentityProviderPlugin`),
the `spring.security.oauth2.client` registration and the CSP header in
`config-cadenzaflow.yml`, and `config-security.yml` (engine REST as an
opentmf resource server). **No dependency on the engine group** unless an SPI
gap surfaces (record it here if one does; optional upstream contribution
later).

## 1. Design

### 1.1 Provider selection

New property `cadenzaflow.sso.provider: keycloak | entra` (default `keycloak`
— zero behavior change for existing deployments). It gates: which
`spring.security.oauth2.client` registration is active, which
`ProcessEnginePlugin` identity provider registers, the logout handler target,
and the CSP `connect-src` host.

### 1.2 Entra login (the easy layer)

A second OAuth2 client registration/provider pair (authorization-code + PKCE):

```yaml
cadenzaflow.sso:
  provider: entra
  entra:
    tenant-id: <tenantId>
    client-id: …
    client-secret: …          # or certificate-based later; deployer-provided
    username-claim: preferred_username   # display; stable id = oid
    groups-claim: roles                  # Entra APP ROLES (recommended; see 1.3)
    admin-group-name: camunda-admin      # reuse the existing semantics

spring.security.oauth2.client:           # derived from the above (v2 endpoints)
  provider.entra:
    issuer-uri: https://login.microsoftonline.com/${tenant-id}/v2.0
```

**Config-surface split (deliberate — decision, not accident):** this app uses
BOTH security config surfaces, for different OAuth2 roles. `opentmf.security.*`
(openid-rbac-security) = the RESOURCE-SERVER role — validating incoming bearer
tokens on the engine REST (gains multi-issuer via that library's
`docs/multi-issuer-plan.md`). `spring.security.oauth2.client.*` = the LOGIN
role — the webapps' interactive authorization-code flow, which
openid-rbac-security deliberately does NOT cover (it is a resource-server
library). Do NOT fold the login config into `opentmf.security.*`: CadenzaFlow's
webapps are the estate's ONLY server-side-login application (dnms UIs are SPAs
doing browser OIDC), and Boot's client registration is already declarative —
a shared-library wrapper would be single-consumer indirection (YAGNI +
simplicity-first, 2026-08-02). The one legacy coupling —
`user-name-attribute: ${opentmf.security.user-claim}` — breaks under
multi-issuer (WHICH issuer's claim?) and moves under the provider-scoped
`cadenzaflow.sso.*` per this section.

Notes that bite: pin **v2 tokens** (`accessTokenAcceptedVersion: 2` on the app
registration — the v1/v2 issuer strings differ); `user-name-attribute` per
provider (today it's wired to `${opentmf.security.user-claim}` — becomes
provider-scoped).

### 1.3 Claims-based identity provider (the real layer)

New `ClaimsIdentityProviderPlugin` — a read-only Camunda `IdentityProvider`
that answers identity queries **from the authenticated session's token claims**
instead of Keycloak Admin API calls:

- **Groups** = the configured `groups-claim`. Recommended: **Entra App Roles**
  named exactly as the Camunda groups (`camunda-admin`, operator groups) with
  AD groups assigned to those roles on the enterprise application — the
  groups→roles mapping then LIVES IN ENTRA (the same mapping Keycloak does
  today), arrives inline in every token, and avoids the ~200-entry groups
  overage truncation of raw `groups` claims.
- **Authorization checks work unchanged**: Cockpit/engine authorizations
  reference group names; the plugin reports the current user's groups from
  claims.
- **Documented degradation (accepted):** the Admin webapp cannot BROWSE the
  user/group directory (queries answer only the current user + configured
  groups). Authorization management by group name is unaffected. A
  Microsoft-Graph-backed browsable variant is a LATER option, deliberately out
  of scope (needs `Group.Read.All` app permission + caching).
- Registration is conditional: `entra` → `ClaimsIdentityProviderPlugin`;
  `keycloak` → the existing `KeycloakIdentityProvider`. Exactly one active.

### 1.4 Generalize the SSO filter/provider

`AuthorizeTokenFilter` + `OAuth2AuthenticationProvider` currently assume the
Keycloak token/userinfo shape for extracting username + groups. Refactor to a
small per-provider claims-extractor (username-claim, groups-claim from 1.2
config) — behavior under `keycloak` byte-identical.

### 1.5 Logout + CSP

- `SsoLogoutSuccessHandler` → provider-aware end-session:
  Entra `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/logout?post_logout_redirect_uri=…`.
- The CSP `connect-src` (currently `${keycloak.url.auth}`) → the active
  provider's host.

### 1.6 Engine REST (machine surface) — no coupling to the webapp choice

`config-security.yml` secures `/engine-rest` via `opentmf.security` (resource
server). Bump **openid-rbac-security ≥ 2.3.0** (its
`docs/multi-issuer-plan.md`) so REST validates the platform M2M issuer and —
where needed — Entra tokens side by side. Webapp SSO and REST token validation
stay independently configurable.

### 1.7 Observability (the embedded-wrapper dividend — keep visible in the release notes)

Because the engine ships as OUR Spring Boot wrapper, the enterprise asks land
here without engine-core changes: Micrometer metrics (scrape), structured/
masked logging per the dnms §13 conventions, and the SIEM-shippable auth audit
events (login success/failure with provider label). Add a login-event audit
log line (provider, user-claim, outcome) in this release — it directly feeds
the VFDE monitoring/logging evidence (SbD M4.x).

### 1.8 README documentation (part of the release, not an afterthought)

The README currently documents Keycloak as THE auth story ("Keycloak /
OpenID", "Use CadenzaFlow UIs Through OpenID Authentication", "Secure
Endpoints"). This release updates it with a **Security architecture** section
stating the deliberate two-surface model (§1.2 note):

- The two OAuth2 roles side by side: **engine REST = resource server via
  openid-rbac-security** (`opentmf.security.*`, multi-issuer `issuers[]` from
  ≥ 2.3.0) vs **webapp login = Spring Boot's native
  `spring.security.oauth2.client`**, provider-selectable
  (`cadenzaflow.sso.provider: keycloak | entra`).
- The **dual-audience engine REST** fact: external-task workers + dnms-flow
  arrive with the platform M2M issuer's tokens while the Entra-logged-in
  webapps call the same endpoints with Entra session tokens — the concrete
  reason the `issuers[]` list exists; include the two-issuer config example.
- Rework the existing Keycloak sections to provider-scoped subsections
  (Keycloak = default/non-prod; Entra = the prod-policy path), with the
  claims-based identity degradation note (no directory browsing) stated where
  admins will look for it.

## 2. Non-goals

Graph-backed directory browsing (later variant) · SAML · multi-tenant Entra ·
any change to the forked engine core · changing the Keycloak path's behavior.

## 3. Testing — how to test BEFORE VFDE is involved

Three rings, first two fully in our hands:

1. **Automated (CI):** `opentmf-mockserver`'s OIDC mock issues real RSA-signed
   JWTs + serves JWKS — covers the filter/provider/claims-plugin logic and the
   REST resource-server path with Entra-SHAPED tokens (`iss`, `roles`, `oid`
   claims). The interactive authorization-code webapp flow is only partially
   mockable — that's ring 2.
2. **Real Entra, no VFDE:** a **free Entra tenant** (Microsoft 365 developer
   program or a plain free Azure AD tenant): create the app registration
   (v2, PKCE, redirect `http://localhost:8080/…`), define app roles
   `camunda-admin`/`read`/…, assign test users, run the released image locally
   (docker) pointed at it → full real login → Cockpit → authorization checks.
   This is the pre-release acceptance run and takes an afternoon to set up;
   document the tenant setup as `docs/entra-test-tenant.md` while doing it
   (it doubles as the VFDE workshop handout).
3. **VFDE validation:** their tenant's registrations (from the AD workshop)
   against `vfde-dev` — configuration only, no code expected to change.

## 4. Versioning & work breakdown

**1.1.0** (minor; default provider unchanged ⇒ no behavior change unless
opted in). CHANGELOG leads with the compatibility statement.

| # | Step | Est. |
|---|---|---|
| 1 | Provider-selection property + config plumbing (1.1, 1.2) | 1 d |
| 2 | Claims extractor generalization (1.4) + keycloak-parity regression | 1 d |
| 3 | `ClaimsIdentityProviderPlugin` (1.3) | 2–3 d |
| 4 | Logout + CSP (1.5) + audit log line (1.7) | 0.5 d |
| 5 | Ring-1 automated tests + ring-2 tenant run + docs incl. the §1.8 README security-architecture section | 2 d |
| 6 | openid-rbac ≥ 2.3.0 bump (1.6), CHANGELOG, release | 0.5 d |

## 5. Follow-ups outside this repo

- dnms/VFDE: the Entra workshop values (tenant, registrations, app-role names)
  → deployment config; the Veit-thread commitment ("evaluate direct Entra OIDC
  for the cockpit as follow-up") is DISCHARGED by this plan — reference it in
  the next security-thread update once released.
- Analysis repo: GAP-REGISTER + to-be §12 note once released; combined-rules
  §16/§25 pointer if the team adopts `entra` as the prod default for the
  engine (expected).
