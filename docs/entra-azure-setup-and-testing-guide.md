# Microsoft Entra ID (Azure AD) — Setup and Testing

The complete, step-by-step Entra ID guide (tenant creation, users/groups, app
registration with secrets/permissions/consents/App Roles, verification runbook and
troubleshooting) lives with the identity plugin:

**➡ [cadenzaflow-entra-identity: Setup and Testing Guide](https://github.com/cadenzaflow/cadenzaflow-entra-identity/blob/main/docs/setup-and-testing-guide.md)**

This file only covers what is specific to **opentmf-cadenzaflow**.

## Provider selection

`plugin.identity.provider: keycloak | entra` (default `keycloak`; exactly one engine
identity plugin is active). The Entra configuration block is documented (commented)
in [config-cadenzaflow.yml](../src/main/resources/config-cadenzaflow.yml).

## Service-specific settings used in the verified local run

- Application base URL: `http://localhost:8091/cadenzaflow/v1` → Entra redirect URI
  `http://localhost:8091/cadenzaflow/v1/login/oauth2/code/azure`.
- REST security: `OPENTMF_SECURITY_JWK_SET_URI=https://login.microsoftonline.com/{TENANT_ID}/discovery/v2.0/keys`
  and `OPENTMF_SECURITY_AUTHORITIES_CLAIM=roles` (App Role values must match the
  roles in [config-security.yml](../src/main/resources/config-security.yml), e.g. `admin`).
- Webapp SSO: the client registration id is the provider-neutral **`oidc`** (it is
  user-visible in `/oauth2/authorization/{id}`, so it must not say "keycloak" on an
  Entra deployment). Point its provider endpoints at Entra via
  `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_*` / `..._REGISTRATION_OIDC_*`
  environment overrides (endpoint list in the plugin guide §4), including
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_REDIRECTURI={baseUrl}/login/oauth2/code/azure`.
  The redirect-URI path segment is independent of the registration id - Spring
  resolves the registration from the `state` parameter - so an app registration
  already registered with `.../code/azure` keeps working.
- With a single registration the generated "Login with OAuth 2.0" page is skipped:
  `/login` (including the `/login?error` you get by navigating Back onto an already
  redeemed authorization code) redirects straight back to the provider.
- **One redirect URI per environment (shared callback, CF-SHARED-CALLBACK-1).** Set
  `CADENZAFLOW_SSO_SHARED_CALLBACK_URL` (`cadenzaflow.sso.shared-callback-url`) to the UIs'
  shared browser callback, e.g. `https://<ui-host>/auth/callback`. The app registration then
  needs only that one URL, as both its redirect URI and its front-channel sign-out return.
  Empty (the default) keeps the template above. With it set:
  - **Sign-in:** the authorize request carries `redirect_uri=<shared URL>`, PKCE (S256)
    and a `state` that starts with `cf.`. The token exchange sends the same `redirect_uri`.
    The shared page must send a `cf.` state to
    `<ui-origin>/cadenzaflow/v1/login/oauth2/code/oidc` with the query (`code`, `state`, ...)
    unchanged. Spring finds the saved sign-in by the whole state.
  - **Sign-out:** the end-session request carries `post_logout_redirect_uri=<shared URL>`
    exactly, with no query, and `state=cf.so:<path>`, where `<path>` is the path of
    `cadenzaflow.bpm.oauth2.sso-logout.post-logout-redirect-uri` (by default
    `/cadenzaflow/v1/app/`). The provider returns the `state` unchanged, and the shared page
    checks the path against its allowlist before sending the browser there.
  - **PKCE** is on for every sign-in, with or without the shared callback. An intercepted
    code cannot be redeemed without the session's verifier. Keycloak accepts PKCE from a
    confidential client (proven by `SharedCallbackIT`). Entra does too, per its
    documentation; the first DEV deployment proves it.
  - A value that is not an absolute http(s) URL without query or fragment fails the boot.
- Local dev launch: `mvn spring-boot:run -Dspring-boot.repackage.skip=false`
  (the pom's global boot-plugin skip flag otherwise skips the run goal), plus the
  usual `SPRING_DATASOURCE_*` overrides for a local PostgreSQL.
- Integration coverage without any tenant: `EntraIdentityIT` boots the full
  application against an embedded WireMock Graph
  (`mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=EntraIdentityIT`).
