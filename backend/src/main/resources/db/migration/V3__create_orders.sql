-- Schema only in Phase 4; order APIs and workflows follow in Phases 5–6.
CREATE SEQUENCE order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE orders (
    id uuid PRIMARY KEY,
    order_number varchar(32) NOT NULL UNIQUE,
    customer_name varchar(160),
    reference varchar(160),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total numeric(24,2) NOT NULL CHECK (total >= 0),
    created_by uuid NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    confirmed_by uuid REFERENCES app_users(id) ON DELETE RESTRICT,
    cancelled_by uuid REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    cancelled_at timestamptz,
    CONSTRAINT orders_state_check CHECK (
        (status = 'DRAFT' AND confirmed_by IS NULL AND confirmed_at IS NULL AND cancelled_by IS NULL AND cancelled_at IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND cancelled_by IS NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND ((confirmed_by IS NULL AND confirmed_at IS NULL) OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)))
    ),
    CHECK (confirmed_at IS NULL OR confirmed_at >= created_at),
    CHECK (cancelled_at IS NULL OR cancelled_at >= created_at),
    CHECK (confirmed_at IS NULL OR cancelled_at IS NULL OR cancelled_at >= confirmed_at)
);

CREATE TABLE order_items (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    sku_snapshot varchar(64) NOT NULL,
    name_snapshot varchar(160) NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    unit_price_snapshot numeric(12,2) NOT NULL CHECK (unit_price_snapshot >= 0),
    line_total numeric(24,2) NOT NULL CHECK (line_total = quantity * unit_price_snapshot),
    UNIQUE (order_id, product_id)
);

CREATE INDEX orders_created_idx ON orders(created_at DESC, id);
CREATE INDEX orders_status_created_idx ON orders(status, created_at DESC, id);
CREATE INDEX order_items_product_idx ON order_items(product_id);
