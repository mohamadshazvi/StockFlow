# StockFlow

Inventory and order management portfolio application built with React, TypeScript, Spring Boot, and PostgreSQL.

## Current status

Phase 7 adds a real operations dashboard, recent orders, paginated low-stock products, responsive layouts, keyboard navigation, and router-level unsaved-change warnings. Transactional orders, inventory, catalogue/account management, authentication, and OpenAPI remain available. Deployment and final portfolio delivery remain Phase 8 work. This is a development application, not a production-ready release.

## Prerequisites

- Java 21 JDK (Maven Wrapper is included; standalone Maven is unnecessary).
- Node.js 24.20.0 and npm (dependency lockfile committed with project).
- Git.
- Docker Desktop running Linux containers for PostgreSQL.

Versions: Spring Boot 4.0.8, PostgreSQL 17.6, Java target 21; frontend resolved versions are recorded in `frontend/package-lock.json`.

## Windows setup

Open PowerShell in `G:\Projects\StockFlow`.

### 1. Start PostgreSQL

```powershell
Copy-Item .env.example .env
# Edit .env and replace POSTGRES_PASSWORD before proceeding.
docker compose up -d --wait
```

The database port is bound to localhost. Data persists in a named volume. Changing the password in `.env` after initial database creation does not change the existing database password.

### 2. Start the backend in another terminal

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DB_URL = 'jdbc:postgresql://localhost:5432/stockflow'
$env:DB_USERNAME = 'stockflow'
$env:DB_PASSWORD = '<the password you set in .env>'
# Generate a random signing key once in this terminal. Do not share or commit it.
$keyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
$rng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($keyBytes)
Set-Location backend
.\mvnw.cmd spring-boot:run
```

The backend does not automatically load the root `.env`; provide these environment variables in every backend/test terminal. Adjust DB_URL if you changed the database port/name. Do not commit real credentials.

- API origin: http://localhost:8080
- Readiness (includes database): http://localhost:8080/actuator/health/readiness
- Liveness: http://localhost:8080/actuator/health/liveness
- Health returns status only; readiness should return `{"status":"UP"}` after startup.
- Flyway applies V0–V4 for the baseline, users, catalogue, future order tables, and inventory ledger.

### 3. Start the frontend in another terminal

```powershell
Set-Location frontend
npm ci
npm run dev
```

Open http://localhost:5173. The frontend now shows the login screen. Vite proxies `/api` and `/actuator` to port 8080. Optional `VITE_API_BASE_URL` is the backend origin, without `/api/v1`. Check database readiness directly at the readiness URL above.

## First admin account (one time)

Stop the backend with Ctrl+C first. In the same backend terminal, retain DB variables and JWT_SECRET from above. This bootstrap profile refuses to run if any accounts already exist; it never overwrites an account or resets passwords.

```powershell
$env:BOOTSTRAP_ADMIN_EMAIL = Read-Host 'Your admin email'
$securePassword = Read-Host 'Choose an admin password (12–72 UTF-8 bytes)' -AsSecureString
$env:BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=bootstrap"
```

Wait for `Initial admin account created` and check that no subsequent startup error appears. Stop it with Ctrl+C. Remove bootstrap credentials and start normally:

```powershell
Remove-Item Env:BOOTSTRAP_ADMIN_EMAIL, Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
Remove-Variable securePassword -ErrorAction SilentlyContinue
.\mvnw.cmd spring-boot:run
```

Sign in with the email/password you chose. No default password is supplied. Refreshing the page, signing out, or token expiration requires login again; tokens are not stored in localStorage/cookies. A new JWT_SECRET invalidates earlier tokens, so keep it stable during a session. For persistent configuration, use your IDE's private environment settings or a secret manager, never committed source files.

Authentication endpoints:

- `POST /api/v1/auth/login` with JSON `{ "email": "...", "password": "..." }` returns accessToken, tokenType, expiresIn, and safe user fields.
- `GET /api/v1/auth/me` requires `Authorization: Bearer <accessToken>`.
- Public registration, refresh tokens, and password recovery are not implemented. Admins can now create accounts and update their role/activity from Users.
- The login limiter is single-instance, 10 attempts per minute per IP and normalized account. It does not trust forwarded IP headers; configure a trusted reverse proxy strategy before deployment.

## Verification

With PostgreSQL running and DB environment variables set:

```powershell
# In backend/, with JAVA_HOME set to Java 21:
.\mvnw.cmd verify
```

Integration tests start the Spring context, verify Flyway migrations, and check authentication, current-role/activity enforcement, token validation, staff restrictions, and throttling. They create and remove synthetic test accounts. Prefer a separate disposable PostgreSQL test database when using non-demo data. Tests use an explicit test-only signing key. Without Docker/database, compilation and packaging can still be checked with `./mvnw.cmd -DskipTests package`; this does not validate database connectivity.

```powershell
# In frontend/:
npm run lint
npm run typecheck
npm run build
```

GitHub Actions runs the backend against a PostgreSQL service and checks frontend lint/types/build. CI has to run on GitHub before its passing status can be claimed.

## Project structure

```text
backend/                 Spring Boot API and Maven Wrapper
frontend/                React application and npm lockfile
compose.yaml             Local PostgreSQL
.github/workflows/ci.yml  Build and verification jobs
```

## Planning documents

- [Product requirements](PRD.md)
- [Technical requirements](TRD.md)
- [Application flows](APP_FLOW.md)
- [Design brief](DESIGN_BRIEF.md)
- [Backend schema](BACKEND_SCHEMA.md)
- [Implementation plan](IMPLEMENTATION_PLAN.md)

Next: Phase 8 covers deployment, demo data, performance measurements, and final portfolio documentation.

## Supplier, catalogue, and account management (Phase 3)

Restart the backend normally with your existing DB/JWT environment variables and restart the frontend if needed. Do not rerun bootstrap or delete the database. Phase 3 reuses the V1/V2 tables; no new migration or account reset is required.

Try these workflows as an admin:

1. **Suppliers → Add supplier:** create a supplier and optionally add contact details.
2. **Products → product → Edit product:** change catalogue fields or assign a supplier. Stock is read-only.
3. On a supplier/product detail page, **Archive** after reviewing the confirmation. Archiving cannot be undone; records/history are retained.
4. Filter products by active/archived/all, supplier, and low stock. Low-stock filtering always includes active products only.
5. **Users → Add user:** enter an initial password privately and choose Staff/Admin. Use **Edit access** to change role or active status.

The last active admin cannot be demoted or deactivated, even by simultaneous requests. Deactivating your own account signs you out; demoting yourself refreshes permissions and leaves the admin page. There is no password-reset/email-change feature yet. Keep at least one accessible admin account and share new-account initial passwords securely.

| Method | Endpoint | Permission |
|---|---|---|
| PATCH | `/api/v1/products/{id}` | Admin |
| GET | `/api/v1/suppliers` and `/api/v1/suppliers/{id}` | Signed in |
| POST | `/api/v1/suppliers` | Admin |
| PATCH | `/api/v1/suppliers/{id}` | Admin |
| GET / POST | `/api/v1/users` | Admin |
| PATCH | `/api/v1/users/{id}` | Admin |

PATCH uses a partial JSON object. Omitted fields stay unchanged. Explicit null clears only product `description`/`supplierId` or supplier `email`/`phone`/`address`. Required fields cannot be null. Empty/unknown/immutable fields are rejected. User PATCH accepts only `role` and `active`; passwords are never returned by user endpoints.

Catalogue changes lock the product row before loading/updating it. New supplier assignments lock and verify the supplier; archived suppliers cannot be newly assigned. Existing links to archived suppliers may be kept or cleared. Stock is changed through the Phase 4 inventory workflow below, never through product PATCH.

Local Phase 3 verification: 25 backend tests (including simultaneous admin demotion) and 11 mocked-API browser interaction tests. Last-admin and concurrency tests create/drop their own unique `phase3_test_*` PostgreSQL schema, so the test database account must have schema-creation permission. Prefer a disposable development/test database; these tests do not modify the owner's real account. Full live-browser acceptance and a passing GitHub CI run remain separate verification steps.

## Dashboard and usability (Phase 7)

Restart the backend and frontend normally with your existing DB/JWT environment variables. No new migration, account bootstrap, or database reset is required. Sign in to open **Dashboard**.

### Metrics and lists

`GET /api/v1/dashboard/summary` is available to both admin and staff:

```json
{
  "activeProductCount": 12,
  "lowStockCount": 3,
  "draftOrderCount": 2,
  "confirmedOrderValue": "1350.00",
  "currency": "LKR"
}
```

- **Active products:** active catalogue records only; archived products are excluded.
- **Low-stock products:** active products with stock **at or below** their individual threshold, including zero stock.
- **Draft orders:** currently DRAFT orders; drafts never reserve stock.
- **Confirmed order value:** exact sum of currently CONFIRMED order snapshots, **not collected revenue**. Cancelled orders are excluded, and later catalogue price edits/product archival do not reprice or remove confirmed orders.
- All four aggregates come from one SQL statement so the summary observes one committed database snapshot. Unexpected confirmed-order currencies return **409 `CURRENCY_MISMATCH`** rather than silently adding unlike amounts; correct the business currency configuration, not the saved history.
- Recent orders reuse `/orders?page=0&size=5&sort=createdAt,desc`; low-stock products reuse `/products?active=true&lowStock=true&size=5&sort=stockQuantity,asc` with pagination. Low-stock page state is preserved as `lowPage` in the dashboard URL.
- Summary/list requests have separate loading/error/retry states. **Refresh dashboard** reloads all three. Metrics and lists are separate committed reads and can briefly differ during simultaneous updates; refresh to reconcile.
- Stock/order/product mutations invalidate the relevant summary/list caches. No optimistic stock, status, or value changes are shown.

### Navigation, formatting, and accessibility

- Mobile menu exposes its expanded state, closes on Escape, and restores keyboard focus. A skip link and route-level main-content focus support keyboard navigation. Scrollable tables are keyboard reachable on the dashboard, catalogue, and order list.
- Native modal dialogs contain Tab/Shift+Tab focus, support Escape when not saving, block pending dismissal, and restore focus to their trigger. Loading/results use status announcements; failures use alerts.
- Product/supplier/draft forms warn on in-app navigation, including browser Back/Forward, and on reload/tab close. Inventory/account dialogs also warn on route/reload changes and confirm local dismissal. Successful saves navigate without a discard prompt. Explicit sign-out warns about unsaved inputs; forced session invalidation is never blocked by a dirty form.
- The router now uses React Router's data-router provider to support proper navigation blockers. Passwords/tokens and unsaved form values are not persisted in browser storage.
- Monetary summary/list values are grouped directly from decimal strings without floating-point conversion. API timestamps are UTC; dashboard/order-list times display the browser's local timezone and timezone label. Saved order prices/currency remain authoritative; catalogue forms retain the default LKR convention.
- Order date filters apply on form submission rather than issuing invalid requests for every partially typed timestamp. Status/date/page/sort filters remain in the URL.
- Tokens remain in memory only: **reloading requires signing in again**, and expiry or a 401 clears the session and cached data. The new UI does not add refresh tokens or persistent login.

### Phase 7 verification

- **73 backend tests** passed against PostgreSQL, including seven dashboard tests for zero values, thresholds, lifecycle changes, archival, exact large totals, role access, and currency mismatch. Dashboard tests run in an isolated disposable `dashboard_test_*` schema, preserving owner data.
- **42 browser interaction tests** passed using installed Chrome and **mocked API responses**. The new smoke journey exercises login → receipt → draft → confirmation → dashboard/history → cancellation, plus staff restrictions, failed reads/retry, unsaved navigation/sign-out, and forced invalidation.
- Dashboard, receipt-dialog bounds, and order forms were checked at **360/768/1440px**. Dashboard screenshots are generated in ignored `frontend/test-results/` directories. Keyboard menu/skip-link/modal focus checks, **2× CSS zoom reflow**, and sampled rendered-text contrast (at least 4.5:1) passed.
- Frontend lint, TypeScript checking, and production build passed. These checks are **not** a comprehensive WCAG audit, assistive-technology certification, or a native browser-zoom test. Full manual screen-reader/zoom acceptance, live frontend-to-database acceptance, and a GitHub CI run for these changes remain unverified.

## Draft orders (Phase 5)

Restart the backend and frontend normally, keeping your existing DB/JWT environment variables. Phase 5 uses the V3 tables already introduced in Phase 4; it requires no database reset, account bootstrap, or new migration.

1. Sign in as admin **or staff**, then open **Orders → Create draft order**.
2. Search active products and add each product once. Set quantities from 1 to 1000000 (at most 100 distinct products).
3. Optionally enter customer name/reference, then create the draft. Zero stock is allowed: **drafts neither reserve nor deduct stock**.
4. The detail screen shows authoritative saved prices, line totals, total/currency, actor, and creation timeline. Later catalogue price/name changes or archival do not rewrite these snapshots.
5. Browse Orders with status/date filters, sorting, and pagination. Saved items/prices are read-only; use Phase 6 confirmation/cancellation actions below to change lifecycle state and stock.

The form uses exact integer-cent arithmetic for its estimate. The server ignores that estimate and reads current product prices under consistently ordered product locks. Only server-generated monetary decimal strings are authoritative. Invalid/business-conflict responses preserve form inputs. Creation is **not idempotent**: after a lost response or server error, automatic retries and resubmission are disabled; inspect recent orders using your reference before creating another draft. A reference helps reconciliation but is not a unique idempotency key.

### Draft-order REST API

`POST /api/v1/orders` (admin or staff):

```json
{
  "customerName": "Example customer",
  "reference": "CUSTOMER-REF-001",
  "items": [
    { "productId": "<active product UUID>", "quantity": 3 }
  ]
}
```

Success returns **201**, a usable `/api/v1/orders/{id}` Location header, and saved order/items. Customer/reference are optional, trimmed, and limited to 160 characters. Duplicate products, invalid quantities, empty/oversized orders, unknown fields, and client-supplied prices/totals/currency/status/actors are rejected. Missing products return 404; archived products and lock conflicts return 409. An item insert failure rolls back the entire order. Sequence gaps are expected after rollback; `SF-00000001` numbers expand beyond eight digits instead of truncating.

- `GET /api/v1/orders/{id}`: saved order with product/SKU/name/price snapshots, quantities, exact totals, status, and actor/timestamp metadata.
- `GET /api/v1/orders`: summary page (no item collection), `page=0`, `size=20` (1–100), `sort=createdAt,desc`; sortable fields are `createdAt`, `orderNumber`, and `total` with `asc`/`desc`, plus an ID tie-breaker.
- Optional filters: `status=DRAFT|CONFIRMED|CANCELLED`, inclusive `createdFrom` and exclusive `createdTo` ISO timestamps, for example `2026-10-21T00:00:00Z`. Omit status for all. Invalid or reversed date ranges return 400.

`APP_CURRENCY` optionally configures the backend currency (default **LKR**). Use an ISO currency with two fractional digits. Each new order snapshots the configured currency; changing this setting does not convert catalogue amounts or rewrite existing orders. Keep the catalogue in a single currency and do not change it casually.

Order integration tests use an isolated disposable `orders_test_*` schema, preserving existing accounts and business data. Browser interaction tests mock APIs; they are not live end-to-end coverage.

Local Phase 5 verification: **50 backend tests**, **23 mocked-API browser tests** in installed Chrome, and frontend lint/type-check/build passed. Live owner acceptance and GitHub CI execution have not been verified.

## Order confirmation and cancellation (Phase 6)

Restart the backend and frontend normally with your existing environment variables. No new migration, database reset, or admin bootstrap is needed: the existing V3/V4 schema supports these workflows. Your accounts and business data are retained.

### Try the lifecycle

1. As an admin, receive 10 units of an active product from its detail page.
2. As either admin or staff, create a draft for 3 units in **Orders**. Stock stays at 10.
3. Open the draft and select **Confirm order**. Review the dialog, then **Confirm and deduct stock**. Stock becomes 7; product history includes an `ORDER_CONFIRMATION` movement of -3 with the order number/ID, actor, and time.
4. Select **Cancel order**, review the restoration warning, then **Cancel order permanently**. Stock returns to 10, a +3 `ORDER_CANCELLATION` movement appears, and the timeline keeps both confirmation and cancellation actors/times.
5. Cancelling an unconfirmed draft has no stock effects. Cancelled orders cannot be confirmed again. Draft/item editing is not supported; create a new order if needed.

### Lifecycle REST API

- `POST /api/v1/orders/{id}/confirm` (admin or staff, **no body**): returns **200** with the committed order. A draft confirms only when every product is active and every quantity is available. One negative movement is recorded per item. A previously confirmed order returns its current representation without another deduction or audit changes.
- `POST /api/v1/orders/{id}/cancel` (admin or staff, **no body**): returns **200** with the committed order. Drafts change state only. Confirmed orders restore every saved quantity, **including archived products**, with one positive movement per item. Confirmation metadata is retained. Already-cancelled orders return unchanged without additional effects.
- Cancelled → confirmed returns **409 `INVALID_ORDER_STATE`**. Archived-product confirmation returns **409 `PRODUCT_ARCHIVED`**; insufficient stock returns **409 `INSUFFICIENT_STOCK`**. Restoration beyond integer stock bounds returns **409 `STOCK_LIMIT_EXCEEDED`** without cancelling or partially restoring anything; an admin must reconcile stock before retrying.
- Invalid/nonempty bodies return 400; missing orders return 404; missing/inactive authentication returns 401. Actor/time are server-owned, never request fields.

The transaction locks the order **first**, then all affected products individually in ascending UUID order, using the same product locks as manual inventory and catalogue edits. It checks state, activity, quantities, and overflow after locking, then commits balances, immutable movements, and state/audit metadata together. Failures roll back every effect. A unique `(order_id, product_id, type)` movement index adds database protection against duplicate effects. Competing orders cannot oversell.

Lock waits are bounded (5-second lock timeout inside a 10-second transaction). Lock/deadlock/query/transaction timeout failures are translated **after rollback** to **409 `RESOURCE_BUSY`**; refresh and explicitly retry rather than automatically resending writes.

### Lost responses and UI refresh

Confirmation/cancellation are **effect-idempotent**, unlike draft creation or manual receipts: repeated requests cannot deduct/restore stock twice. A confirmation after a later cancellation still returns a state conflict, not a new confirmation. The first successful actors/times are preserved.

The UI uses explicit action dialogs, blocks duplicate/pending submissions, and never optimistically changes stock/status or automatically retries mutations. Successful responses update the detail/timeline and invalidate order lists, products, stock history, and future dashboard queries. Product names on an order link to current balances/history. On conflicts or uncertain responses, action controls remain paused until an explicit order refresh succeeds. If refresh also fails, the recovery dialog remains available; do not assume a lost response means the operation failed.

Local Phase 6 verification: **66 backend tests** passed against PostgreSQL, including 16 lifecycle tests for multi-item rollback, competing orders, repeated/concurrent transitions, confirm/cancel races, archived restoration, overflow, catalogue/adjustment races, duplicate movement protection, and real lock-timeout/deadlock recovery. **31 mocked-API browser tests** passed in installed Chrome, including staff confirmation/cancellation, timeline and stock-history refresh, conflicts, lost responses with failed follow-up reads, and pending submission guards. Frontend lint/type-check/build passed. Live owner acceptance and GitHub CI execution remain unverified.

## Inventory ledger (Phase 4)

Restart the backend normally with your existing database/JWT environment variables. Flyway applies `V3__create_orders.sql` and `V4__create_stock_movements.sql` automatically. V3 introduced the order tables/sequence in Phase 4; draft-order endpoints are now enabled in Phase 5. Your existing accounts, products, and suppliers are retained. Do not rerun admin bootstrap or reset the database.

**Opening-balance safeguard:** before enabling the ledger, V4 requires existing product balances to be zero (as created by earlier app phases). If stock was changed out-of-band with SQL, migration stops rather than erasing balances or inventing historical actors. Ask for a reviewed opening-balance migration; do not edit applied Flyway files, reset stock, or delete data to bypass the safeguard.

### Try it

1. Sign in as an admin and open **Products → a product**.
2. Select **Receive stock**, enter a positive quantity such as 10, and optionally add a delivery reference.
3. Select **Adjust stock**, enter a signed quantity such as -2 and a reason such as “Damaged items”.
4. The resulting balance should be 8 for a previously empty product. History shows separate +10 and -2 entries with actor IDs, timestamps, reasons, and references.
5. Staff can read paginated history but cannot see/use manual stock actions. Archived products retain their history and reject manual stock changes.

Preview balances are estimates only. The server locks and validates the latest product balance. Existing catalogue edits take the same lock so they cannot overwrite a concurrent receipt/adjustment.

### REST API

`POST /api/v1/stock-movements` (admin):

```json
{
  "productId": "<product UUID>",
  "type": "RECEIPT",
  "quantityDelta": 10,
  "reference": "DELIVERY-001"
}
```

For an adjustment use `"type": "ADJUSTMENT"`, a nonzero signed `quantityDelta`, and a required nonblank `reason`. Reason is limited to 500 characters; reference to 160. Quantities must be integers and resulting stock must be between 0 and 2147483647. The server derives actor/time and rejects client-supplied actor, order ID, stock balance, and unknown fields. Order-generated movement types are reserved for later workflows.

A successful request returns **201** with the recorded movement. There is no individual movement GET endpoint and no unusable Location header. Stock and movement are committed in the same transaction. Invalid input returns 400; insufficient stock, overflow, archived products, and lock conflicts return 409; role/account/resource errors use 403/401/404.

`GET /api/v1/products/{id}/stock-movements?page=0&size=20&sort=createdAt,desc` (signed in) returns `content`, `page`, `size`, `totalElements`, and `totalPages`. Size is 1–100; only `createdAt,asc` or `createdAt,desc` sorting is accepted, with ID as a stable tie-breaker. Archived products remain readable.

### No automatic retries after ambiguous writes

This POST is **not idempotent**. After a lost response, the transaction might already have committed. The frontend disables duplicate/pending submissions and does not automatically retry mutations. On network/5xx uncertainty it keeps the form/instructions visible, blocks resubmission, and asks you to close it and inspect history/current balance before entering another movement. Use a meaningful reference to help reconcile the outcome. Business conflicts preserve entered values and refresh availability so you can correct the request explicitly.

### Ledger integrity and deployment notes

- Product balance and movement inserts share a transaction; failed ledger insertion rolls back a flushed balance update.
- Product write locks serialize receipts, adjustments, and catalogue edits; negative/overflow balances are rejected after locking.
- Movement records have no update/delete API or repository operations. JPA marks them immutable, and PostgreSQL rejects direct UPDATE/DELETE with an append-only trigger. Correct mistakes using compensating adjustments.
- Do not manually update product stock with SQL. Balance-to-ledger reconciliation is verified by tests, not an invitation to bypass application workflows.
- Local Compose still uses the development table owner. Production must use separate migration/runtime credentials and grant runtime only SELECT/INSERT on stock_movements (no UPDATE/DELETE/TRUNCATE/DDL). No database roles or existing credentials are silently changed by this phase.
- Inventory integration tests use their own disposable `inventory_test_*` schema and committed transactions; they test real PostgreSQL locking and rollbacks without modifying the owner's account/product data. Schema/function/trigger creation permission is required in the test database. Browser tests mock API responses independently.

Local Phase 4 verification: **38 backend tests**, **20 browser interaction tests** (mocked API, installed Chrome), and frontend lint/type-check/build passed. This includes a forced ledger-insert failure after flushing the stock balance, competing removals, concurrent receipts/catalogue changes, and recovery from an uncertain write when follow-up reads also fail. Live owner acceptance and GitHub CI execution are not claimed as completed.

## Product catalogue usage (Phases 2–3)

Restart the backend after updating so Flyway applies `V2__create_catalogue.sql`. Use your existing database and account; do not run admin bootstrap again. Keep DB variables and JWT_SECRET configured as above. Restart the frontend if it is not already running; use `npm ci` after dependency changes.

1. Sign in as your admin.
2. Open **Products → Add product**.
3. Enter a unique SKU, name, price in LKR, and nonnegative low-stock threshold.
4. Submit and check the saved product details. New stock is always zero.
5. Return to Products to search by name/SKU, sort, and paginate.

Staff can read the catalogue/history but cannot create/edit/archive products or manually change stock, including by directly calling the API. Supplier assignment, catalogue editing, one-way archival, filters, and inventory entry are available to admins.

| Method | Endpoint | Permission |
|---|---|---|
| POST | `/api/v1/products` | Admin |
| GET | `/api/v1/products?search=rice&page=0&size=20&sort=name,asc` | Signed in |
| GET | `/api/v1/products/{id}` | Signed in |

Creation body:

```json
{
  "sku": "RICE-001",
  "name": "White Rice 1kg",
  "description": "Demo catalogue item",
  "unitPrice": "450.00",
  "lowStockThreshold": 5
}
```

SKU is trimmed/uppercased. Price is a nonnegative decimal with at most 10 whole digits and two decimal places, returned as a two-decimal string. Creation rejects unknown/immutable fields including stockQuantity; duplicate SKU returns 409. Lists return `content`, `page`, `size`, `totalElements`, and `totalPages`. Page size is 1–100. Product lists show active records by default; use `active=false` or `active=all` for archived records.

### Interactive REST API documentation

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

Documentation is publicly readable in local development. Business endpoints remain protected. Execute login in Swagger, copy its accessToken, select **Authorize**, and paste the token without adding `Bearer` (Swagger adds it). Never publish screenshots containing tokens or passwords.

### Browser interaction checks

```powershell
# In frontend/:
npm ci
npx playwright install chromium --only-shell
npm run test:ui
```

Playwright starts a separate Vite server on port 5175. These tests deliberately mock API responses to verify admin creation, staff UI restrictions, validation/conflicts, search/pagination, expiry handling, and mobile layout. They do not require your admin credentials. Real persistence and authorization are checked separately by PostgreSQL integration tests in Maven. Full live-browser acceptance against the owner's running backend remains a manual check.

If Chrome is already installed, you can avoid downloading another browser: set `$env:PLAYWRIGHT_CHANNEL = 'chrome'` before `npm run test:ui`. The default CI configuration uses Playwright's headless Chromium.

Local Phase 2 verification: 19 backend tests and 6 mocked-API browser tests passed; frontend lint, types, and build passed. Browser tests were run with installed Chrome. Dirty product forms warn on page reload/close, Cancel, and internal-link navigation; SPA browser-history Back/Forward blocking is not yet implemented.

## Stopping local services

Stop frontend/backend with Ctrl+C. Run `docker compose stop` from the project root to stop PostgreSQL while retaining data. Avoid removing database volumes unless you intend to erase local data.
