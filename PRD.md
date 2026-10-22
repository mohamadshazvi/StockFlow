# StockFlow — Product Requirements Document

**Status:** Draft for implementation  
**Created:** 2026-10-13  
**Project type:** Full-stack software engineering portfolio project

## 1. Overview

StockFlow is a web application that helps a small business manage products, suppliers, inventory, and customer orders. A React frontend communicates with a Java Spring Boot REST API backed by PostgreSQL.

The project should demonstrate practical backend engineering: clear API design, relational data modelling, authentication, authorization, database transactions, concurrency control, automated testing, and deployment.

## 2. Problem and goals

Small businesses need a reliable way to track stock and process orders without inconsistent quantities or missing transaction history.

### Product goals

- Maintain an accurate product catalogue and stock balance.
- Record every stock change with an actor, reason, and timestamp.
- Process orders without overselling, including simultaneous requests.
- Restrict sensitive operations using admin and staff roles.
- Make low-stock items and recent activity easy to identify.

### Portfolio goals

- Deliver a working, deployed application with a documented REST API.
- Demonstrate Java and Spring Boot through business workflows beyond basic CRUD.
- Provide reproducible setup instructions and meaningful automated tests.
- Explain important design decisions in the repository documentation.

## 3. Users and permissions

| Capability | Admin | Staff |
|---|---|---|
| Log in and view own profile | Yes | Yes |
| View products, suppliers, inventory, dashboard | Yes | Yes |
| Create or update products and suppliers | Yes | No |
| Archive products and suppliers | Yes | No |
| Record stock receipts and adjustments | Yes | No |
| View inventory history | Yes | Yes |
| Create, view, confirm, and cancel orders | Yes | Yes |
| Manage user accounts and roles | Yes | No |

This is a single-business application. Authorized staff can view all business orders. Public registration is not part of the MVP. An initial admin is provisioned securely; admins create staff accounts.

## 4. Scope

### MVP

1. Authentication and role-based authorization.
2. Product and supplier management.
3. Stock receipts, adjustments, and immutable movement history.
4. Draft order creation, confirmation, and cancellation.
5. Concurrency-safe stock deduction and restoration.
6. Dashboard with low-stock products and recent orders.
7. Search, filtering, and pagination.
8. OpenAPI documentation, integration tests, CI, and deployment.

### Out of scope for MVP

- Payments, accounting, tax calculation, and shipping integrations.
- Multiple warehouses, multi-tenancy, or microservices.
- Purchase-order workflows and supplier invoicing.
- AI forecasting, mobile applications, and real-time notifications.
- Partial fulfilment, returns, and order editing after creation.

## 5. Functional requirements

### 5.1 Authentication and users

- Users log in with email and password.
- Store passwords using a supported adaptive password hash, such as BCrypt.
- Use short-lived bearer JWT access tokens; keep frontend tokens in memory for the MVP. Reloading the application may require signing in again.
- Logout clears the frontend token. Tokens expire server-side; account activity and current permissions are checked for protected requests.
- Admins can create accounts, update roles, and deactivate accounts.
- Prevent deactivation or demotion of the last active admin.
- Validate permissions on the backend for every protected operation.

### 5.2 Products and suppliers

- Product fields: SKU, name, description, unit price, current stock, low-stock threshold, optional supplier, active flag, timestamps.
- SKU is unique after trimming and case normalization.
- Unit price must be nonnegative; quantity and threshold are nonnegative integers.
- Stock starts at zero and changes only through stock movements and order workflows, never through product updates.
- Supplier fields: name, optional email, phone, address, active flag, timestamps.
- Support product search by name or SKU, supplier filters, low-stock filters, and pagination.
- Archive records instead of deleting records needed by historical orders or movements.
- Archived products cannot be added to new orders or confirmed in existing drafts. Historical records remain readable.
- Archived suppliers cannot be newly assigned to products; existing associations remain visible.

### 5.3 Inventory

- Admins record receipts with a positive integer quantity and optional reference.
- Admins record adjustments with a nonzero signed integer quantity and a required reason.
- An adjustment must not make stock negative.
- Each movement records product, signed quantity, type, actor, timestamp, reason/reference, and related order where applicable.
- Movement types: RECEIPT, ADJUSTMENT, ORDER_CONFIRMATION, ORDER_CANCELLATION.
- Movement records are immutable. Correct mistakes with a new compensating adjustment.
- Save the stock balance change and its movement record in the same database transaction.

### 5.4 Orders

- An order contains at least one item and optional customer name/reference.
- Each item identifies an active product and a positive integer quantity. Reject duplicate product entries in one request.
- The backend retrieves prices and snapshots SKU, product name, and unit price into order items at creation.
- Calculate totals on the backend using decimal arithmetic. Use one configured currency; exclude taxes, discounts, and shipping from the MVP.
- Creating an order saves a DRAFT and does not reserve or deduct stock.
- Draft prices remain fixed even if catalogue prices change.
- Confirming a DRAFT checks product availability and stock, deducts all quantities, writes movements, and marks the order CONFIRMED in one transaction.
- If any item fails validation or has insufficient stock, roll back the entire confirmation.
- Cancelling a DRAFT changes its status to CANCELLED without stock changes.
- Cancelling a CONFIRMED order restores stock exactly once, including for products archived after confirmation.
- CANCELLED is terminal. Reconfirming a CONFIRMED order or recancelling a CANCELLED order returns its existing state without further stock changes. Confirming a CANCELLED order returns 409.
- Capture creator, confirmer, canceller, and relevant timestamps for auditability.

### 5.5 Dashboard

- Show active product count, low-stock count, draft order count, and confirmed order value.
- Low stock means current stock is less than or equal to the configured product threshold.
- Confirmed order value includes only currently CONFIRMED orders and is not labelled as collected revenue.
- Show recent orders and a paginated low-stock list with links to details.

## 6. Technical architecture

```text
React + TypeScript frontend
          |
          | HTTPS / JSON
          v
Spring Boot REST API
          |
          | Spring Data JPA
          v
PostgreSQL
```

### Stack

- Backend: Java 21 LTS, compatible stable Spring Boot, Spring Web, Spring Security, Spring Data JPA, Bean Validation, Maven.
- Database: PostgreSQL with Flyway schema migrations.
- Frontend: React, TypeScript, Vite, React Router, TanStack Query.
- API documentation: Springdoc OpenAPI and Swagger UI.
- Testing: JUnit, Spring Boot integration tests, Testcontainers PostgreSQL, selected Playwright browser tests.
- Delivery: Docker, Docker Compose for local dependencies, GitHub Actions.

Pin compatible stable dependency versions when implementation starts.

### Backend organization

Organize by feature (`auth`, `users`, `products`, `suppliers`, `inventory`, `orders`, `dashboard`) with controller, service, repository, entity, and DTO responsibilities inside each feature as needed.

- Controllers handle HTTP mapping and request validation.
- Services implement business rules and transaction boundaries.
- Repositories handle persistence.
- DTOs define explicit request/response contracts; do not serialize entities directly.
- A shared exception handler produces consistent API errors.

## 7. REST API contract

**Base path:** `/api/v1`  
**Format:** JSON  
**Authentication:** `Authorization: Bearer <access-token>` for protected endpoints

| Method | Endpoint | Purpose | Permission |
|---|---|---|---|
| POST | `/auth/login` | Authenticate and issue access token | Public |
| GET | `/auth/me` | Get current user | Signed in |
| GET | `/users` | List accounts | Admin |
| POST | `/users` | Create account | Admin |
| PATCH | `/users/{id}` | Update role or active status | Admin |
| GET | `/products` | Search/filter/page products | Signed in |
| GET | `/products/{id}` | Get product | Signed in |
| POST | `/products` | Create product | Admin |
| PATCH | `/products/{id}` | Update allowed fields or archive | Admin |
| GET | `/suppliers` | Search/page suppliers | Signed in |
| GET | `/suppliers/{id}` | Get supplier | Signed in |
| POST | `/suppliers` | Create supplier | Admin |
| PATCH | `/suppliers/{id}` | Update or archive supplier | Admin |
| POST | `/stock-movements` | Record receipt or adjustment | Admin |
| GET | `/products/{id}/stock-movements` | Page product movement history | Signed in |
| POST | `/orders` | Create draft order | Signed in |
| GET | `/orders` | Filter/page orders | Signed in |
| GET | `/orders/{id}` | Get order and items | Signed in |
| POST | `/orders/{id}/confirm` | Confirm and deduct stock | Signed in |
| POST | `/orders/{id}/cancel` | Cancel and restore stock if needed | Signed in |
| GET | `/dashboard/summary` | Get dashboard metrics | Signed in |

### Conventions

- Use UUID identifiers and UTC ISO 8601 timestamps.
- Use zero-based `page`, default `size=20`, maximum `size=100`, and allowlisted sort fields with a stable identifier tie-breaker.
- Return paginated results as `content`, `page`, `size`, `totalElements`, and `totalPages`.
- Product query example: `/products?search=rice&lowStock=true&page=0&size=20`.
- Order filters include status and creation date range; define inclusive start and exclusive end timestamps in OpenAPI.
- PATCH applies only explicitly permitted fields; reject unknown or immutable fields such as product stock and order totals.
- POST creation returns `201 Created` with a `Location` header; successful reads, patches, confirmations, and cancellations return `200 OK`.
- Use `400` for invalid input, `401` for missing/invalid credentials, `403` for insufficient permissions, `404` for unknown resources, and `409` for state or stock conflicts.
- Specify all schemas, examples, query parameters, security requirements, and error responses in OpenAPI.

### Example order creation

```http
POST /api/v1/orders
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "customerName": "Demo Customer",
  "items": [
    { "productId": "d9e49324-bbf4-4d91-a726-659927f737d2", "quantity": 3 }
  ]
}
```

Prices and totals are not accepted from the client.

### Example error

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "There is not enough stock to confirm this order.",
  "fieldErrors": [],
  "traceId": "request-correlation-id"
}
```

Never expose stack traces, tokens, passwords, or database details in responses.

## 8. Data model

| Entity | Main fields |
|---|---|
| User | id, email, passwordHash, role, active, createdAt, updatedAt |
| Supplier | id, name, email, phone, address, active, timestamps |
| Product | id, sku, name, description, unitPrice, stockQuantity, lowStockThreshold, supplierId, active, timestamps |
| Order | id, orderNumber, customerName, reference, status, currency, total, createdBy, confirmedBy, cancelledBy, createdAt, confirmedAt, cancelledAt |
| OrderItem | id, orderId, productId, skuSnapshot, nameSnapshot, quantity, unitPriceSnapshot, lineTotal |
| StockMovement | id, productId, type, quantityDelta, reason, reference, orderId, createdBy, createdAt |

### Constraints and indexes

- Unique normalized user email, product SKU, and order number.
- Foreign keys for all relationships; restrict deletion of referenced historical records.
- Database checks for nonnegative stock/prices and positive order quantities.
- Use PostgreSQL NUMERIC and Java BigDecimal for money; define a consistent two-decimal rounding policy and reject excess input precision.
- Index order status/date and product movement history queries.
- Add a uniqueness constraint for order-driven movements on `(order_id, product_id, type)` to help prevent duplicate effects.

## 9. Transaction and concurrency design

- Use a database transaction for confirmation, cancellation, and manual inventory updates.
- Lock the order row before changing order state.
- Lock affected product rows using pessimistic write locks in deterministic product-ID order.
- Recheck order state and available stock after acquiring locks.
- All stock-changing paths use the same product-locking strategy.
- Persist stock changes, movement records, and order state atomically.
- Repeated confirmations/cancellations must not create duplicate movements.
- Creation and manual stock POST requests are not automatically idempotent: disable duplicate UI submissions and do not blindly retry ambiguous failures.
- Translate lock contention/timeouts into a documented retryable conflict without partial writes.

**Critical acceptance scenario:** With one unit remaining and two simultaneous confirmations each requesting one unit, exactly one succeeds, the other returns a stock conflict, and final stock is zero.

## 10. Frontend requirements

### Pages

1. Login.
2. Dashboard.
3. Product list and product create/edit forms.
4. Product details with stock history and admin stock actions.
5. Supplier list and create/edit forms.
6. Order list, draft creation, and order details with confirm/cancel actions.
7. Admin user management.

### User experience

- Responsive layouts for desktop and mobile widths.
- Labelled inputs, keyboard-accessible controls, and visible focus states.
- Loading, empty, validation, permission, and failure states.
- Disable submission while a mutation is pending and refresh affected data on success.
- Display stock conflicts clearly and refresh current availability.
- Confirm cancellation and archive actions before submitting them.
- Hide unauthorized controls for clarity while enforcing permissions independently on the backend.

## 11. Nonfunctional requirements

- Use HTTPS in deployment and environment-based configuration.
- Keep credentials out of version control; provide `.env.example` with placeholders.
- Restrict CORS to configured frontend origins.
- Rate-limit login attempts and use generic invalid-credential messages.
- Use structured logs with request correlation IDs; do not log authentication secrets.
- Provide a health endpoint without exposing sensitive operational information.
- Bound list sizes and validate all input on the server.
- Target p95 below 500 ms for product listing and order detail API reads on a documented warmed-up demo environment with 1,000 products and 10 concurrent users. Report actual measured results and environment rather than claiming an untested result.
- Support reproducible setup using database migrations and documented commands.

## 12. Verification and acceptance criteria

### Meaningful automated checks

- Authentication rejects invalid credentials and inactive accounts.
- Staff cannot mutate products, manually adjust stock, or manage users.
- Duplicate normalized SKUs and invalid quantities are rejected.
- Order totals use stored product prices rather than client values.
- Draft creation leaves stock unchanged.
- Successful confirmation deducts stock and writes matching movements.
- A multi-item confirmation failure leaves every product and the order unchanged.
- Concurrent confirmations cannot oversell.
- Repeated and concurrent cancellation restores stock once only.
- Concurrent confirmation/cancellation produces a valid final state and consistent stock.
- Insufficient negative adjustments leave no movement record or balance change.
- Archiving preserves history and prevents new sales of the product.
- Integration tests run against PostgreSQL through Testcontainers, not a substitute in-memory database for locking tests.
- Browser smoke test covers login, draft creation, confirmation, and dashboard refresh.

### MVP completion criteria

- All MVP workflows function through both the UI and documented API.
- Database migrations work on an empty database.
- Critical integration tests and frontend/backend builds pass in CI.
- A deployed demo contains synthetic sample data and a usable staff login.
- Public demo access does not expose administrative account management; demo data reset procedures are documented.
- README includes setup, configuration, tests, screenshots, architecture, ER diagram, Swagger URL, demo URL, and known limitations.
- No secrets are committed, and errors are presented consistently.

## 13. Implementation milestones

### Milestone 1 — Foundation and first vertical slice

- Initialize backend/frontend, PostgreSQL, migrations, and local configuration.
- Implement login, roles, and initial admin provisioning.
- Deliver product creation through the REST API and product display in React.
- Establish CI build checks.

### Milestone 2 — Catalogue and inventory

- Complete suppliers, product filtering, pagination, and archiving.
- Implement receipts, adjustments, movement history, and permissions.
- Verify validation and atomic stock updates.

### Milestone 3 — Order engine

- Implement draft creation and price snapshots.
- Implement transactional confirmation/cancellation and row locking.
- Verify concurrent and repeated requests with integration tests.

### Milestone 4 — Portfolio delivery

- Complete dashboard and responsive frontend states.
- Finalize Swagger documentation and browser smoke coverage.
- Containerize, deploy, seed demo data, and document setup/design decisions.
- Measure performance and record actual results.

## 14. Planned repository layout

```text
StockFlow/
├── PRD.md
├── README.md
├── backend/
├── frontend/
├── docs/
│   ├── architecture.md
│   └── er-diagram.md
├── compose.yaml
├── .env.example
└── .github/
    └── workflows/
```

This layout is a plan; this document does not imply these implementation files already exist.

## 15. Decisions to finalize during implementation

- Hosting providers and deployment budget.
- Business currency (suggested initial demo setting: LKR).
- UI component library and visual design.
- Exact supported dependency versions and measured performance environment.

## 16. Suggested CV description after completion

**StockFlow — Inventory and Order Management System**  
**Stack:** Java, Spring Boot, React, TypeScript, PostgreSQL, Docker, GitHub Actions.

- Designed and implemented REST APIs for inventory and order management with role-based authorization, input validation, and OpenAPI documentation.
- Implemented transactional stock updates and concurrency control to prevent overselling and duplicate stock restoration.
- Delivered a React dashboard with automated integration tests and CI-backed deployment.

Use these statements only after the corresponding features are implemented and verified.
