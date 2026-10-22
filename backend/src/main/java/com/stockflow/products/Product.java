package com.stockflow.products;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String sku;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 2000) private String description;
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2) private BigDecimal unitPrice;
    @Column(name = "stock_quantity", nullable = false) private int stockQuantity;
    @Column(name = "low_stock_threshold", nullable = false) private int lowStockThreshold;
    @Column(name = "supplier_id") private UUID supplierId;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Product() {}
    public Product(String sku, String name, String description, BigDecimal price, int threshold, UUID supplierId) {
        this.id = UUID.randomUUID(); this.sku = sku; this.name = name; this.description = description;
        this.unitPrice = price; this.lowStockThreshold = threshold; this.supplierId = supplierId;
        this.stockQuantity = 0; this.active = true; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getStockQuantity() { return stockQuantity; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public UUID getSupplierId() { return supplierId; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    /** Caller must hold this product's database write lock and persist a matching movement. */
    public void applyStockMovement(int delta) {
        long next = (long) stockQuantity + delta;
        if (next < 0 || next > Integer.MAX_VALUE) throw new IllegalStateException("Stock balance outside integer bounds");
        stockQuantity = (int) next;
        updatedAt = Instant.now();
    }
    public void update(ProductDtos.CreateProduct values, boolean active) {
        sku = values.sku(); name = values.name(); description = values.description();
        unitPrice = new BigDecimal(values.unitPrice()).setScale(2); lowStockThreshold = values.lowStockThreshold();
        supplierId = values.supplierId(); this.active = active; updatedAt = Instant.now();
        // stockQuantity is deliberately never assigned by catalogue updates.
    }
}
