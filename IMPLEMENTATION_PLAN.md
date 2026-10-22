# StockFlow — Implementation Plan

Status: Ready for staged implementation | Date: 2026-10-13

## 1. Inputs and working rules

Read [PRD](PRD.md), [TRD](TRD.md), [App flow](APP_FLOW.md), [Design brief](DESIGN_BRIEF.md), and [Backend schema](BACKEND_SCHEMA.md) before implementation.

Deliver small vertical slices with working UI/API/database integration. Each phase depends on the preceding phase unless explicitly stated otherwise. Checkboxes below describe future work; none indicate implementation has already occurred. Choose exact compatible dependency versions in Phase 0. Hosting/budget is a deployment decision; LKR is the initial demo currency.

## 2. Phase 0 — Repository and development foundation

### Setup progress (2026-10-13)

Repository, backend/frontend scaffolds, Compose configuration, Flyway baseline, health endpoints, error envelope/request IDs, README, and CI workflow have been created. Java 21 packaging and frontend lint/type-check/build passed locally. Docker Desktop is not installed, so database startup, runtime readiness, and PostgreSQL integration verification remain pending. GitHub Actions is configured but has not yet run. The Phase 0 gate is not complete until those checks pass.

- [ ] Initialize Git and ignore build output, environment secrets, and editor-local files.
- [ ] Create `backend/` with Java 21, Maven Wrapper, Spring Boot dependencies and package structure from TRD.
- [ ] Create `frontend/` with React, TypeScript, Vite, routing, and TanStack Query.
- [ ] Pin Node/Java/PostgreSQL versions and commit dependency lockfiles.
- [ ] Add Compose PostgreSQL service, named local volume, health check, and `.env.example` placeholders.
- [ ] Configure Flyway, JPA validation, API error envelope, request IDs, and health endpoints.
- [ ] Establish GitHub Actions backend verification and frontend type-check/build jobs.
- [ ] Create README with prerequisites and exact Windows/local startup commands.

**Gate:** Backend and frontend start locally, database connectivity/health works, CI builds both projects, and no secrets are committed.

## 3. Phase 1 — Identity and protected shell

### Implementation progress (2026-10-15)

Implemented user migration, explicit admin bootstrap, BCrypt hashing, 15-minute signed JWTs with issuer/audience/expiry checks, current-role/activity checks, login throttling, configured CORS, authentication errors, login UI, responsive protected shell, in-memory session expiry/logout, and safe internal return routing. Auth and database integration checks run against local PostgreSQL; frontend lint/types/build are verified. First-admin creation and interactive login with the owner's chosen credentials remain a manual acceptance step. Later business screens are explicit placeholders, not completed functionality.

Verification: Maven verify passed all 8 tests (6 authentication integration tests, 1 limiter unit test, 1 migration/context test). PostgreSQL connectivity and migrations are now verified, superseding the earlier Phase 0 local database blocker. GitHub CI and interactive browser acceptance are not claimed as completed.

- [ ] Implement user migration/entity/repository and one-time admin bootstrap.
- [ ] Implement login, password hashing, signed/validated 15-minute JWTs, and `/auth/me`.
- [ ] Check current account activity and role on protected requests.
- [ ] Add login throttling, configured CORS, and consistent 401/403/429 responses.
- [ ] Build login screen, in-memory session, protected routes, sidebar, account menu, and logout.
- [ ] Add session clearing and safe return-route handling on token expiry.

**Verification:** Valid/invalid login, inactive user, expired token, incorrect issuer/audience/signature, and role denial tests. Verify reload/logout behaviour manually.

**Gate:** Admin can log in; anonymous requests are rejected; staff cannot access admin operations.

## 4. Phase 2 — First catalogue vertical slice

### Implementation progress (2026-10-17)

Implemented V2 supplier/product schema, product creation/list/detail APIs, normalized unique SKUs, zero initial stock, decimal validation, bounded pagination, safe sorting, literal name/SKU search, and admin-only creation. Supplier schema/assignment validation is present for future catalogue integration; supplier CRUD/UI is still Phase 3. React now has product list, create, and detail screens with loading/empty/error states, field errors, cache refresh, query-string list state, and read-only staff access. OpenAPI JSON and Swagger UI document the implemented endpoints with bearer authentication. Backend PostgreSQL integration tests and isolated browser interaction tests are included; browser fixtures are intentionally mocked rather than represented as live end-to-end coverage.

Verification: all 19 backend tests passed against local PostgreSQL, including 11 product tests and the 8 existing identity/foundation tests. All 6 Playwright interaction tests passed using installed Chrome, including a 360px mobile layout check. Frontend lint, type-check, and production build passed. Owner acceptance against the live backend (create a product and read it back through the UI) remains the final manual gate. CI configuration now includes the isolated browser tests; a GitHub CI run is not claimed.

- [ ] Apply supplier/product migration with normalized uniqueness and numeric checks.
- [ ] Implement product POST, GET list/detail, DTO validation, and pagination.
- [ ] Implement create product form and product list/detail screens.
- [ ] Build shared inputs, buttons, error summary, table, and empty/loading states as needed.
- [ ] Ensure product creation sets zero stock and rejects stockQuantity from clients.
- [ ] Add OpenAPI documentation to the implemented endpoints.

**Verification:** Duplicate normalized SKU, invalid price/quantity fields, staff mutation denial, pagination bounds, and create/read through the UI.

**Gate:** Admin creates a product through React; staff can view the stored result through the protected REST API and UI.

## 5. Phase 3 — Complete catalogue and accounts

### Implementation progress (2026-10-21)

Implemented supplier create/read/edit/archive with paginated search, nullable contact-field clearing, linked product views, and one-way archiving. Product PATCH preserves omitted fields, accepts null only for description/supplier, rejects immutable/unknown fields, locks the product row before updating, and never changes stock. Active/archived/all, supplier, and low-stock filters are available. New/changed supplier assignments take shared supplier-row locks and reject archived suppliers; existing archived links remain visible and can be retained or cleared.

Admin account list/create/access-update APIs and native modal UI are implemented. Passwords are BCrypt-hashed and never returned. Role/activity updates serialize through the same transaction-scoped advisory lock as bootstrap and enforce at least one active admin. Self-access changes refresh the current session and redirect/sign out where appropriate. PostgreSQL tests use a unique disposable schema for last-admin/concurrency scenarios, never the owner's account data.

Verification: 25 backend tests and 11 mocked-API browser interaction tests passed during implementation. Live owner acceptance remains a manual step. Inventory and order processing remain subsequent phases.

- [x] Implement suppliers create/read/update/archive and active-only assignment rules.
- [x] Implement product PATCH with explicit nullable/omitted field handling, filters, sorting, and one-way archival.
- [x] Add catalogue update locking so future stock updates cannot be overwritten.
- [x] Build supplier pages, edit forms, archive dialogs, and linked-product list.
- [x] Implement admin account list/create/update with last-admin transaction locking.
- [x] Build user-management dialogs and access refresh after self-role/status changes.

**Verification:** Archive history remains visible, archived suppliers cannot be newly assigned, unknown PATCH fields fail, concurrent last-admin mutations cannot remove all admins.

**Gate:** Both roles have correct navigation/permissions; catalogue and account workflows handle validation and archive states.

## 6. Phase 4 — Inventory ledger

### Implementation progress (2026-10-21)

Added V3 order schema (no order APIs yet) and V4 immutable stock ledger, with an opening-balance safeguard against silently changing pre-existing nonzero stock. Implemented admin-only receipt/adjustment APIs with current actor checks, product write locks, nonnegative/overflow validation, atomic balance/movement writes, and bounded lock timeouts. Read-only history supports stable pagination and archived products. PostgreSQL rejects movement UPDATE/DELETE in addition to the immutable JPA mapping and restricted repository surface.

Product details now include receipt/adjustment dialogs, server-refreshed stock, signed history, actor/reference information, pagination/sorting, and role/archive-aware controls. Mutation retries are disabled; unknown outcomes block resubmission and direct the user to inspect history. Catalogue, product, movement, and future dashboard queries are invalidated after success. Read failures retain product data and the reconciliation dialog rather than discarding entered values.

Verification: all 38 backend tests passed against PostgreSQL, including 13 inventory tests covering concurrent updates/removals, rollback after a flushed balance update, immutability, validation, permissions, and reconciliation. All 20 mocked-API browser interaction tests passed in installed Chrome, including uncertain outcomes with failed refreshes. Frontend lint, type-check, and production build passed. Owner acceptance against the live application and a GitHub CI run remain separate checks.

- [x] Apply order schema migration followed by stock-movement migration in planned order; order endpoints are implemented in Phase 5.
- [x] Implement receipt and adjustment services with product row locks and atomic balance/movement writes.
- [x] Implement movement-history pagination and DTOs.
- [x] Build receipt/adjustment dialogs and movement table with signed quantities.
- [x] Configure mutation error handling without automatic retries and invalidate affected query caches.
- [x] Document ambiguity recovery for timed-out manual writes.

**Verification:** Receipt success, required adjustment reason, invalid signs, negative-stock rejection, archived-product rejection, complete rollback on failure, and balance-to-ledger reconciliation.

**Gate:** Stock changes only through authorized inventory operations and every successful change has one immutable movement.

## 7. Phase 5 — Draft orders and snapshot pricing

- [x] Implement order-number generation using a database sequence.
- [x] Implement draft creation with active-product checks, unique items, server-side prices, exact totals, and immutable snapshots.
- [x] Implement order list/detail with status/date filters and actor metadata.
- [x] Build product picker, order-item form, total preview, order list, and detail timeline.
- [x] Handle creation failures without losing user input; explain no stock reservation.

### Implementation progress (2026-10-21)

Added authenticated draft creation/read APIs for both roles using existing V3 tables. Creation locks active products in consistent UUID order, snapshots prices/SKU/name/currency, calculates exact totals, and atomically inserts order/items without inventory effects. Sequence-based numbers expand past eight digits. Item snapshots have an immutable entity mapping and no editing API. Summary pages support status, inclusive/exclusive date bounds, validated sorting, and pagination; detail includes items and audit metadata.

React includes a searchable paginated active-product picker, unique-item quantities/removal, exact integer-cent estimated totals, saved authoritative details/timeline, and order list filters. Failed creation retains inputs; uncertain outcomes disable retries/resubmission and link to recent orders for reconciliation. Confirmation, cancellation, and stock reservation/deduction remain deliberately unavailable until Phase 6. README documents setup, APIs, currency configuration, and uncertainty recovery.

Verification: all **50 backend tests** passed against PostgreSQL, including 12 order tests for validation, snapshot preservation, atomic rollback, 100-item exact totals, sequence expansion/concurrency, date boundaries, and zero inventory effects. All **23 mocked-API browser tests** passed in installed Chrome, including draft creation with authoritative server prices, failure/input preservation, and uncertain committed-write reconciliation. Frontend lint, type-check, and production build passed. Live owner acceptance and GitHub CI execution remain separate, unverified checks.

**Verification:** Reject client totals/prices, duplicate products, empty/oversized orders, archived products, and invalid quantities. Confirm stock remains unchanged and catalogue price edits do not change saved drafts.

**Gate:** A user creates a draft from React and sees its authoritative saved prices/total with zero inventory effects.

## 8. Phase 6 — Transactional order lifecycle

- [ ] Implement confirmation with order lock then sorted product locks.
- [ ] Implement cancellation of drafts and confirmed orders; preserve audit metadata.
- [ ] Enforce effect-idempotent repeat confirmation/cancellation and unique movement protection.
- [ ] Handle product archival and insufficient stock after locks are acquired.
- [ ] Translate lock/deadlock timeouts after rollback into retryable resource conflicts.
- [ ] Build explicit confirm/cancel dialogs and refresh orders/products/history/dashboard after success.

**Required PostgreSQL integration tests:**

1. Successful multi-item confirmation deducts exactly the required quantities.
2. Insufficient stock in one item rolls back every item and movement.
3. Two independent orders competing for one unit yield one success and one stock conflict.
4. Concurrent repeat confirmation produces one deduction per product.
5. Repeated/concurrent cancellation restores stock exactly once.
6. Confirm versus cancel race ends with a valid state and consistent ledger.
7. Cancellation restores archived-product stock.
8. Manual adjustment/catalogue update versus confirmation cannot lose stock changes.

Use independent connections and synchronization barriers, with bounded test timeouts. Do not substitute H2 for PostgreSQL locking tests or rely on sleeps to create races.

**Gate:** All lifecycle and concurrency tests pass; final balances match the movement ledger; errors never leave partial changes.

## 9. Phase 7 — Dashboard and UI completion

- [ ] Implement summary metrics with correct active/confirmed-state definitions.
- [ ] Compose recent orders and low-stock lists from existing endpoints.
- [ ] Complete responsive shell, mobile tables/forms, status badges, and clear navigation.
- [ ] Add accessible focus handling, labelled controls, keyboard dialogs, and async announcements.
- [ ] Complete empty/search-empty/loading/error/permission states and dirty-form navigation warnings.
- [ ] Verify money and timestamp formatting and document token reload behaviour.

**Verification:** Browser smoke flow for login -> product receipt (admin setup) -> draft -> confirmation -> dashboard and history refresh. Check staff access, 360/768/1440px layouts, keyboard navigation, 200% zoom, and rendered contrast.

**Gate:** Core journeys are usable by both roles across viewport sizes and the dashboard accurately reflects committed operations.

## 10. Phase 8 — Deployment and portfolio delivery

- [ ] Finalize OpenAPI schemas/examples/errors and verify Swagger can call authenticated endpoints.
- [ ] Add backend production Dockerfile and frontend production build configuration.
- [ ] Choose hosting, HTTPS routing, secret configuration, runtime/migration DB roles, and readiness checks.
- [ ] Run migrations as a release step and deploy frontend/backend/database.
- [ ] Add explicit synthetic seed/reset process and a public staff-only demo account.
- [ ] Run deployed smoke checks: login, product reads, order lifecycle, expiry, and API documentation.
- [ ] Benchmark documented dataset/concurrency against the PRD performance target; record actual measurements.
- [ ] Complete README with setup/test commands, ER diagram, architecture, demo/API links, screenshots, limitations, and backup/reset instructions.
- [ ] Record key tradeoffs: modular monolith, bearer-token lifecycle, immutable order snapshots, and pessimistic row locking.

**Gate:** A new developer can reproduce local setup, CI passes, the public demo works, and portfolio claims match verified features.

## 11. Final acceptance matrix

| Requirement | Primary evidence |
|---|---|
| REST API design | OpenAPI, consistent DTOs/errors, Swagger walkthrough |
| Role authorization | Backend permission tests and role-specific UI |
| Reliable inventory | Ledger reconciliation and rollback tests |
| No overselling | Real PostgreSQL concurrent-confirmation test |
| No duplicate restoration | Concurrent/repeated cancellation tests |
| Snapshot pricing | Catalogue price-change test |
| Usable frontend | Browser smoke, responsive and keyboard checks |
| Delivery quality | CI, deployed demo, setup documentation |

## 12. Scope control

Complete the gates above before considering refresh tokens, password reset, multi-warehouse stock, purchases, payments, analytics charts, or AI forecasting. Log discovered requirements and update the governing documents when decisions change. Do not claim an unfinished feature as completed in the README or CV.
