# Mixed Identity Provider Recipes (Keycloak + Entra ID)

The three security layers of opentmf-cadenzaflow are configured independently, so the
webapp and the REST API can trust **different** providers — and since
openid-rbac-security **2.3.0**, the REST API can trust **both at once**.

| Layer | Config | Constraint |
|---|---|---|
| Webapp SSO (browser) | `spring.security.oauth2.client.*` | any provider |
| Engine identity plugin | `plugin.identity.provider: keycloak \| entra` | **exactly one** — must be the directory where webapp-login users exist (join key = email) |
| REST API security | `opentmf.security.jwk-set-uri` (single issuer) or `opentmf.security.issuers[]` (multi, rbac ≥ 2.3.0) | validates tokens cryptographically; never consults the identity plugin |

Rule of thumb: **the identity plugin follows the webapp's provider** (or a directory
mirroring its users by email). The REST layer is free-standing: it authorizes purely
on validated token claims.

Prerequisites per provider are documented in the
[Entra setup guide](https://github.com/cadenzaflow/cadenzaflow-entra-identity/blob/main/docs/setup-and-testing-guide.md)
(app registration, App Roles, claims) and the standard Keycloak realm setup (client,
realm roles/groups).

---

## Recipe A — Webapp on Entra ID, engine-rest on Keycloak

Corporate users log into Cockpit with Microsoft; all machine/API clients keep their
Keycloak tokens (nothing changes for them).

```yaml
plugin.identity.provider: keycloak          # webapp users resolved in Keycloak
plugin.identity.keycloak:                   # unchanged

# Browser SSO -> Entra
spring.security.oauth2:
  client:
    registration:
      oidc:                     # provider-neutral id; it is user-visible in URLs
        client-id: {CLIENT_ID}
        client-secret: ${ENTRA_CLIENT_SECRET}
        redirect-uri: "{baseUrl}/login/oauth2/code/azure"
        scope: openid, profile, email
    provider:
      oidc:
        authorization-uri: https://login.microsoftonline.com/{TENANT_ID}/oauth2/v2.0/authorize
        token-uri: https://login.microsoftonline.com/{TENANT_ID}/oauth2/v2.0/token
        jwk-set-uri: https://login.microsoftonline.com/{TENANT_ID}/discovery/v2.0/keys
        user-info-uri: https://graph.microsoft.com/oidc/userinfo
        user-name-attribute: email

# REST -> Keycloak (unchanged)
opentmf.security:
  jwk-set-uri: ${plugin.identity.keycloak.keycloak-issuer-url}/protocol/openid-connect/certs
  user-claim: email
  authorities-claim: roles
```

**The join constraint:** every Entra user who logs into the webapp must exist in
Keycloak **with the same email** (provisioning, LDAP/SCIM sync, or Keycloak brokering
with first-login auto-creation). Cockpit admin rights come from the *Keycloak*
`camunda-admin` group — Entra groups play no role here. Verify the Entra `email`
claim equals the Keycloak email exactly (Entra's `preferred_username`/UPN is often
not the mail address).

## Recipe B — Webapp on Keycloak, engine-rest on Entra ID

Cockpit stays on Keycloak; API callers authenticate with Entra tokens (App Roles).

```yaml
plugin.identity.provider: keycloak          # webapp users resolved in Keycloak
# spring.security.oauth2.* -> Keycloak      # unchanged

# REST -> Entra
opentmf.security:
  jwk-set-uri: https://login.microsoftonline.com/{TENANT_ID}/discovery/v2.0/keys
  user-claim: email
  authorities-claim: roles                  # Entra App Roles; values must match
                                            # config-security.yml role names
```

No directory join is needed: the REST layer never looks callers up in the identity
plugin. API callers need App Role assignments (`admin`/`writer`/`reader`) on the
Entra app registration. Note that `engine-rest/user` / `/group` query results still
come from the *identity plugin's* directory (Keycloak here) — directory content and
token trust are decoupled by design.

The full-Entra variant (webapp **and** REST on Entra, identity plugin `entra`) is the
configuration verified in the Entra setup guide.

## Recipe C — engine-rest accepts BOTH providers (rbac ≥ 2.3.0)

`opentmf.security.issuers[]` replaces the single `jwk-set-uri` (they are mutually
exclusive; boot fails if both or neither are set). Tokens route by exact `iss` match;
unknown issuers are rejected — there is no fallback.

```yaml
opentmf.security:
  user-claim: email                # inherited defaults; entries may override
  authorities-claim: roles
  issuers:
    - name: keycloak
      issuer: ${plugin.identity.keycloak.keycloak-issuer-url}
      jwk-set-uri: ${plugin.identity.keycloak.keycloak-issuer-url}/protocol/openid-connect/certs
      audiences: [ camunda-identity-service ]
    - name: entra
      issuer: https://login.microsoftonline.com/{TENANT_ID}/v2.0
      jwk-set-uri: https://login.microsoftonline.com/{TENANT_ID}/discovery/v2.0/keys
      user-claim: email
      fallback-user-claims: [ preferred_username, oid ]
      audiences: [ "{CLIENT_ID}" ]     # v2: bare client id - see the token-version note
```

Notes (from the 2.3.0 property contract, confirmed against a live tenant):

- **Token version decides BOTH `iss` and `aud`.** An app registration with
  `api.requestedAccessTokenVersion: 2` mints access tokens with
  `iss: https://login.microsoftonline.com/{tenantId}/v2.0` and `aud:` the **bare
  client id**. Left at the default (v1) the same app emits
  `iss: https://sts.windows.net/{tenantId}/` (trailing slash) and `aud:` the App ID
  URI `api://{CLIENT_ID}`. Both values must match the token you actually receive -
  `issuers[]` routes by exact `iss` and rejects unknown issuers with no fallback, so
  a mismatch is a silent 401. The request `scope` keeps the `api://.../<scope>` form
  in both cases; only the resulting `aud` claim differs. Decode a real token
  (https://jwt.ms) and copy `iss`/`aud` verbatim rather than assuming.
- **Set `audiences` in production.** Without it, any token from the same tenant —
  issued to a *different* application — passes the issuer check. This is the
  token-confusion hardening this project's identity analysis called out.
- **`user-claim` for Entra:** access tokens carry `email` only when the app
  registration declares it as an optional claim **for the Access token type**
  (optional claims are configured per token type; enabling it for ID tokens alone is
  not enough). Without it the principal falls back to `sub` - an opaque pairwise GUID
  in the audit trail. Either add the optional claim or set `user-claim: oid` with
  `preferred_username` as a fallback.
- Role vocabulary stays provider-blind: endpoint rules don't fork per issuer, so
  name Entra App Roles and Keycloak roles identically (`admin`, `writer`, `reader`).
- Requires `openid-rbac-security.version` ≥ **2.3.0** in the service pom
  (2.2.0, the current default, is single-issuer only).
