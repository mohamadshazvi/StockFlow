package com.stockflow.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class CustomerOrder {
    @Id private UUID id;
    @Column(name = "order_number", nullable = false, length = 32, updatable = false) private String orderNumber;
    @Column(name = "customer_name", length = 160, updatable = false) private String customerName;
    @Column(length = 160, updatable = false) private String reference;
    @Column(nullable = false, length = 16) private String status;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(nullable = false, precision = 24, scale = 2, updatable = false) private BigDecimal total;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "confirmed_by") private UUID confirmedBy;
    @Column(name = "cancelled_by") private UUID cancelledBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    protected CustomerOrder() {}
    public CustomerOrder(String orderNumber, String customerName, String reference, String currency, BigDecimal total, UUID createdBy) {
        this.id = UUID.randomUUID(); this.orderNumber = orderNumber; this.customerName = customerName; this.reference = reference;
        this.currency = currency; this.total = total; this.createdBy = createdBy; this.status = "DRAFT"; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public String getCustomerName() { return customerName; }
    public String getReference() { return reference; }
    public String getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotal() { return total; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    /** Lifecycle service must hold this order's write lock and commit stock effects atomically. */
    public void confirm(UUID actor, Instant at) {
        if (!status.equals("DRAFT")) throw new IllegalStateException("Only a draft can be confirmed");
        status = "CONFIRMED"; confirmedBy = actor; confirmedAt = at;
    }
    public void cancel(UUID actor, Instant at) {
        if (status.equals("CANCELLED")) throw new IllegalStateException("Order already cancelled");
        status = "CANCELLED"; cancelledBy = actor; cancelledAt = at;
    }
}
