# Security + auth (OIDC / Keycloak)

This repo supports OIDC authentication using Keycloak.

## Browser login flow (Authorization Code + PKCE)

Endpoints:

* `GET /auth/login?next=/live`
* `GET /auth/callback?code=...&state=...`
* `POST /auth/logout`

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

The backend accepts access tokens from:

1) `Authorization: Bearer <token>` header
2) `?token=<token>` query param (useful for WS/dev)
3) HttpOnly cookie (default name: `access_token`)

WebSockets `/ws/events` and `/ws/audio` enforce auth **before upgrade** when `:auth {:required? true}`.

Prefer the authorization header or the HttpOnly cookie over query tokens.
Reverse proxies and tracing agents can record URLs independently of the BFF;
configure those systems to exclude credentials, cookies and query parameters.

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
or Keycloak's `azp`. The verifier checks `nbf` when present. REST verification
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

When authentication is disabled, HTTP and WebSocket requests run under the
configured development guest tenant (`SAMURAIBFF_AUTH_GUEST_TENANT_ID`). This
keeps tenant-scoped sessions, recordings, and speakers usable for the
localhost-only Community Edition quickstart. Do not use guest mode on a shared
or publicly reachable deployment.

## Tenant isolation (current model)

At the moment, isolation is enforced **per-tenant** (not per-user):

* `POST /api/sessions` binds the new session to the authenticated tenant (`tenant_id` claim).
* `/ws/events` and `/ws/audio` accept a `session_id` only if it belongs to the authenticated tenant.
* When auth is required, WS endpoints will not create sessions implicitly; unknown session ids are rejected.

This prevents a tenant from subscribing to another tenant’s session, even if a `session_id` leaks.

## Machine-to-machine (M2M) credentials

The BFF can create user-managed M2M credentials backed by Keycloak confidential clients
(service accounts). The secret is returned only once and never stored in Postgres.

Key properties:

* The client secret is never stored in Postgres.
* The secret is returned to the UI only once (on create/rotate).
* Tokens contain a `tenant_id` claim (protocol mapper) and are configured to include the BFF audience.

### Keycloak Admin API credentials (required only for managing M2M credentials)

Environment variables:

* `SAMURAIBFF_KEYCLOAK_ADMIN_ISSUER`
* `SAMURAIBFF_KEYCLOAK_ADMIN_REALM`
* `SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_ID`
* `SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_SECRET`

If the admin config is not present, the server will start, but M2M management endpoints return
`503 keycloak-admin-unavailable`.

### Debugging redirect_uri

You can see the computed Keycloak redirect URL without using the browser:

```bash
curl -s -D - -o NUL --max-redirs 0 "http://localhost:8000/auth/login?next=%2Flive"
```

If Keycloak shows `Invalid parameter: redirect_uri`, fix either:

* `resources/system.edn` `:bff :origin-uri`, or
* Keycloak client settings → Valid Redirect URIs (e.g. `http://localhost:8000/*`).

