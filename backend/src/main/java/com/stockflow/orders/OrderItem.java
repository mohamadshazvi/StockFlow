package com.stockflow.orders;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import com.stockflow.products.Product;

@Entity
@Table(name = "order_items")
@Immutable
public class OrderItem {
    @Id private UUID id;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "product_id", nullable = false, updatable = false) private UUID productId;
    @Column(name = "sku_snapshot", nullable = false, length = 64, updatable = false) private String skuSnapshot;
    @Column(name = "name_snapshot", nullable = false, length = 160, updatable = false) private String nameSnapshot;
    @Column(nullable = false, updatable = false) private int quantity;
    @Column(name = "unit_price_snapshot", nullable = false, precision = 12, scale = 2, updatable = false) private BigDecimal unitPriceSnapshot;
    @Column(name = "line_total", nullable = false, precision = 24, scale = 2, updatable = false) private BigDecimal lineTotal;
    protected OrderItem() {}
    public OrderItem(UUID orderId, Product product, int quantity) {
        id = UUID.randomUUID(); this.orderId = orderId; productId = product.getId(); skuSnapshot = product.getSku(); nameSnapshot = product.getName();
        this.quantity = quantity; unitPriceSnapshot = product.getUnitPrice().setScale(2);
        lineTotal = unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity)).setScale(2);
    }
    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getProductId() { return productId; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public String getNameSnapshot() { return nameSnapshot; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public BigDecimal getLineTotal() { return lineTotal; }
}
