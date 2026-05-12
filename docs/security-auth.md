# Security + auth (OIDC / Keycloak)

This repo supports OIDC authentication using Keycloak.

This document is a short index; details may still live in
`docs/readme-archive.md` until further migrated.

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

## Machine-to-machine (M2M) credentials

The BFF can create user-managed M2M credentials backed by Keycloak confidential
clients (service accounts). The secret is returned only once and never stored in
Postgres.

Read more:

* `docs/readme-archive.md`
