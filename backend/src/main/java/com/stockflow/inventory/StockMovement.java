package com.stockflow.inventory;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "stock_movements")
@Immutable
public class StockMovement {
    @Id private UUID id;
    @Column(name = "product_id", nullable = false, updatable = false) private UUID productId;
    @Column(nullable = false, updatable = false, length = 24) private String type;
    @Column(name = "quantity_delta", nullable = false, updatable = false) private int quantityDelta;
    @Column(updatable = false, length = 500) private String reason;
    @Column(updatable = false, length = 160) private String reference;
    @Column(name = "order_id", updatable = false) private UUID orderId;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected StockMovement() {}
    public StockMovement(UUID productId, String type, int delta, String reason, String reference, UUID createdBy) {
        this.id = UUID.randomUUID(); this.productId = productId; this.type = type; this.quantityDelta = delta;
        this.reason = reason; this.reference = reference; this.createdBy = createdBy; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public String getType() { return type; }
    public int getQuantityDelta() { return quantityDelta; }
    public String getReason() { return reason; }
    public String getReference() { return reference; }
    public UUID getOrderId() { return orderId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
