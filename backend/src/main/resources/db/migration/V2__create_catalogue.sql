CREATE TABLE suppliers (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    email varchar(254),
    phone varchar(32),
    address varchar(500),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id uuid PRIMARY KEY,
    sku varchar(64) NOT NULL UNIQUE CHECK (sku = upper(btrim(sku)) AND sku ~ '^[A-Z0-9._-]+$'),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(2000),
    unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    stock_quantity integer NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    low_stock_threshold integer NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
    supplier_id uuid REFERENCES suppliers(id) ON DELETE RESTRICT,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX products_supplier_idx ON products(supplier_id);
CREATE INDEX products_active_name_idx ON products(active, name, id);
CREATE INDEX suppliers_active_name_idx ON suppliers(active, name, id);
