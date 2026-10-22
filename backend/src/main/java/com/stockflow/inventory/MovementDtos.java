package com.stockflow.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class MovementDtos {
    private MovementDtos() {}
    @Schema(name = "CreateStockMovement")
    public record Input(@NotNull UUID productId,
            @NotNull @Pattern(regexp = "RECEIPT|ADJUSTMENT", message = "Only RECEIPT and ADJUSTMENT may be submitted manually.") String type,
            @NotNull @Schema(description = "Positive for a receipt; nonzero signed integer for an adjustment", example = "10") Integer quantityDelta,
            @Size(max = 500) String reason, @Size(max = 160) String reference) {
        public Input { reason = clean(reason); reference = clean(reference); }
        private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    }
    @Schema(name = "StockMovementView")
    public record View(UUID id, UUID productId, String type, int quantityDelta, String reason, String reference,
            UUID orderId, UUID createdBy, Instant createdAt) {
        static View of(StockMovement movement) {
            return new View(movement.getId(), movement.getProductId(), movement.getType(), movement.getQuantityDelta(),
                    movement.getReason(), movement.getReference(), movement.getOrderId(), movement.getCreatedBy(), movement.getCreatedAt());
        }
    }
    @Schema(name = "StockMovementPage")
    public record Page(List<View> content, int page, int size, long totalElements, int totalPages) {}
}
