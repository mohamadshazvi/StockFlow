package com.stockflow.orders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class OrderDtos {
    private OrderDtos() {}
    @Schema(name = "DraftOrderItemInput")
    public record ItemInput(@NotNull UUID productId, @NotNull @Min(1) @Max(1000000) Integer quantity) {}
    @Schema(name = "CreateDraftOrder")
    public record Create(@Size(max = 160) String customerName, @Size(max = 160) String reference,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ItemInput> items) {
        public Create { customerName = clean(customerName); reference = clean(reference); }
        private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    }
    @Schema(name = "OrderItemView")
    public record ItemView(UUID id, UUID productId, String skuSnapshot, String nameSnapshot, int quantity,
            @Schema(type = "string", example = "450.00") String unitPriceSnapshot,
            @Schema(type = "string", example = "1350.00") String lineTotal) {
        static ItemView of(OrderItem item) { return new ItemView(item.getId(), item.getProductId(), item.getSkuSnapshot(), item.getNameSnapshot(), item.getQuantity(), item.getUnitPriceSnapshot().setScale(2).toPlainString(), item.getLineTotal().setScale(2).toPlainString()); }
    }
    @Schema(name = "OrderSummary")
    public record Summary(UUID id, String orderNumber, String customerName, String reference, String status, String currency,
            @Schema(type = "string", example = "1350.00") String total, UUID createdBy, UUID confirmedBy, UUID cancelledBy,
            Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
        static Summary of(CustomerOrder order) { return new Summary(order.getId(), order.getOrderNumber(), order.getCustomerName(), order.getReference(), order.getStatus(), order.getCurrency(), order.getTotal().setScale(2).toPlainString(), order.getCreatedBy(), order.getConfirmedBy(), order.getCancelledBy(), order.getCreatedAt(), order.getConfirmedAt(), order.getCancelledAt()); }
    }
    @Schema(name = "OrderDetail")
    public record View(UUID id, String orderNumber, String customerName, String reference, String status, String currency,
            @Schema(type = "string", example = "1350.00") String total, UUID createdBy, UUID confirmedBy, UUID cancelledBy,
            Instant createdAt, Instant confirmedAt, Instant cancelledAt, List<ItemView> items) {
        static View of(CustomerOrder order, List<OrderItem> items) { return new View(order.getId(), order.getOrderNumber(), order.getCustomerName(), order.getReference(), order.getStatus(), order.getCurrency(), order.getTotal().setScale(2).toPlainString(), order.getCreatedBy(), order.getConfirmedBy(), order.getCancelledBy(), order.getCreatedAt(), order.getConfirmedAt(), order.getCancelledAt(), items.stream().map(ItemView::of).toList()); }
    }
    @Schema(name = "OrderPage")
    public record Page(List<Summary> content, int page, int size, long totalElements, int totalPages) {}
}
