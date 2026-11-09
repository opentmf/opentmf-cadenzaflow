# Microsoft Entra ID (Azure AD) — Setup and Testing

The complete, step-by-step Entra ID guide (tenant creation, users/groups, app
registration with secrets/permissions/consents/App Roles, verification runbook and
troubleshooting) lives with the identity plugin:

**➡ [cadenzaflow-entra-identity: Setup and Testing Guide](https://github.com/cadenzaflow/cadenzaflow-entra-identity/blob/main/docs/setup-and-testing-guide.md)**

This file only covers what is specific to **opentmf-cadenzaflow**. Which identity provider
to choose (`token`, `entra`, `keycloak`), what engine authorizations do and do not cover,
and worked examples are in [identity-and-access.md](identity-and-access.md).

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
- **The UIs' shared browser callback (CF-SHARED-CALLBACK-1/-2).** Two settings:
  `CADENZAFLOW_SSO_SHARED_CALLBACK_URL` (`cadenzaflow.sso.shared-callback-url`), the shared
  page, e.g. `https://<ui-host>/auth/callback`; and `CADENZAFLOW_SSO_SHARED_CALLBACK_SIGN_IN`
  (`cadenzaflow.sso.shared-callback-sign-in`, default `false`). Both empty/false keeps
  today's behaviour.
  - **On Entra, Cockpit signs in through the shared page by token (CF-SHARED-CALLBACK-3).**
    Set `CADENZAFLOW_SSO_LOGIN_PAGE_URL` (`cadenzaflow.sso.login-page-url`) to the page's
    Cockpit route. An unauthenticated Cockpit/Admin/Tasklist request is then redirected
    there with `?returnTo=<path>`. The page runs authorization code + PKCE in the browser
    (SPA platform, the shared URL, the same registration, a scope that includes the
    registration's API scope, so the access token's `aud` is what `/engine-rest` accepts).
    It then calls `POST /cadenzaflow/v1/app/login-with-token` (same origin) with
    `Authorization: Bearer <access token>` and, optionally, `X-ID-Token: <ID token>`. The
    access token is validated exactly as an `/engine-rest` bearer: same keys, issuer,
    audience (on Entra the bare client id, via the registration's API scope the page
    requests), expiry, user and roles claims. The ID token is checked against the same
    issuers and keys, signature and expiry. By default its `aud` must be the registration's
    `client-id` (on Entra, the app the shared page signs in with) or one of the issuer's
    `audiences`. Since 1.5.3 you can set `cadenzaflow.sso.id-token-audiences` to narrow it
    to named clients, for example only the console client. An ID token is issued to the
    client, not to the API. It is kept only as the logout's `id_token_hint`. Do not add the client id to
    `opentmf.security.issuers[].audiences` for it: that would widen what `/engine-rest`
    accepts, and the ID token does not need it since 1.5.2. A new session is opened (any previous
    session is invalidated), and the answer is `204` with `Location` = the Cockpit app URL.
    **Which claim names the user (OPENTMF-CADENZAFLOW-BEARER-LOGIN-USER-CLAIM-1).** With
    `plugin.identity.provider: token` there is nothing to set: the engine user is the
    signed-in principal, i.e. the claim `/engine-rest` maps tokens by (on Entra the stable
    `oid`). This setting is for projects on a DIRECTORY provider (`entra`, `keycloak`) whose
    directory keys users on another claim. For example, the Entra plugin keys users on their
    mail address (`use-email-as-camunda-user-id`) while `/engine-rest` maps by `oid`. There,
    set `CADENZAFLOW_SSO_BEARERLOGIN_USERCLAIM=email`
    (`cadenzaflow.sso.bearer-login.user-claim`). The Cockpit session's engine user is then
    the token's `email`; the authorities and `/engine-rest`'s own principal are unchanged.
    The registration whose API scope the page requests (the access token's audience) must
    then issue the **`email` optional claim in the access token**; a token without it
    answers `401`. Without the setting, on such a project, a user whose `oid` is not a
    directory id answers `401` at Cockpit (the directory does not know it). Empty, the
    default, changes nothing.
    Only a delegated USER token opens a session
    (`cadenzaflow.sso.bearer-login.user-token-only`, default `true`). It needs the
    configured user claim, and it must not be a client-credentials token: on Entra
    `idtyp=app`, or no `scp`; on Keycloak a `service-account-*` principal. Such tokens,
    which `/engine-rest` does accept, answer `401` with one WARN naming the reason. Rate
    limiting of this endpoint is not the engine's: the ingress carries it, with the same
    per-IP limit as the Cockpit login page.
    A refused token answers `401` and nothing is stored; a cross-origin call
    (`Sec-Fetch-Site` other than `same-origin`, or another `Origin` host) answers `403`.
    From then on it is an ordinary webapp session: the token's own expiry does not end it,
    the idle timeout does, and so does `cadenzaflow.sso.bearer-session-max` (default `PT8H`)
    counted from the login. Sign-out goes to the shared URL as above, with the ID token as
    `id_token_hint` when the page sent one. With this in place, the
    registration needs ONE SPA-type redirect URI per environment, and Cockpit's Web-type
    `.../login/oauth2/code/oidc` URI leaves the registration. Without
    `login-page-url`, the shared URL is for sign-out only. Entra binds a
    redirect URI to ONE platform type: Cockpit redeems its code server-side with the
    secret (Web), the UIs redeem theirs in the browser (SPA). One URI cannot serve both
    (AADSTS9002326 / AADSTS9002327). So Cockpit keeps its own Web-type sign-in URI
    (`.../cadenzaflow/v1/login/oauth2/code/oidc`, the template above), and the shared URL
    is registered as its sign-out return.
  - **On Keycloak (local stack), both.** With sign-in `true`, the authorize request carries
    `redirect_uri=<shared URL>` and a `state` that starts with `cf.`. The token exchange
    sends the same `redirect_uri`. The shared page must send a `cf.` state to
    `<ui-origin>/cadenzaflow/v1/login/oauth2/code/oidc` with the query (`code`, `state`, ...)
    unchanged. Spring finds the saved sign-in by the whole state. Sign-in `true` without a
    URL fails the boot.
  - **Sign-out**, whenever the URL is set: the end-session request carries `post_logout_redirect_uri=<shared URL>`
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
