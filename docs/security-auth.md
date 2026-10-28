# Security + auth (OIDC)

The BFF works with any OpenID Connect (OIDC) identity provider. Keycloak is the
provider we test end to end, and it is the only one with optional adapters
that let the app create API keys and add people. Without those adapters, an
operator does both with the `samuraibff.tools.principals` command (see
[Identity provider](#identity-provider)).

## Identity provider

### What the provider must offer

| Need | Detail |
| --- | --- |
| Discovery | `{issuer}/.well-known/openid-configuration` with `issuer` equal to `SAMURAIBFF_AUTH_ISSUER`, `jwks_uri`, `authorization_endpoint` and `token_endpoint`. `end_session_endpoint` is optional. |
| Browser client | A public client (no secret) with Authorization Code + PKCE (S256), the redirect URI `<BFF origin>/auth/callback`, and the scopes `openid email profile`. |
| Access tokens | JWT, RS256, with `iss`, `sub`, `exp`, and the audience in `aud` (or in `azp`). |
| Tenant claim | A string UUID in the claim named by `SAMURAIBFF_AUTH_TENANT_CLAIM` (default `tenant_id`), on user and machine tokens. |
| Email (optional) | `email` and `email_verified` on user tokens. Needed only to activate people added with **Add person** (Keycloak adapter). |
| Machine clients (optional) | The `client_credentials` grant. The claim named by `SAMURAIBFF_AUTH_CLIENT_CLAIM` (default `azp`; Okta uses `cid`) must hold the client id. |

### Settings

| Env | Default | Purpose |
| --- | --- | --- |
| `SAMURAIBFF_AUTH_REQUIRED` | `false` | Require sign-in. Without it, every request runs as the local guest Admin. |
| `SAMURAIBFF_AUTH_ISSUER` | none | Exact issuer URL. Required when sign-in is on. |
| `SAMURAIBFF_AUTH_AUDIENCE` | `bff-web` | Audience every access token must carry. |
| `SAMURAIBFF_AUTH_CLIENT_ID` | `bff-web` | Browser client id for login. |
| `SAMURAIBFF_AUTH_TENANT_CLAIM` | `tenant_id` | Claim with the tenant UUID. |
| `SAMURAIBFF_AUTH_CLIENT_CLAIM` | `azp` | Claim with the client id of machine tokens. New. |

The issuer had a built-in default before (our hosted realm). It now has none,
so an install never trusts an issuer it did not configure.

The BFF reads the login, token and logout URLs from discovery. It does not
assume Keycloak's URL layout. Discovery is cached for five minutes. If it is
unavailable, `/auth/login` returns `503 identity-provider-unavailable` and
the callback returns `502 token-exchange-failed`.

The BFF never creates a person from a token. Identity is issuer + `sub`;
email is never an identity key. Role and status live in Postgres
(`app_users`), not in the provider.

### What depends on Keycloak

| Feature | With Keycloak adapters | With any other provider |
| --- | --- | --- |
| Sign-in, sign-out, REST, WebSockets | Works | Works |
| First Admin | Operator `provision` command | Same |
| Add people | **People → Add person** creates the account and sends the setup email (`SAMURAIBFF_KEYCLOAK_USER_ADMIN_*`) | Create the account at the provider, then run `provision` with its `sub`. **Add person** is off and says so. |
| Change role, suspend | In the app (Postgres) | Same. Suspension does not disable the account at the provider. |
| API keys | **API Credentials** creates, rotates and revokes keys (`SAMURAIBFF_KEYCLOAK_ADMIN_*`) | Create a confidential client at the provider, then run `register-key`. Admins see it, change its manager and audience, and revoke it in the app. Rotate the secret at the provider. |

With another provider, **Revoke** blocks the key in the BFF at once, but the
BFF cannot disable the client at the provider. The key stays in
`disable_pending`. Disable the client at the provider as well.

### Operator command

Run it inside the BFF image; database settings come from `SAMURAIBFF_DB_*`.
It prints ids and roles, never emails or secrets.

```bash
java -cp /app/samuraibff.jar clojure.main -m samuraibff.tools.principals provision \
  --tenant <tenant UUID> --tenant-name "Acme" \
  --issuer https://id.example.com/ --subject <user sub> \
  --role admin --name "Ada" --email ada@example.com

java -cp /app/samuraibff.jar clojure.main -m samuraibff.tools.principals register-key \
  --tenant <tenant UUID> --issuer https://id.example.com/ \
  --subject <client sub> --client-id <client id> --name "Reporting" \
  [--audience tenant|private] [--manager <Builder or Admin UUID>]
```

`--issuer` must equal `SAMURAIBFF_AUTH_ISSUER` exactly. Other commands:
`set-role`, `set-status`, `list`, `legacy-report` and `assign-owner` (see the
namespace docstring).

## Browser login flow (Authorization Code + PKCE)

Endpoints:

* `GET /auth/login?next=/live`
* `GET /auth/callback?code=...&state=...`
* `POST /auth/logout`

Logout clears the BFF cookies and, when authentication is required and the
provider advertises `end_session_endpoint`, returns `logout_url`: that
endpoint with `client_id`, `post_logout_redirect_uri=<BFF origin>/` and the ID
token as `id_token_hint`. The browser opens it so the provider ends its single
sign-on session; otherwise the next automatic login would sign the same person
in again. The callback keeps the ID token only for this purpose, in an
HttpOnly, SameSite=Lax cookie scoped to `/auth` (`id_token_hint`). A provider
without `end_session_endpoint` gets cookie-only logout.

The browser client must allow the BFF origin followed by `/` as a valid
post-logout redirect URI (in Keycloak, for example, `post.logout.redirect.uris`
set to `+`, which reuses the login redirect URIs). Electron keeps cookie-only
logout for now, because its main window may not navigate to the identity
provider.

## Electron login flow

Electron loads the UI from the configured BFF origin, so REST requests,
WebSockets, playback media, and the BFF-managed HttpOnly cookie remain
same-origin. The main window is not allowed to navigate to the identity
provider.

Instead, Electron opens `/auth/login` in a sandboxed child window that shares
the main window's cookie session but has no preload bridge. HTTPS identity
provider navigation is allowed in that isolated window; HTTP is limited to
loopback development providers. After `/auth/callback` redirects to the
validated internal `next` pathname, Electron closes the child and reloads the
main renderer's auth state.

IPC handlers validate both the sender window and configured BFF origin. The
main renderer cannot navigate to another origin, and popup creation is denied.
Do not add CORS support for `Origin: null`: packaged Electron does not use
`file://`, and broad null-origin access would weaken the BFF trust boundary.

## Token transport

The backend accepts access tokens from these sources, in order:

1) `Authorization: Bearer <token>` header
2) HttpOnly cookie (default name: `access_token`)

Query, form and body parameters never authenticate a request. This includes
`token` and `access_token`. Legacy clients that put bearer tokens in URLs must
send the Authorization header instead. A supplied bearer header takes precedence
over the cookie; an invalid bearer token does not fall back to a valid cookie.

WebSockets `/ws/events` and `/ws/audio` enforce auth **before upgrade** when `:auth {:required? true}`.

Browser and Electron WebSockets use the same-origin HttpOnly cookie. Their URLs
carry session and stream parameters; the UI does not read
`sessionStorage.access_token` or append access tokens. Connection status and
diagnostic logs omit full WebSocket URLs. SDK and other non-browser clients
continue using the Authorization header.

The normal OIDC authorization-code redirect still uses `code` and `state`.
Those protocol parameters are not bearer-token authentication. Reverse proxies
and tracing agents can record incoming URLs independently of the BFF; configure
those systems to exclude credentials, cookies and sensitive query parameters.
No new credential mechanism or environment variable is required.

## Error responses and diagnostics

Authentication and Keycloak admin errors do not include request headers,
cookies, tokens, client secrets, upstream response bodies or exception payloads.
Their diagnostic logs retain bounded exception classes and code locations.
Keycloak credential-creation errors may include the numeric upstream HTTP status.
Successful credential creation and rotation still return the secret once.

HTTP error logs use the matched route template, an allowlisted method and status,
and validated session and trace correlation. They omit raw URLs, response bodies,
arbitrary error messages and exception data. An invalid session identifier is not
used for log correlation.

Request validation and body-decoding failures return HTTP 400 with
`{"ok":false,"message":"invalid-request"}`. Unexpected failures, including
response-schema failures, return HTTP 500 with
`{"ok":false,"message":"internal-error"}`. These responses omit submitted values
and response-schema payloads. Explicit application error responses retain their
documented messages.

The login callback returns a fixed `oidc-error` message for an identity-provider
error. Token exchange rejection returns HTTP 400 with `token-exchange-failed`
and the numeric upstream status. A transport error, malformed JSON or missing
access token returns HTTP 502 with the same fixed message. Provider error text
and token responses are not returned to the browser.

These rules apply to the BFF boundaries described above. They do not sanitize
logs or traces produced independently by proxies, agents or other services.
No additional environment variables are required.

## Token validation and lifetime

Access tokens must have an RS256 signature from the configured issuer, a
non-empty `sub`, and a valid `exp`. The configured audience must match `aud`
or `azp`. The verifier checks `nbf` when present. REST verification
allows a fixed 30-second clock skew; missing or malformed required claims,
expired tokens outside that window, and tokens not yet valid are rejected.
Keep BFF and identity-provider clocks synchronized.

Discovery must report the configured issuer. Signing keys are cached for at
most five minutes. An unknown key ID can trigger a refresh, with one shared
refresh attempt per 30 seconds per process. Concurrent requests share the
attempt, and attacker-supplied key IDs do not create cache entries. A failed
refresh rejects unknown keys; previously known keys remain usable only until
their original cache expiry. Expired keys are never reused after a failed
refresh. During an issuer outage, authentication therefore stops once cached
keys expire and retries at the same bounded interval.

Each metadata response is limited to 256 KiB and a five-second total timeout;
redirects are rejected. HTTPS discovery cannot select an HTTP key endpoint.
Operator-configured HTTP issuers remain supported for private development
networks; use HTTPS for shared deployments. Tokens are limited to 16 KiB and
must contain a non-empty signing key ID of at most 256 characters. These limits
are fixed; no new environment variables are required.

Authenticated WebSockets require a token whose `exp` is still in the future.
Both endpoints close at that expiry, including idle connections, with code
`1008`. The current HTTP server does not transmit a close reason. They do not
receive the REST clock-skew extension. Audio intake and event sends also check the deadline.
Each connection uses the token supplied at its own handshake.

Browser and Electron login currently have no silent token refresh. Expiry
stops microphone capture and marks the sockets disconnected; the close code
appears in the diagnostic log. There is no dedicated re-login prompt. A long
recording can therefore stop at token expiry. Sign in again and start a new
recording. SDK clients must obtain a fresh token before opening a new session.

This is local JWT verification, not token introspection. Account disablement,
logout, and individual token revocation are not checked on each request.
Removing a signing key affects new authentication after the cached key set
expires, but existing WebSockets retain their original token deadline. They
do not re-check signing keys while open. Use short access-token lifetimes.
For emergency signing-key revocation, remove the compromised key at the
issuer and terminate existing BFF connections (for example, restart all BFF
instances); do not rely on the five-minute cache TTL to close existing sockets.

When authentication is disabled, HTTP and WebSocket requests run as the guest
principal (issuer `urn:nanosamurai:guest`) of the configured guest tenant
(`SAMURAIBFF_AUTH_GUEST_TENANT_ID`), created by the deployment seed. If it is
missing, requests fail with 503 instead of falling back to another identity.
The guest is the tenant's Admin for authoring, but sharing, the colleague
directory, People and API keys are off for it (403): a single-user install has
nobody to share with, and requests need no key. This keeps sessions, recordings, and speakers usable for the
localhost-only Community Edition quickstart. Do not use guest mode on a shared
or publicly reachable deployment.

## Tenant isolation

Within a tenant, every session has one owner and an audience (Private,
Selected people, or Everyone); see `samuraibff.access.policy`. Underneath,
tenant isolation is always enforced:

* `POST /api/sessions` binds the new session to the authenticated tenant
  (the claim named by `SAMURAIBFF_AUTH_TENANT_CLAIM`, default `tenant_id`; HTTP
  and WebSockets use the same claim).
* `/ws/events` and `/ws/audio` accept a `session_id` only if it belongs to the authenticated tenant.
* When auth is required, WS endpoints will not create sessions implicitly; unknown session ids are rejected.

This prevents a tenant from subscribing to another tenant’s session, even if a `session_id` leaks.

## Machine-to-machine (M2M) credentials

With the Keycloak adapter, the BFF creates user-managed M2M credentials backed
by Keycloak confidential clients (service accounts). With any other provider,
an operator registers clients created there with `register-key`; the rest of
this section applies to both, except that create and rotate need Keycloak.

Key properties:

* The client secret is never stored in Postgres.
* The secret is returned to the UI only once (on create/rotate).
* Tokens contain a `tenant_id` claim (protocol mapper) and are configured to include the BFF audience.

Two kinds of credential. Keycloak is identical for both (one confidential
client per credential, client-credentials grant, secret held only by
Keycloak); the kind and its rules are stored in Postgres (`api_credentials`):

| | Personal key | Organization key |
| --- | --- | --- |
| Created by | Builder or Admin, for themselves | Admin only |
| A request acts as | the key's owner, with the key attached | the key's own service principal |
| Sessions it records | owned by the owner, marked "via API key" | owned by the key; start with its audience (`private` or `tenant`) |
| Reads | `read_scope=credential` (default): only sessions it recorded; `owner`: everything the owner can read | its own sessions |
| Who manages its sessions in the UI | the owner | the key's manager, or every Admin when it has none |
| Rotated by | its owner only | its manager or an Admin |
| When the owner/manager loses Builder rights | revoked | handed to Admins (manager cleared) |

A key never administers: it cannot manage people, credentials, definitions,
publishing or defaults, and manager rights never flow through a key. Only the
owner rotates a personal key, because a new secret acts as that person; an
Admin may still revoke it. Organization keys run published workflows and
webhooks (personal keys run what their owner may run).

Who manages credentials:

* Admin: lists every credential of the organization, revokes any, creates
  organization keys and changes their manager or audience
  (`PATCH /api/api-credentials/{id}`). Credentials without a manager
  (created before access control) are Admin-managed organization keys.
* Builder: lists and manages only the credentials they manage.
* Members, service principals and API keys: `403 forbidden`. A credential the
  caller cannot manage returns `404 not-found`.

Service principal:

* Create registers a `service` principal in `app_users` for the client's
  Keycloak service account (`issuer` = `[:auth :issuer]`, `external_id` =
  service-account user id, `client_id` = Keycloak clientId) before the secret
  is returned. If registration fails, the BFF deletes the client (or disables
  it when delete fails) and returns `502 credential-provisioning-failed`.
* Tokens of a client without a registered principal get
  `403 access-not-provisioned`. Operators register clients that were created
  outside the BFF with `register-key`.
* On every request the service principal is bound to its credential row: a
  revoked credential is denied, and a personal key resolves to its owner,
  who must still be an active Builder or Admin.
* Revoke sets `revoked_at` and suspends the principal in one transaction, so
  existing tokens fail on their next request. Then the BFF disables the
  Keycloak client. The response has `upstream`: `disabled`, or `pending` when
  Keycloak did not confirm. Pending credentials have
  `identity_sync_state = disable_pending`; repeat the revoke to retry.
* Rotation keeps the principal active. A revoked credential cannot rotate
  (`400 credential-revoked`).
* When a Builder is demoted to Member or suspended, their personal keys are
  revoked the same way and the organization keys they manage become
  Admin-managed. Reactivating the person does not restore them.
* Create, configure, rotate and revoke write access audit events without
  secrets.

### Keycloak Admin API credentials (required only for managing M2M credentials)

Environment variables:

* `SAMURAIBFF_KEYCLOAK_ADMIN_ISSUER`
* `SAMURAIBFF_KEYCLOAK_ADMIN_REALM`
* `SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_ID`
* `SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_SECRET`

If the admin config is not present, the server will start. Listing, editing
and revoking keys still work; create and rotate return
`503 keycloak-admin-unavailable`, and the UI says to ask the operator.

### Debugging redirect_uri

You can see the computed authorization redirect URL without using the browser:

```bash
curl -s -D - -o NUL --max-redirs 0 "http://localhost:8000/auth/login?next=%2Flive"
```

If the provider rejects the redirect URI (Keycloak: `Invalid parameter:
redirect_uri`), fix either:

* `resources/system.edn` `:bff :origin-uri` (or `SAMURAIBFF_PUBLIC_ORIGIN_URI`), or
* the browser client's allowed redirect URIs (e.g. `http://localhost:8000/auth/callback`).

