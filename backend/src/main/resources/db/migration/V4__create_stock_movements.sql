-- Never invent or silently discard opening balances from out-of-band edits.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM products WHERE stock_quantity <> 0) THEN
        RAISE EXCEPTION 'Existing nonzero stock needs a reviewed opening-balance migration before enabling the ledger. No balances were changed.';
    END IF;
END;
$$;

CREATE TABLE stock_movements (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    type varchar(24) NOT NULL CHECK (type IN ('RECEIPT', 'ADJUSTMENT', 'ORDER_CONFIRMATION', 'ORDER_CANCELLATION')),
    quantity_delta integer NOT NULL CHECK (quantity_delta <> 0),
    reason varchar(500),
    reference varchar(160),
    order_id uuid REFERENCES orders(id) ON DELETE RESTRICT,
    created_by uuid NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stock_movements_rules_check CHECK (
        (type = 'RECEIPT' AND quantity_delta > 0 AND order_id IS NULL)
        OR (type = 'ADJUSTMENT' AND order_id IS NULL AND reason IS NOT NULL AND length(btrim(reason)) > 0)
        OR (type = 'ORDER_CONFIRMATION' AND quantity_delta < 0 AND order_id IS NOT NULL)
        OR (type = 'ORDER_CANCELLATION' AND quantity_delta > 0 AND order_id IS NOT NULL)
    ),
    FOREIGN KEY (order_id, product_id) REFERENCES order_items(order_id, product_id) ON DELETE RESTRICT
);

CREATE INDEX stock_movements_product_created_idx ON stock_movements(product_id, created_at DESC, id);
CREATE UNIQUE INDEX stock_movements_order_effect_idx ON stock_movements(order_id, product_id, type) WHERE order_id IS NOT NULL;

-- Append-only protection also applies when local development uses the table owner.
-- Production should additionally grant runtime roles only SELECT/INSERT here.
CREATE FUNCTION reject_stock_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Stock movements are immutable; record a compensating adjustment instead.' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER stock_movements_immutable
BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_mutation();
