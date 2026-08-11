# Identity Provider Strategy — Analysis

**Scope:** extending opentmf-cadenzaflow beyond Keycloak to Microsoft Entra ID
(formerly Azure AD), including scenarios where both providers are active at the
same time.

**Status:** analysis for decision — no implementation started.

---

## 1. Current state: the three identity layers

opentmf-cadenzaflow touches identity in three independent places. Understanding
which layer a scenario changes is the key to sizing it.

| # | Layer | Implemented by | Bound to Keycloak via |
|---|-------|----------------|----------------------|
| 1 | REST API security (`/engine-rest/**`, actuator) | `openid-rbac-security` 2.x (JWT resource server) | `opentmf.security.jwk-set-uri` + claim mapping (`user-claim: email`, `authorities-claim`) |
| 2 | Webapp SSO (Cockpit / Tasklist / Admin login) | Spring Security OAuth2 login (`CadenzaFlowSpringSecurityOAuth2AutoConfiguration`) | `spring.security.oauth2.client.*` registration (explicit endpoint URIs, no discovery — split-DNS support) |
| 3 | Engine identity service (user/group resolution, Cockpit directory, admin group) | `cadenzaflow-keycloak-4` identity provider plugin | `plugin.identity.keycloak.*` (Keycloak Admin REST API; `use-email-as-camunda-user-id`, `administrator-group-name`) |

Two structural facts that shape every scenario:

- **Layers 1 and 2 are provider-agnostic by design.** They speak standard OIDC
  (JWKS validation, authorization-code flow). Pointing them at another OIDC
  provider is configuration.
- **Layer 3 is the only Keycloak-specific component.** It implements the
  engine's read-only identity SPI against Keycloak's Admin API. No equivalent
  exists for Entra ID in the ecosystem. Without a working layer 3, webapp
  logins authenticate (layer 2) but the engine cannot resolve the user, and
  Cockpit falls back to its built-in login form — a failure mode we have
  observed and diagnosed in practice.

Also relevant: **layer 1 already authorizes purely on validated token claims.**
`engine-rest` API calls never consult the identity plugin. This is the trust
model that Scenario B generalizes.

---

## 2. Scenario A — Fully provider-agnostic (native, no Keycloak required)

**Goal:** any OIDC provider (Keycloak, Entra ID, others) can be plugged in by
configuration, with full functionality — including Cockpit user/group
browsing — and optionally more than one provider at the same time.

### Required work

1. **Layer 1 — multi-issuer resource server** (`openid-rbac-security`
   enhancement). Today the library assumes a single `jwk-set-uri`. Accepting
   tokens from N issuers requires an issuer-keyed decoder registry (Spring's
   `JwtIssuerAuthenticationManagerResolver` is the standard mechanism) plus
   per-issuer claim mapping (Entra: roles in `roles` app-role claim, user in
   `email`/`preferred_username`; Keycloak: as today).
2. **Layer 2 — multiple client registrations.** Spring supports this natively;
   with more than one registration the default auto-redirect becomes a
   provider-selection page (or a custom selector). The SSO logout handler needs
   per-provider end-session handling (Entra: `/oauth2/v2.0/logout`, resolvable
   via discovery metadata since Entra has a single public issuer URL).
3. **Layer 3 — a Microsoft Graph identity provider plugin.** A new plugin
   implementing the engine's read-only identity SPI against Microsoft Graph
   (`/users`, `/groups`, `/memberOf`) with a client-credentials app
   (`User.Read.All`, `GroupMember.Read.All` application permissions + admin
   consent). Must handle Graph paging, throttling (429 back-off), caching, and
   mapping Entra groups to engine group ids.
4. **Layer 3 for "both at once" — a composite identity provider.** The engine
   mounts exactly **one** identity provider plugin. Two live directories require
   a delegating/composite provider that merges results (and resolves id
   collisions — email is the natural join key).

### Assessment

| Aspect | Evaluation |
|--------|------------|
| Cockpit directory UX | Full (per provider) |
| Revocation freshness | Best — live directory queries (subject to plugin cache) |
| Both providers at once | Yes (with composite provider) |
| Operational dependency | None beyond the IdPs themselves |
| Effort | **Largest.** Graph plugin ≈ 3–6 weeks incl. tests; rbac multi-issuer ≈ 1 week; composite provider ≈ 1–2 weeks; integration/test matrix across providers |
| Key risks | Entra groups-overage (>~200 groups → claim dropped), Graph permission/consent process, claim normalization (UPN ≠ mail), long-term maintenance of a Graph client |

---

## 3. Scenario B — "Trust the token" identity provider

**Goal:** cheap provider-agnosticism by removing layer 3's directory dependency:
a minimal identity provider that materializes the user and their groups from the
**already-validated** token/session claims and performs **no IdP queries**.

### Security analysis (can we trust it?)

The plugin never sees unverified input. By the time it runs:

- Webapp path: the ID token was obtained by the application **server-to-server**
  from the IdP's token endpoint (authorization-code exchange) and validated for
  issuer, audience, expiry and nonce.
- REST path: the JWT's RS256 signature was verified against the IdP's published
  JWKS, plus issuer/expiry checks.

Forging a passing token requires the IdP's **private signing key**; the JWKS
endpoint publishes only public keys. A self-made token fails signature
verification and is rejected with 401 before any identity code executes. The
trust anchor is identical to what layer 1 already uses for every API call today.

What is actually given up is **freshness and directory features**, not
authentication strength:

1. **Deprovisioning lag** — a user disabled at the IdP keeps working until token
   expiry (bounded by access-token lifetime, typically 5–15 min; refresh then
   fails at the IdP). Note the Keycloak plugin runs cached
   (`cache-enabled: true`) today, so the current window is not zero either.
2. **Role/group changes** apply at next login/refresh (claims are captured at
   token issuance).
3. **No directory browsing** — Cockpit/Admin user and group lists, and
   "assign task to user…" pickers, have no directory to search (current
   principal only; other ids resolve as not-found by design).
4. **The webapp's built-in login form must be disabled** — there is no password
   check without an IdP query. (SSO-only is already the operating posture.)

### Preconditions to declare it trustworthy

- **Strict `aud` (audience) validation** in layer 1 — the realistic abuse is not
  forgery but *token confusion*: a valid token issued by the same IdP for a
  different application replayed against this API. Verify what
  `openid-rbac-security` 2.2.0 validates beyond signature/issuer and configure
  audience checking if absent. (This matters independently of Scenario B.)
- Short access-token lifetimes at the IdP.
- The plugin must only materialize the **current authenticated principal**,
  never accept arbitrary ids.

### Assessment

| Aspect | Evaluation |
|--------|------------|
| Cockpit directory UX | Degraded (no browse/pickers; login + own permissions work) |
| Revocation freshness | Token-lifetime bounded |
| Both providers at once | Yes for authentication (REST still needs multi-issuer from Scenario A item 1 if two token sources call the API) |
| Operational dependency | None |
| Effort | **Small.** ≈ 1–2 weeks incl. tests |
| Key risks | Acceptance of degraded Cockpit admin UX; audience-validation gap must be closed first |

---

## 4. Scenario C — Split IdPs: Cockpit on Entra ID, engine-rest on Keycloak

**Goal:** corporate users log into the webapps with Entra ID; all API/machine
traffic (dsync-engine, adapters, service accounts) keeps authenticating with
Keycloak tokens.

### Why it is structurally easy

Layers 1 and 2 are **separate Spring Security filter chains with independent
configuration** — they point at the same Keycloak today only by convention.
Splitting them:

1. Entra app registration (web redirect URI
   `{baseUrl}/login/oauth2/code/azure`, ID token with `email` claim).
2. Layer 2: swap (or add) the client registration —
   `issuer-uri: https://login.microsoftonline.com/{tenant}/v2.0`, keep
   `user-name-attribute: email`. One registration keeps auto-redirect; two
   registrations give a provider-selection page (webapp-level "both").
3. Logout handler: small extension for Entra's end-session endpoint (its
   authorization endpoint ends in `/authorize`, not `/auth`; discovery metadata
   covers it since Entra has a single public issuer URL).
4. Layers 1 and 3: **no change.** dsync services and all API clients are
   untouched.

### The one real constraint: the identity join

After an Entra login, the webapp hands the engine a user id = the **email
claim**, and layer 3 still resolves that user **in Keycloak**
(`use-email-as-camunda-user-id`). Therefore:

- Every Entra webapp user must exist in Keycloak **with the same email** —
  via provisioning, LDAP/SCIM sync, or Keycloak brokering with first-login
  auto-creation.
- Cockpit permissions derive from the user's **Keycloak groups**
  (e.g. `camunda-admin`) — not Entra groups. Authorization stays centralized in
  Keycloak (arguably a feature).
- The Entra `email` claim must exactly match the Keycloak email. Entra's
  `preferred_username` is usually the UPN, which is **not** always the mail
  address — the design must standardize on the `email` claim and verify it is
  populated tenant-wide.

### Assessment

| Aspect | Evaluation |
|--------|------------|
| Cockpit directory UX | Full (still Keycloak-backed) |
| Revocation freshness | As today |
| Both providers at once | Webapp: yes (2 registrations). API: Keycloak-only (by design) |
| Operational dependency | Users mirrored Entra → Keycloak (email join) |
| Effort | **Days** (config + logout tweak) + the provisioning process decision |
| Key risks | Email claim alignment (UPN vs mail); identity lifecycle in two systems |

---

## 5. Reference alternative — Keycloak brokering Entra ID (zero code)

Not requested as a scenario but the baseline every option competes with:
Keycloak's built-in **Identity Brokering** federates Entra ID as an upstream
IdP. Users get a "Sign in with Microsoft" button on the Keycloak login page;
first login auto-creates the Keycloak user (solving Scenario C's join
automatically); local Keycloak users keep working alongside. All three layers
stay exactly as shipped today. Effort: Keycloak + Entra configuration only.

---

## 6. Comparison and recommendation

| | A — Full provider-agnostic | B — Trust the token | C — Split IdPs | (Ref) Keycloak broker |
|---|---|---|---|---|
| Code effort | Large (5–9 wks) | Small (1–2 wks) | Minimal (days) | None |
| Cockpit directory | Full | Degraded | Full | Full |
| Keycloak still required | No | No | Yes | Yes |
| Both IdPs simultaneously | Yes | AuthN yes | Webapp yes / API no | Yes |
| Deprovisioning immediacy | Best | Token-lifetime | As today | As today |
| New operational burden | Graph app + consent | — | User mirroring | Broker config |

**Recommendation.**

- If Keycloak may remain in every deployment → **broker (reference)** or
  **Scenario C**; choose C when API traffic must be provably Keycloak-only and
  webapp branding requires a direct Microsoft login.
- If Keycloak must become optional → **Scenario B first** (fast, secure,
  provider-agnostic authentication), accepting degraded Cockpit admin UX;
  upgrade to **Scenario A** (Graph plugin, multi-issuer rbac) when directory
  UX or instant deprovisioning become hard requirements.
- Regardless of scenario: **audit audience validation in
  openid-rbac-security** — it is the common hardening item all options rely on.
