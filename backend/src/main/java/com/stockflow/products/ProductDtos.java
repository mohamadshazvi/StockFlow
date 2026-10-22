package com.stockflow.products;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

public final class ProductDtos {
    private ProductDtos() {}
    public record CreateProduct(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9._-]+", message = "Use letters, digits, dots, underscores or hyphens.")
            @Schema(example = "RICE-001") String sku,
            @NotBlank @Size(max = 160) @Schema(example = "White Rice 1kg") String name,
            @Size(max = 2000) String description,
            @NotNull @Pattern(regexp = "[0-9]{1,10}(\\.[0-9]{1,2})?", message = "Enter a nonnegative price with at most 10 whole digits and 2 decimal places.")
            @Schema(type = "string", example = "450.00") String unitPrice,
            @NotNull @Min(0) Integer lowStockThreshold,
            UUID supplierId) {
        public CreateProduct {
            sku = sku == null ? null : sku.trim().toUpperCase(Locale.ROOT);
            name = name == null ? null : name.trim();
            description = description == null || description.isBlank() ? null : description.trim();
            unitPrice = unitPrice == null ? null : unitPrice.trim();
        }
    }

    public record ProductView(UUID id, String sku, String name, String description,
            @Schema(type = "string", example = "450.00") String unitPrice, int stockQuantity,
            int lowStockThreshold, UUID supplierId, boolean active, Instant createdAt, Instant updatedAt) {
        static ProductView of(Product p) {
            return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(),
                    p.getUnitPrice().setScale(2).toPlainString(), p.getStockQuantity(), p.getLowStockThreshold(),
                    p.getSupplierId(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record ProductPage(List<ProductView> content, int page, int size, long totalElements, int totalPages) {
        static ProductPage of(Page<Product> source) {
            return new ProductPage(source.getContent().stream().map(ProductView::of).toList(),
                    source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
        }
    }
}
