# StockFlow — Technical Requirements Document

Status: Implementation specification | Date: 2026-10-13

## 1. Related documents and authority

- [PRD](PRD.md): scope, business rules, roles, and acceptance criteria.
- [App flow](APP_FLOW.md): navigation and user journeys.
- [Design brief](DESIGN_BRIEF.md): visual and interaction requirements.
- [Backend schema](BACKEND_SCHEMA.md): persistence contracts.
- [Implementation plan](IMPLEMENTATION_PLAN.md): delivery sequence and gates.

The PRD governs product scope. This document defines technical decisions within that scope. Hosting and exact dependency versions must be confirmed before deployment. All features described here are planned, not claims of existing implementation.

## 2. Architecture and stack

Use a modular monolith: one REST backend, one browser frontend, one PostgreSQL database.

```text
Browser: React / TypeScript / React Router / TanStack Query
                       | HTTPS, JSON, bearer token
API: Spring Web -> feature services -> Spring Data JPA
                       | transactions
Database: PostgreSQL / Flyway
```

- Java 21 LTS and a compatible stable Spring Boot release; Maven Wrapper.
- Spring Security OAuth2 resource-server JWT support and a supported JWT encoder.
- Bean Validation, Springdoc OpenAPI, Actuator health, PostgreSQL JDBC driver.
- Vite, React, TypeScript, TanStack Query; npm with committed lockfile.
- CSS variables and CSS Modules; an accessible headless dialog primitive may be used if needed.
- JUnit, Spring Boot Test, Testcontainers PostgreSQL, Playwright.
- Docker Compose for local PostgreSQL; GitHub Actions for CI.
- Pin compatible stable versions at initialization; record Java, Node, and PostgreSQL versions in README and CI.

### Package structure

Backend package root: `com.stockflow`. Features: `auth`, `users`, `products`, `suppliers`, `inventory`, `orders`, `dashboard`; shared `config` and `common.error` packages.

Keep controllers, services, DTOs, entities, and repositories within their owning feature. Controllers never call repositories directly. Cross-feature writes go through services with explicit transaction ownership. Disable Open Session in View; map responses inside service read transactions using appropriate fetches/projections.

Frontend: `src/app`, `src/features/<feature>`, `src/components/ui`, `src/lib/api`, and `src/styles`. Keep the authenticated user and token in session memory; server data belongs in TanStack Query.

## 3. Authentication and authorization

- `POST /auth/login` verifies normalized email and password, checks active status, and returns `{accessToken, tokenType: "Bearer", expiresIn: 900, user}`.
- JWT default lifetime: 15 minutes. Validate signature, allowed algorithm, issuer, audience, expiration, and subject UUID.
- Configure a sufficiently random signing secret of at least 256 bits for HS256 through a secret store/environment variable; do not supply a production default.
- Load current user activity and role on protected requests so deactivation and permission changes take effect without waiting for expiration.
- Password hashing: BCrypt with a benchmarked work factor. Accept 12–72 UTF-8 bytes; never silently truncate. User creation requires an initial password and never returns it.
- No public registration, refresh token, or password-reset workflow in the MVP. Reload/expiry requires login. Logout clears token, user, and query cache; it does not revoke a copied JWT.
- Use stateless bearer authentication; no authentication cookies. CSRF protection may be disabled for this bearer-only API. Restrict CORS origins, methods, and headers.
- Enforce backend permissions from the PRD using method/request authorization. UI guards are supplementary.
- Serialize role/status mutations with a transaction-scoped PostgreSQL advisory lock before counting active admins, preventing concurrent removal of the last admin.
- Bootstrap an admin through an explicit one-time command/profile and externally supplied credentials; do not recreate or reset the account on normal startup.
- Single-instance MVP login limiter: bounded storage, initial limit 10 attempts per minute per IP and normalized account, configurable; return 429 and `Retry-After`. Trust forwarded addresses only from the configured proxy. A multi-instance deployment requires shared limiter state.

## 4. HTTP and DTO contracts

Base path `/api/v1`; JSON request/response bodies; UUID IDs; UTC ISO 8601 timestamps. Monetary API values are decimal strings such as `"1250.00"`, mapped to BigDecimal. Currency is a configured ISO 4217 code, initially LKR. Do not change currency on a populated installation without a migration strategy.

### Endpoint inventory

| Method | Path | Request/query | Success |
|---|---|---|---|
| POST | `/auth/login` | email, password | 200 token and user |
| GET | `/auth/me` | — | 200 user |
| GET | `/users` | page, size, sort | 200 user page; admin |
| POST | `/users` | email, password, role | 201 user; admin |
| PATCH | `/users/{id}` | role and/or active | 200 user; admin |
| GET | `/products` | search, supplierId, active, lowStock, page, size, sort | 200 product page |
| GET | `/products/{id}` | — | 200 product |
| POST | `/products` | sku, name, description?, unitPrice, lowStockThreshold, supplierId? | 201 product; admin |
| PATCH | `/products/{id}` | editable catalogue fields and/or active | 200 product; admin |
| GET | `/suppliers` | search, active, page, size, sort | 200 supplier page |
| GET | `/suppliers/{id}` | — | 200 supplier |
| POST | `/suppliers` | name, email?, phone?, address? | 201 supplier; admin |
| PATCH | `/suppliers/{id}` | supplier fields and/or active | 200 supplier; admin |
| POST | `/stock-movements` | productId, type, quantityDelta, reason?, reference? | 201 movement; admin |
| GET | `/products/{id}/stock-movements` | page, size, sort | 200 movement page |
| POST | `/orders` | customerName?, reference?, items | 201 order |
| GET | `/orders` | status, createdFrom, createdTo, page, size, sort | 200 order-summary page |
| GET | `/orders/{id}` | — | 200 order with items |
| POST | `/orders/{id}/confirm` | no body | 200 order |
| POST | `/orders/{id}/cancel` | no body | 200 order |
| GET | `/dashboard/summary` | — | 200 metrics |

All endpoints except login require authentication. Read permissions and order operations are available to both roles. No DELETE endpoints are needed.

### Request rules

- Trim non-password text. Store emails lowercase and SKU uppercase using locale-independent normalization. Passwords are never trimmed.
- Creation defaults: product/supplier/user active, stock zero, order DRAFT.
- PATCH is a partial JSON object: omitted means unchanged; explicit null clears only nullable fields. Reject empty patches, unknown fields, and null for required fields.
- Never accept stockQuantity, totals, actor IDs, timestamps, or order status as editable client fields.
- Manual movement type must be RECEIPT or ADJUSTMENT; sign/reason rules follow the schema.
- Reject duplicate products in an order. Maximum 100 items; quantity per item 1–1,000,000. Reject numeric overflow before persistence.
- Catalogue unit price maximum `9999999999.99`; reject more than two fractional digits. Derive line/order totals exactly with BigDecimal, not floating point.
- Manual movements on archived products return 409; system cancellation restoration remains allowed.
- Archiving is one-way in MVP: `active=false` is allowed; attempts to reactivate archived products/suppliers return 409. User reactivation remains supported.

### Pagination and sorting

Return `{content, page, size, totalElements, totalPages}`. Page defaults to 0, size to 20, maximum 100. Invalid ranges return 400. Sort syntax: `sort=createdAt,desc`; append ID as a deterministic tie-breaker.

Allowed sorts: products `name, sku, unitPrice, stockQuantity, createdAt`; suppliers `name, createdAt`; users `email, createdAt`; orders `createdAt, orderNumber, total`; movements `createdAt`. Product/supplier lists default to active records; omit status filtering only through explicit `active=all`. Recent orders default to descending createdAt. Order date filters use inclusive `createdFrom` and exclusive `createdTo`; reject inverted ranges.

### Response shapes

- User: id, email, role, active, createdAt, updatedAt; never passwordHash.
- Product: id, sku, name, description, unitPrice, stockQuantity, lowStockThreshold, supplierId, active, createdAt, updatedAt.
- Supplier: id, name, email, phone, address, active, createdAt, updatedAt.
- Movement: id, productId, type, quantityDelta, reason, reference, orderId, createdBy, createdAt.
- Order: id, orderNumber, customerName, reference, status, currency, total, actor IDs/timestamps, items. Items include productId, SKU/name snapshots, quantity, unitPriceSnapshot, lineTotal. Lists omit items.
- Dashboard: activeProductCount, lowStockCount, draftOrderCount, confirmedOrderValue, currency. Fetch recent orders and low-stock products using existing list endpoints.

### Errors and status codes

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Check the highlighted fields.",
  "fieldErrors": [{"field": "items[0].quantity", "message": "Must be positive."}],
  "traceId": "request-correlation-id"
}
```

Use 400 validation, 401 credentials/token invalid, 403 permission denied, 404 unknown resource, 409 duplicate SKU/email or business conflict, 429 login throttling, and 500 unexpected failure. Codes include `INSUFFICIENT_STOCK`, `INVALID_ORDER_STATE`, `PRODUCT_ARCHIVED`, `LAST_ADMIN_REQUIRED`, and `RESOURCE_BUSY`. Return generic 500 messages; log details with trace IDs. Success creates return 201; addressable resources include Location. Movement creation has no individual GET endpoint, so returns its representation without an unusable Location URL.

Generate OpenAPI at `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html`, covering DTOs, bearer security, pagination, and all documented error statuses.

## 5. Transactions and concurrency

Use PostgreSQL READ COMMITTED and Spring service-level transactions. Schema constraints provide additional protection, not a replacement for application validation.

### Confirm order

1. Lock order with `SELECT ... FOR UPDATE`.
2. Return current representation if already CONFIRMED; reject CANCELLED.
3. Acquire product write locks one by one in ascending UUID order.
4. Recheck active flags and all quantities after acquiring locks.
5. Deduct balances, insert negative confirmation movements, set state and actor/time, commit.
6. Any failure rolls back all items, movements, and state.

### Cancel order

Lock order first. If CANCELLED, return it without effects. A DRAFT changes status only. For CONFIRMED, lock products in the same UUID order, restore quantities (even when archived), write positive cancellation movements, retain confirmation metadata, and set cancellation actor/time.

### Other write paths

- Receipts/adjustments and catalogue changes take the same product write lock; catalogue updates must never overwrite a stale stock balance.
- Creating drafts locks selected products in UUID order while reading snapshot prices and checking activity. It reserves no stock.
- Supplier assignments acquire a shared supplier-row lock after product locks and check active status. Supplier archive acquires a write lock on that supplier only; it never locks product rows.
- Configure bounded transaction/lock timeouts; map timeout/deadlock rollback to 409 `RESOURCE_BUSY`. Return the error only after the transaction rolls back.
- Repeated confirm/cancel calls are effect-idempotent. Draft creation and manual movements are not: the client must reconcile ambiguous network outcomes before retrying.

## 6. Frontend data behaviour

- Central API client attaches bearer token, parses error envelopes, and handles 401 by clearing session/query cache and routing to login.
- Allow automatic retry only for bounded, transient read failures; do not retry 4xx or mutations automatically.
- After confirmation/cancellation invalidate orders, affected products, movements, and dashboard queries. Stock updates invalidate products, movements, and dashboard.
- Keep page/filter state in URL query parameters; reset page when filters change.
- Do not optimistically update stock, order status, or totals. Render the backend response after commit.
- Render money returned by the API with a decimal-aware formatter; previews are informational and backend totals are authoritative.

## 7. Configuration and deployment

Planned backend variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_TTL_SECONDS`, `CORS_ALLOWED_ORIGINS`, `APP_CURRENCY`, `PORT`. Bootstrap-only inputs: `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`. Frontend: `VITE_API_BASE_URL` (public value only).

- Local frontend port 5173, API port 8080, PostgreSQL port 5432. Vite may proxy `/api` to the backend.
- Deploy frontend static assets, one backend container, and managed PostgreSQL behind HTTPS. Prefer same-origin `/api` routing where supported.
- Use Flyway migration credentials at deployment; runtime database credentials have no DDL rights and no stock-movement UPDATE/DELETE privileges.
- `spring.jpa.hibernate.ddl-auto=validate`; never `update` in deployed environments.
- Actuator health exposes only health status publicly. Liveness must not depend on the database; readiness should.
- Structured logs include trace ID, route, status, and duration; no credentials/tokens/request passwords.
- CI runs Maven verify with PostgreSQL containers, frontend type checking/build, and selected browser smoke tests.
- Demo seed/reset is an explicit separate command/profile with synthetic data. Public accounts are staff-only. Back up any retained database before migrations and document restoration steps.

## 8. Verification and performance

Use the PRD critical cases plus last-admin concurrent mutations, stale catalogue edits versus stock changes, and archived-supplier assignment races. Concurrency tests require separate connections/transactions and synchronization barriers; do not rely on timing sleeps.

Measure p95 product-list/order-detail latency against 1,000 seeded products and 10 concurrent users after warmup. Initial target is under 500 ms. Record host resources, dataset, test duration, errors, and actual results. Resolve N+1 queries with projections/fetch strategies; add indexes based on query plans rather than assumed gains.
