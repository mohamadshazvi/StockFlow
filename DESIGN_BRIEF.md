# StockFlow — Design Brief

Status: Proposed visual direction | Date: 2026-10-13

Related: [App flow](APP_FLOW.md), [PRD](PRD.md), [TRD](TRD.md).

## 1. Purpose and personality

Design a clear business dashboard for people tracking inventory and processing orders. Prioritize readable data, obvious state changes, and fast completion of everyday tasks. The visual character is calm, precise, and professional.

Primary users: admins maintaining catalogue/inventory and staff handling orders. The interface must remain usable with realistic long product names, empty tables, many records, and validation errors.

## 2. Visual direction

Light theme for MVP. Use a deep navy navigation area, neutral page backgrounds, white surfaces, and blue primary actions. Keep decoration minimal; use meaningful stock/order states rather than decorative charts.

### Proposed colour tokens

| Token | Value | Use |
|---|---|---|
| `--color-bg` | `#F8FAFC` | Page background |
| `--color-surface` | `#FFFFFF` | Cards, forms, tables |
| `--color-text` | `#0F172A` | Primary text |
| `--color-muted` | `#475569` | Secondary text |
| `--color-border` | `#CBD5E1` | Dividers and input borders |
| `--color-primary` | `#1D4ED8` | Main buttons, links, focus |
| `--color-primary-hover` | `#1E40AF` | Primary hover |
| `--color-nav` | `#0F172A` | Desktop sidebar |
| `--color-success` | `#166534` | Confirmed/in-stock text |
| `--color-warning` | `#92400E` | Low-stock/draft text |
| `--color-danger` | `#B91C1C` | Errors/destructive actions |

Use pale tinted badge backgrounds with dark text. Check actual foreground/background combinations during implementation for WCAG AA contrast; token selection alone is not proof of compliance. Never rely on colour without labels or icons.

### Typography and spacing

- System sans-serif font stack to avoid an external font dependency.
- Body 16px, metadata/table secondary text 14px, section headings 20px, page headings 28px.
- Body line height about 1.5; headings about 1.2. Use tabular numerals for quantities and money.
- Spacing scale: 4, 8, 12, 16, 24, 32, 48px.
- Inputs/buttons at least 44px high. Card radius 12px; input/button radius 8px.
- Prefer subtle borders; use shadows only for floating layers or mild surface separation.

## 3. Application shell and responsive layout

- Desktop >=1024px: 240px sidebar, compact top bar, main area with 24–32px padding and maximum content width around 1440px.
- Tablet 768–1023px: collapsible navigation, two-column metric cards, 20px page padding.
- Mobile <768px: navigation drawer, single-column forms/cards, 16px padding, primary action near page heading.
- Tables may scroll inside a labelled container; the whole page must not overflow horizontally. Keep essential columns visible and use row details for secondary information.
- Design and manually inspect at approximately 360px, 768px, and 1440px widths; also verify 200% zoom.

## 4. Screen composition

### Dashboard

```text
Sidebar | Header: StockFlow / account menu
        | Dashboard                    [Create order]
        | [Active products] [Low stock] [Drafts] [Confirmed value]
        | Recent orders table
        | Low-stock products list       [View all]
```

Show the configured currency next to confirmed value. Do not label it paid revenue. Avoid charts until a meaningful requirement exists.

### Products

Page title and admin-only Add product action -> search/filter toolbar -> data table -> pagination. Columns: SKU, name, supplier, unit price, stock, status, row action. Low-stock state shows a labelled badge next to quantity.

Product detail: breadcrumb, name/SKU and active state, price/stock/threshold overview, admin edit/archive/stock actions, movement history. Movement quantities display an explicit plus or minus sign and type.

### Suppliers

List: name, contact details, active state. Detail: supplier contact card and linked products. Forms use one column on mobile; related fields may share a row on desktop.

### Order creation

Customer/reference section -> searchable product picker -> selected item rows with quantity and removal controls -> total preview -> Create draft. Explain clearly that creating a draft does not reserve inventory.

### Order detail

Header with order number and status badge -> customer/reference and timeline -> immutable item table with snapshot prices -> total -> state-dependent action area. Confirmation and cancellation dialogs state the inventory effect explicitly.

### Login and users

Login: centred compact card, product name, email/password fields, show-password control, error area, submit button. No public registration link. Users: account table with role/status and admin dialogs; no password columns.

## 5. Component inventory

- App shell, sidebar, mobile drawer, breadcrumbs, account menu.
- Button variants: primary, secondary, destructive, text; pending state reserves width.
- Labelled input, textarea, numeric input, select, searchable product selector.
- Form error summary and inline error text.
- Data table, pagination, empty state, skeleton, inline retry panel.
- Metric card, stock badge, order-status badge, audit timeline.
- Confirmation dialog and nonblocking success announcement.

Use native HTML semantics wherever possible. Add reusable components only when multiple screens need them.

## 6. Interaction and accessibility

- One primary action per screen or form section; destructive actions are visually distinct.
- Inputs have persistent labels, not placeholder-only descriptions.
- Keyboard focus is visible; no hover-only required actions.
- Dialogs have accessible titles/descriptions, focus trapping, Escape support, and focus restoration.
- Tables have headers and an accessible name. Sort buttons announce direction.
- Announce async success with a polite live region and important failures with an appropriate alert.
- Respect reduced-motion preferences. Use short transitions around 100–200ms only where helpful.
- Mark pending actions and prevent duplicate submission. Do not imply success before the API commits.
- Preserve values after validation failure and focus the error summary/first invalid field.

## 7. Content and formatting

- Use clear verbs: “Create draft”, “Confirm order”, “Receive stock”, “Adjust stock”, “Archive product”.
- Format currency consistently; example `LKR 1,250.00`. Keep server decimal values precise.
- Display dates in the browser locale with timezone context where necessary; API values remain UTC.
- Show customer/reference as “Not provided” when absent, rather than a misleading zero or blank label.
- Example conflict: “There is not enough stock to confirm this order. Review current availability.”
- Example adjustment help: “Use a negative quantity to remove stock. A reason is required.”
- Clearly distinguish an empty dataset from a search with no matches.

## 8. Design acceptance checklist

- All routes in APP_FLOW have loading, empty, error, and permission states where applicable.
- Staff views hide admin actions; backend security remains authoritative.
- Stock/status information is understandable without colour.
- Forms and dialogs are usable by keyboard and at 200% zoom.
- Long names, large totals, and mobile layouts do not clip essential information.
- Contrast and focus visibility have been checked in the rendered implementation.
- Screenshots for the portfolio use synthetic data and show dashboard, inventory history, and order detail.
