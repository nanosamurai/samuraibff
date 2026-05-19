# Security + auth (OIDC / Keycloak)

This repo supports OIDC authentication using Keycloak.

## Browser login flow (Authorization Code + PKCE)

Endpoints:

* `GET /auth/login?next=/live`
* `GET /auth/callback?code=...&state=...`
* `POST /auth/logout`

## Token transport

The backend accepts access tokens from:

1) `Authorization: Bearer <token>` header
2) `?token=<token>` query param (useful for WS/dev)
3) HttpOnly cookie (default name: `access_token`)

WebSockets `/ws/events` and `/ws/audio` enforce auth **before upgrade** when `:auth {:required? true}`.

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

