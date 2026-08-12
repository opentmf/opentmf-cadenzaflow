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
- Local dev launch: `mvn spring-boot:run -Dspring-boot.repackage.skip=false`
  (the pom's global boot-plugin skip flag otherwise skips the run goal), plus the
  usual `SPRING_DATASOURCE_*` overrides for a local PostgreSQL.
- Integration coverage without any tenant: `EntraIdentityIT` boots the full
  application against an embedded WireMock Graph
  (`mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=EntraIdentityIT`).
