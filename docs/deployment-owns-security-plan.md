# The deployment owns `opentmf.security` - implementation plan

> **Owner: this repository's session.** Written 2026-09-09 by the dnotify-analysis session at
> Gökhan's request, from four collisions measured while moving the engine onto the DNMS platform
> chart (dnms-deploy #232). **Nothing here is speculative**: every claim below was read out of this
> repository's own sources or measured on a running engine, and the measurements are named.
>
> **Scope: fix the cause at source.** The consuming deployment has an interim workaround merged
> already; this plan is what lets that workaround be deleted.

## 1. The problem, in one sentence

**The engine ships deployment-owned security configuration on the always-active classpath, so a
deployment that does not think to override it is silently governed by this image's ACL instead of
its own.**

`src/main/resources/application.yml` imports `config-security.yml` unconditionally
(`spring.config.import`), and that file sets `opentmf.security.*` in full. A deployment that mounts
its own block wins cleanly - but a deployment that mounts NOTHING inherits this one, in a
vocabulary that is not its own, with no signal that it happened.

⚠ **CORRECTED 2026-09-09, by measurement.** An earlier version of this plan - and the analysis that
prompted it - said these lists MERGE BY INDEX, so that a shorter platform list would leave this
image's tail entries alive. **That is wrong.** Spring binds a list from the highest-precedence
source that defines it and does not merge across sources: maps merge per key, **lists replace**.
Measured on a scratch pod carrying the platform's four-entry management whitelist and no service
override - the exact shape the index theory called dangerous:

```
GET /actuator          -> 401
GET /actuator/metrics  -> 401
GET /actuator/loggers  -> 401
```

Under index-merging those three would have been 200. The original before/after measurement in §1.1
could not discriminate between the two theories, because the old deployment supplied **no**
management block at all; this one can, and it settles it.

**Replacement holds between profiles too** (second experiment, same day): a `common` profile whose
management whitelist held one entry discarded a `platform` profile's whole four-entry list, and
`/actuator/health/readiness` answered 401 - the pod never went Ready. So "one author" is not a
classpath-versus-file rule; it is the rule everywhere, and the consequence of a second author is
deletion rather than blending.

**The correction makes the case for the fix stronger, not weaker.** "Our list happens to win
because we remembered to write one" is a weaker guarantee than "the image is not an author at all".
And under replace-semantics the forgetful case is not a partly-wrong ACL - it is **this image's ACL
in full force with nothing of the deployment's in it**, which is exactly the unauthenticated logger
write of §1.1. The failure this plan prevents is therefore the general case, not an example.

### 1.1 What this cost, measured

`base/infra/camunda/application-k8s.yaml` in dnms-deploy carried **no `management:` block at all**
(verified at `cf513ee^`), so the image's 9-entry management whitelist was in sole force. Anonymous
requests to the engine's management port, before and after dnms-deploy #232:

```
/actuator                          200 -> 401
/actuator/metrics                  200 -> 401
/actuator/metrics/jvm.memory.used  200 -> 401
/actuator/loggers                  200 -> 401
/actuator/health                   200 -> 200   (probes still work)
/actuator/prometheus                  -> 200   (scrape still works)
```

⚠ **And it was not only a read surface - MEASURED, not reasoned.** On a scratch reproduction of
the true pre-move shape (no platform profile at all, this image's whitelist in sole force), with no
token: `GET /actuator/loggers` -> 200, `POST /actuator/loggers/org.cadenzaflow` -> **204**, and the
level had changed from INFO to TRACE. The write landed. `application.yml` sets
`management.endpoint.loggers.access: unrestricted`, overriding the global
`management.endpoints.access.default: read_only`. With `/actuator/loggers/**` also in the
unauthenticated whitelist, the configuration **permitted an unauthenticated WRITE** - a runtime
log-level change with no token. Bounded: management port 16000, in-cluster only, no ingress, and a
local k3d stack is the only place the engine has ever run. Closed by the consumer on 2026-09-09.

⚠ **Do not "fix" this by setting `loggers.access: read_only`.** That would break the legitimate
platform rule `POST /actuator/loggers/** -> admin-class role`: the endpoint must remain writable
for the ACL to have anything to authorise. **The defect was never `unrestricted` alone - it was
`unrestricted` on a path the image also whitelisted.** Removing the whitelist authorship is the
fix; the endpoint's access level stays as it is.

## 2. The four collisions, all from the same cause

| # | What the image ships | Where | Effect on a platform deployment |
|---|---|---|---|
| 1 | `opentmf.security.management.whitelist`, **9 entries** (`/actuator`, health ×2, info, metrics ×2, loggers ×2, prometheus) | `config-security.yml` | A deployment that supplies its own list replaces this one cleanly. A deployment that supplies NONE is governed by this - and serves metrics and loggers unauthenticated while believing its platform defaults apply. **That is what happened** (§1.1). |
| 2 | `user-claim: email` | `config-security.yml` | The platform's `sub` wins only because it arrives as an env var; a mounted-file platform would collide. Accepted downstream 2026-09-09. |
| 3 | `jwk-set-uri`, derived from `${plugin.identity.keycloak.keycloak-issuer-url}` | `config-security.yml` | A deployment using **multi-issuer** (`opentmf.security.issuers[]`) then has BOTH set, which openid-rbac-security refuses at startup by design. Bites the moment the engine joins a multi-issuer environment. |
| 4 | `management.endpoint.env.roles: [admin]` | `application.yml` | `/actuator/env` is *reachable* for a platform `admin`-class caller but its **values stay masked**, because the role names do not match the platform's. Correct in this project's own vocabulary; wrong in every consumer's. |

Also present and harmless, worth tidying while nearby: the whitelist lists `/actuator/info`, which
this image does not expose (`management.endpoints.web.exposure.include` has no `info`).

## 3. The fix

**Stop being the second author. Ship these defaults only when nobody else is configuring the
application.**

### 3.1 Mechanism: `spring.profiles.default`, not a Dockerfile ENV

Move the whole `opentmf.security` block, and the role-bearing actuator settings, into
profile-gated documents activated by a **default** profile:

```yaml
# application.yml
spring:
  profiles:
    default: standalone      # applies ONLY when no profile is active
```

```yaml
# config-security.yml  (multi-document)
spring:
  config:
    activate:
      on-profile: standalone
opentmf:
  security:
    ...   # exactly today's content, unchanged
```

`spring.profiles.default` is the right instrument and a `Dockerfile` `ENV` is not:

- A bare `docker run` with no profile set activates `standalone` and behaves **exactly as today**.
  No consumer of the image who is not already setting profiles sees any change.
- A platform deployment that sets `SPRING_PROFILES_ACTIVE=platform,common,<env>` - which every
  DNMS deployment does - **automatically** stops activating `standalone`, without having to know
  this file exists. Its mounted profile becomes the sole author of `opentmf.security`, the merge
  cannot occur, and collisions 1-4 vanish together rather than being patched one at a time.
- An `ENV` in the Dockerfile would be replaced wholesale by a deployment's own value, which happens
  to work, but expresses "the image's opinion" rather than "the default when nobody has an opinion".
  The second is what this actually is.

Apply the same gating to `management.endpoint.env.roles` (collision 4). Leave
`management.endpoint.loggers.access: unrestricted` **alone** - see §1.1.

### 3.2 What is NOT proposed

- **No change to openid-rbac-security's list semantics.** Making a higher-precedence source replace
  rather than merge would change binding behaviour for every service in the estate to fix one
  image's habit. The trap is not that Spring merges lists; it is that two parties authored the same
  list. Remove the second author.
- **No change to the role vocabulary in the standalone defaults.** `reader`/`writer`/`admin` are
  correct for a standalone run and are nobody's problem once profile-gated.

## 4. Consequence to state loudly: this is a config-contract break

A deployment that already sets `SPRING_PROFILES_ACTIVE` **and relies on the image's security
defaults** loses them at this release. That is the entire point, and it fails in the safe direction:
with neither `jwk-set-uri` nor `issuers` set, openid-rbac-security **refuses to start** rather than
serving unauthenticated. Loud, at boot, naming the missing property.

⚠ It fails at BOOT, where no render-time gate can see it. dnms-deploy proved this class of failure
the hard way on 2026-09-09: a transcribed config that dropped `plugin.identity.keycloak` passed
every gate green and then crash-looped on the stack. Any consumer adopting this release must bring
up one instance before believing a green pipeline.

**Version: recommend `1.3.0`** (pom is `1.2.4-SNAPSHOT`, latest tag `1.2.3`), with an explicit
**BREAKING CHANGES** heading in the CHANGELOG naming the profile requirement and the fail-closed
behaviour. A case exists for `2.0.0` on the grounds that a configuration contract changed; that is
Gökhan's call, not this plan's. Whichever is chosen, per the SNAPSHOT rule the CHANGELOG gets a
**new section** with the bare numeric version - never a `-SNAPSHOT` heading, never an edit to
`[1.2.3]`.

## 4a. Deviations decided during implementation

Two changes to this plan were taken by Gökhan on 2026-09-09, both after measurement, and both
are in the shipped 1.3.0. Recorded here so the plan matches what was actually built.

**1. Gate the whole `management.endpoint.env` block, not only its `roles`.**
§3.1 said to gate `management.endpoint.env.roles`. Doing only that would have made platform
deployments **looser** than 1.2.3, not safer. Spring Boot's own configuration metadata for
`management.endpoint.env.roles` reads *"When empty, all authenticated users are authorized"* -
so with `roles` gated away and `show-values: when_authorized` still shipping ungated,
unsanitized environment values (datasource credentials among them) would be served to **any**
authenticated caller that reached the endpoint. Today they stay masked precisely because the
role names do not match. Gating the whole block leaves Boot's own default `show-values: never`
in force, so values are masked until a deployment opts in.

**2. Close the anonymous logger write in `standalone` too.**
§1.1 correctly says the fix is to remove the whitelist authorship rather than to touch
`loggers.access` - but as originally scoped that removal applied only to deployments. The
`standalone` profile would have kept `/actuator/loggers/**` whitelisted *and*
`access: unrestricted`, so the measured 204-with-no-token stayed open for anyone running the
image bare. Raising a logger is not a read-only act here - README §9.2 documents DEBUG on
jersey's `LoggingFeature` as the way to log request and response bodies - so both loggers paths
are dropped from the standalone whitelist and a `POST /actuator/loggers/** -> admin` rule takes
their place. `loggers.access` stays `unrestricted`, exactly as §1.1 requires. **This makes
`standalone` differ from 1.2.3** - the single respect in which it does - so step 3 below reads
"today's behaviour except the logger change", not "byte-identical".

**Implemented, and where.** `spring.profiles.default: standalone` in `application.yml`; the
whole `opentmf.security` document in `config-security.yml` gated `on-profile: standalone`;
`management.endpoint.env` gated the same way; `SecurityConfigOwnershipTests` covers steps 3 and
4 - 12 tests, of which **6 fail against the 1.2.3 configuration**. The three that pass against
1.2.3 are the mounted-deployment cases, which independently reproduces the §1 correction: a
mounted list already won cleanly before this release.

## 5. Work breakdown


| # | Step | Done when |
|---|---|---|
| 1 | `spring.profiles.default: standalone` in `application.yml`; `opentmf.security` in `config-security.yml` gated `on-profile: standalone`; `management.endpoint.env.roles` gated the same way | the file diff is the whole change - no property values edited, only their activation |
| 2 | Drop `/actuator/info` from the standalone whitelist (not exposed by this image) | - |
| 3 | **Test: no profile set → today's behaviour.** Assert the effective `opentmf.security` matches the standalone block, and that the management ACL is what 1.2.3 served | green, and it is the regression test for every consumer who does not set profiles |
| 4 | **Test: a profile set + a mounted file → the mounted file is the SOLE author.** Assert the effective management whitelist length equals the mounted one - the assertion that would have caught all four collisions | green. ⚠ Assert the LENGTH, not just membership: a leftover at index 4 is invisible to a contains-check |
| 5 | README: a section stating that a deployment activating any profile owns `opentmf.security` entirely, with the by-index trap explained and the fail-closed consequence named | a first-time integrator can read it and not repeat this |
| 6 | CHANGELOG per §4; release-readiness per the house rules - both `versions:display-*-updates` goals with **every profile activated** and the pre-release ignore regex, tests green, working tree clean, and a **fresh** Trivy scan of every image flavour in the release matrix | all reported explicitly, per flavour |

## 6. What the consumer does after the release

Not this repository's work; recorded so the plan's success condition is unambiguous.

dnms-deploy gives camunda **the standard `profilePlatform` every other DNMS service gets** - which,
per the correction in §1, is already sufficient on its own; the padding written in dnms-deploy #232
was dead weight from the first commit and is being removed in #234.

So the consumer is NOT waiting on this release to be safe. What it waits for is the *guarantee*:
today the platform is safe because its list wins, and after this release it is safe because there is
no competing list to win against. The acceptance measurement stays the same - `401` on `/actuator`,
`/actuator/metrics`, `/actuator/metrics/**` and `/actuator/loggers`, with `/actuator/health` and
`/actuator/prometheus` still `200` - and the new thing it proves is that those hold with the image
contributing nothing.
