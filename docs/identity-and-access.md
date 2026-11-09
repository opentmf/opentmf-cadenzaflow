# Identity and access: choosing and configuring it

This guide is for a project that adopts opentmf-cadenzaflow. It explains:

- who the engine thinks a caller is;
- where that comes from;
- what the engine checks;
- how a person signs in to Cockpit, Admin and Tasklist.

Everything below is read from the code and the shipped configuration; class names and configuration keys are given so you can check. Provider-specific setup (tenant, realm, app registration) lives in [entra-azure-setup-and-testing-guide.md](entra-azure-setup-and-testing-guide.md) and the README §8.

## 1. Two layers, and which one applies where

| Path | Who is checked | By what |
|---|---|---|
| `/engine-rest/**` (services, workers, scripts) | the bearer token's **roles** | the ROLE access list `opentmf.security.secure-endpoints` (openid-rbac-security). **Nothing else.** |
| `/app/*`, `/api/*` (Cockpit, Admin, Tasklist and their back end) | the signed-in **engine user and groups** | the engine's own authorizations (`cadenzaflow.bpm.authorization.enabled: true`, `config-cadenzaflow.yml`) |

Why engine authorizations do not reach `/engine-rest`:

- The engine only knows a user where the webapp's `ContainerBasedAuthenticationFilter` runs. `CadenzaFlowSpringSecurityOAuth2AutoConfiguration#webappAuthenticationFilter` registers it for `<webapp>/app/*` and `<webapp>/api/*` only.
- On `/engine-rest` there is no engine authentication, so the engine's authorization checks never apply there.

The consequences for a **service client** (a worker, an orchestrator, a test driver):

- It needs **no engine authorizations and no group** under any identity provider.
- It needs a token that `opentmf.security` trusts (issuer, signing keys, audience, §8.4 of the README) and whose roles claim carries a role that the matching rule names. The shipped standalone rules (`config-security.yml`) look like this, and a deployment that activates a profile writes its own list (README §3.5):

```yaml
opentmf:
  security:
    secure-endpoints:          # first match wins: specific rules above the wildcards
      - { method: POST, path: /engine-rest/extensions/incident/retry, roles: [writer, admin] }
      - { method: GET,  path: /engine-rest/user/**,                    roles: [admin] }
      - { method: GET,  path: /engine-rest/**,  roles: [reader, writer, admin] }
      - { method: POST, path: /engine-rest/**,  roles: [writer, admin] }
```

- Identity and authorization administration over REST (`/engine-rest/user`, `/group`, `/identity`, `/tenant`, `/authorization`) is `admin`-only for this reason. Behind the wildcards alone, any reader could enumerate users or rewrite grants, because the engine does not check them on this path.

The engine's authorizations, the administrator group and the identity provider below therefore govern **people in the web consoles**, not API callers.

## 2. The three identity providers

`plugin.identity.provider` (env `PLUGIN_IDENTITY_PROVIDER`) selects exactly one: `keycloak`, `entra` or `token`. The default is `keycloak`: `KeycloakIdentityProvider` is `@ConditionalOnProperty(name = "plugin.identity.provider", havingValue = "keycloak", matchIfMissing = true)`, and the shipped `config-cadenzaflow.yml` sets `plugin.identity.provider: keycloak`. Each provider binds its own prefix: `plugin.identity.keycloak` (`KeycloakIdentityProvider`), `plugin.identity.entra` (`EntraIdentityProvider`) and `plugin.identity.token` (`TokenIdentityProvider`).

| | `token` | `entra` | `keycloak` |
|---|---|---|---|
| Engine user | the signed-in principal of the current request only (`TokenIdentityProviderSession#currentUser`) | Entra users read over Microsoft Graph | Keycloak users read over the Admin REST API |
| User id | the principal's name: for a Cockpit token login, the issuer's `user-claim` (§4) | mail address (`use-email-as-camunda-user-id`; `fallback-to-upn-when-mail-empty`) or object id | mail (`use-email-as-camunda-user-id`), username, or id |
| Groups | the token's authorities, i.e. the issuer's `authorities-claim` values, 1:1 (`TokenIdentityProviderSession#groupIds`; strip a prefix with `authority-prefix`) | Entra groups (display name as id with `use-group-display-name-as-camunda-group-id`; `transitive-group-membership`, `security-groups-only`) | realm groups (path as id with `use-group-path-as-camunda-group-id`) |
| User/group search (Admin pages, Tasklist assignee pickers) | **none**: only yourself; no enumeration by construction | yes | yes |
| Suitable for human tasks (candidate users/groups, assignment) | only where tasks are assigned to groups the token carries; no user search | yes | yes |
| What Cockpit shows as name / e-mail | the id itself for both (`currentUser` sets id and e-mail to the principal name; no first/last name) | Graph profile (display name, mail) | Keycloak profile (first/last name, e-mail) |
| Administrator group key | `plugin.identity.token.administrator-group-name` (default empty: none) | `plugin.identity.entra.administrator-group-name` | `plugin.identity.keycloak.administrator-group-name` (default `camunda-admin`) |
| Registration / permissions it needs | **none** beyond the sign-in | an app registration with **application** permissions `User.Read.All` + `GroupMember.Read.All` (admin consent) and a **client secret** (`plugin.identity.entra.client-id/-secret`, `tenant-id`) | a confidential client whose service account may read users and groups in the realm (`plugin.identity.keycloak.client-id/-secret`, `keycloak-issuer-url`, `keycloak-admin-url`) |

**The administrator group, every provider.** The named group is added to the engine's admin groups. The plugin re-creates an ALL grant on every resource for it at each boot, so a lost grant heals on restart (token provider: `TokenIdentityProviderPlugin#postProcessEngineBuild`). Members see and do everything in the consoles. Every other signed-in user sees only what explicit engine authorizations grant to their user or groups, which is nothing by default. The admin group's own grants are protected against edits (`AdminAuthorizationGuardPlugin`).

**The `token` provider's trade-offs, chosen deliberately:**

- the Admin user and group pages show only yourself;
- authorizations are written against group names typed by hand;
- Tasklist cannot search users;
- threads without a request (the job executor, history cleanup) see no user.

## 3. How a person signs in to Cockpit

Two paths exist. A deployment uses one of them.

### 3.1 Server-side OpenID Connect login (default)

An unauthenticated `/app/**` request starts Spring's `oauth2Login`. The registration id is `oidc`, the redirect URI `{baseUrl}/login/oauth2/code/oidc`. Its keys:

- `spring.security.oauth2.client.registration.oidc.*`, a **confidential client**: client id **and secret**;
- `spring.security.oauth2.client.provider.oidc.*`: the endpoints and `user-name-attribute`. The claim the principal is named by must be what the identity provider's directory keys users on, for example `email` with a mail-keyed directory.

PKCE (S256) is always sent. The access token is refreshed by `AuthorizeTokenFilter`.

On Entra this redirect URI must be a **Web**-platform URI. Entra binds a redirect URI to one platform type, so it cannot be shared with single-page apps.

### 3.2 Bearer login through a shared sign-in page (CF-SHARED-CALLBACK-3)

This lets the consoles and single-page UIs of a project use **one** SPA-type redirect URI. Set `cadenzaflow.sso.login-page-url` to an absolute http(s) URL **without query or fragment**, e.g. `https://ui.example.com/auth/callback/cockpit`.

- An unauthenticated webapp request is redirected to `<login-page-url>?returnTo=<url-encoded path+query>` (`LoginPageAuthenticationEntryPoint`). The page checks `returnTo` against its allowlist.
- The page signs the user in in the browser (code + PKCE, the shared SPA redirect URI). It then calls `POST <context>/app/login-with-token` with `Authorization: Bearer <access token>` and, optionally, `X-ID-Token: <id token>`. The access token's audience must be one that `opentmf.security` accepts.
- The token is validated **exactly as on `/engine-rest`**: the same `ServletJwtSupport`, so the same keys, issuer, audience, expiry, user claim and authorities claim.
  - A refused token answers `401` and nothing is stored.
  - A cross-origin call (`Sec-Fetch-Site` other than `same-origin`, or another `Origin` host) answers `403`.
- The optional `X-ID-Token` is checked against the same issuers and keys (signature, issuer, expiry). Its audience is checked against the clients it may be issued to, because an ID token's `aud` is the client that signed the user in, not the API (`IdTokenDecoders`). That list is `cadenzaflow.sso.id-token-audiences` (env `CADENZAFLOW_SSO_IDTOKENAUDIENCES`, comma-separated, e.g. `cadenzaflow,dnms-config-ui`). Empty, the default, it holds only the webapp registration's `client-id`. That fits Entra, where the shared page and the webapp are usually one app. On a Keycloak stack, list the shared page's console client (e.g. `dnms-config-ui`). A set list REPLACES the default, so name the registration's client id too if its ID tokens should still pass. An ID token for no listed client answers `401`. It is kept only as the logout's `id_token_hint`.
  - Success invalidates any earlier session, opens a new one and answers `204` with `Location` = the Cockpit URL (`LoginWithTokenFilter`).
- `cadenzaflow.sso.bearer-login.user-token-only` (default `true`) admits only a **delegated user** token (`UserTokenPolicy`). The issuer's user claim must be present. Client-credentials tokens are refused with `401`: Entra `idtyp=app` or no `scp`, and Keycloak `service-account-*`.
- The session is an ordinary webapp session. The token's expiry does not end it; the idle timeout and `cadenzaflow.sso.bearer-session-max` (default `PT8H`) do.
- Sign-out goes to the provider's end-session endpoint with `id_token_hint` = the `X-ID-Token` sent at login, or without a hint when none was sent (`SsoLogoutSuccessHandler`). With `cadenzaflow.sso.shared-callback-url` set, it returns to that URL with `state=cf.so:<path>`.
- With `login-page-url` empty, the endpoint answers `404` and §3.1 applies.

The rate limit for `login-with-token` belongs to the ingress, not the engine.

### 3.3 A signed-in user the directory does not know (OPENTMF-CADENZAFLOW-UNKNOWN-DIRECTORY-USER-401-1)

With `entra` or `keycloak`, a user the container authenticated but the directory does not hold is answered `401` (`OAuth2AuthenticationProvider`). Upstream, the webapp filter would otherwise fail with a 500 (cadenzaflow-bpm-platform issue #120). The `token` provider always knows the signed-in user.

### 3.4 Which claim names the Cockpit user (OPENTMF-CADENZAFLOW-BEARER-LOGIN-USER-CLAIM-1)

This matters only for the bearer login (§3.2) **on a directory provider**.

- `/engine-rest` names a token by the issuer's `user-claim`, typically a stable id such as Entra's `oid`.
- A mail-keyed directory knows users by mail.

Set `cadenzaflow.sso.bearer-login.user-claim` (env `CADENZAFLOW_SSO_BEARERLOGIN_USERCLAIM`), e.g. `email`, to name the session's user by that claim. The registration whose API scope the page requests must then put that claim in the **access token** (on Entra: the `email` optional claim, configured per token type). A token without it answers `401`.

With `token`, leave it empty: the user is the principal itself.

## 4. Which provider for which project

| Project | Provider | Needs | Loses |
|---|---|---|---|
| No human tasks; consoles for operators only | `token` | the operators' role in the token (`authorities-claim`); an admin role → `administrator-group-name` | user/group search; directory-backed authorizations. Cockpit labels the user with the token's `given_name` / `family_name`, else `preferred_username`, `email`, `sub` or the id |
| Human tasks, people in Entra | `entra` | Graph application permissions + secret; for the bearer login usually `bearer-login.user-claim: email` + the `email` access-token claim | nothing functional; carries the Graph permissions and a secret |
| Human tasks, people in a project Keycloak (possibly brokered to Entra) | `keycloak` | a realm client allowed to read users/groups; realm groups for candidate groups | nothing functional; users must exist in the realm (brokered users after their first login) |

## 5. Reference shape: roles as groups with the `token` provider

The local reference realm follows this pattern; the names are examples:

- **API roles** are what `secure-endpoints` checks, e.g. `<project>-read`, `<project>-write`, `<project>-admin`. Service clients get them as client roles or service-account roles.
- **An engine-admin role** (e.g. `<project>-engine-admin`) is carried by the people who administer Cockpit. It is configured as `plugin.identity.token.administrator-group-name`, so those people get the ALL grant. On Entra it is an **App Role** (`authorities-claim: roles`). Prefer App Roles over group claims: group claims are truncated to an overage pointer past a tenant limit.
- **Personas** are bundles of these roles assigned to people (a viewer, an operator, a break-glass administrator). The engine only sees the resulting role values as groups.

## 6. Worked examples

### 6.1 The DNMS mode: `token` provider and the shared-page bearer login

```yaml
plugin:
  identity:
    provider: token
    token:
      administrator-group-name: dnms-engine-admin   # the admin App Role value (example)
opentmf:
  security:
    issuers:                          # a list (List<IssuerProperties>); set INSTEAD of jwk-set-uri
      - name: entra
        issuer: https://login.microsoftonline.com/<tenant-id>/v2.0
        jwk-set-uri: https://login.microsoftonline.com/<tenant-id>/discovery/v2.0/keys
        audiences:
          - <api-client-id>           # mandatory for Entra: one tenant mints for many apps
        user-claim: oid               # the stable id
        fallback-user-claims: preferred_username,sub
        authorities-claim: roles      # App Roles
cadenzaflow:
  sso:
    shared-callback-url: https://<ui-host>/auth/callback
    login-page-url: https://<ui-host>/auth/callback/cockpit
    # bearer-login.user-claim stays unset: the user is the principal itself
```

- **Engine user** = the token's `oid`. Cockpit shows it as name and e-mail.
- **Groups** = the App Role values in `roles`.
- **Cockpit admins** = holders of the `dnms-engine-admin` App Role, through the ALL grant.
- **Nothing is read from a directory**, so the API registration needs no Graph permission and no client secret for the engine. The access token needs no `email` claim.
- **Sign-in**: one SPA-type redirect URI per environment, the shared callback. Cockpit signs in through §3.2.
- **Services** keep calling `/engine-rest` with their client-credentials tokens, gated by `secure-endpoints` roles only (§1).

### 6.2 A directory-provider project: `entra` with human tasks

```yaml
plugin:
  identity:
    provider: entra
    entra:
      tenant-id: <tenant-id>
      client-id: <api-client-id>          # holds User.Read.All + GroupMember.Read.All
      client-secret: <secret>
      use-email-as-camunda-user-id: true  # the directory keys users on mail
      administrator-group-name: <admin-group-display-name>
cadenzaflow:
  sso:
    login-page-url: https://<ui-host>/auth/callback/cockpit
    bearer-login:
      user-claim: email                   # name the Cockpit user by the claim the directory keys on
```

The `opentmf.security.issuers` entry is the same shape as in 6.1. `/engine-rest` keeps its own `user-claim`.

`/engine-rest` still maps tokens by the issuer's `user-claim` (e.g. `oid`). The Cockpit login names its user by `email`, which the directory knows.

The registration whose API scope the sign-in page requests (the access token's audience) must issue the **`email` optional claim in the access token**. A directory keyed on `preferred_username` works the same way with that claim.

With the server-side login (§3.1) instead, `spring.security.oauth2.client.provider.oidc.user-name-attribute: email` plays this role. The sign-in registration (a Web-platform client with a secret) issues `email` in its ID token.

## 7. Where each piece lives

The CadenzaFlow platform's `ContainerBasedAuthenticationFilter` receives only a principal **name** from the container (the authentication provider, here `OAuth2AuthenticationProvider`). It asks the identity provider for that user and builds the engine authentication from the answer. The Entra and Keycloak plugins only look the name up. The `token` provider answers with the principal itself.

So everything that decides **which name** arrives is wrapper code by design:

- how the token is validated (§3.2, `LoginWithTokenFilter` on `ServletJwtSupport`);
- which claim names the user (§3.4);
- what happens when the directory does not know it (§3.3).

The only platform change involved is the missing null guard in `ContainerBasedAuthenticationFilter`, proposed upstream as cadenzaflow-bpm-platform PR #121 (issue #120). Until it is released, §3.3's check in the wrapper covers it.
