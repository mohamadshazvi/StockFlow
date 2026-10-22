package com.stockflow.products;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.stockflow.common.error.ApiExceptionHandler.ApiError;
import static com.stockflow.products.ProductDtos.*;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Catalogue creation and read access; stock starts at zero.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid request or pagination/sort parameters", content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "403", description = "Admin role required for creation", content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "404", description = "Product or supplier not found", content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "409", description = "Duplicate SKU or archived supplier", content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(responseCode = "500", description = "Unexpected server failure", content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a product (admin only)")
    @ApiResponse(responseCode = "201", description = "Created with zero stock and Location header", content = @Content(schema = @Schema(implementation = ProductView.class)))
    public ResponseEntity<ProductView> create(@Valid @RequestBody CreateProduct input) {
        ProductView product = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id())).body(product);
    }

    @GetMapping
    @Operation(summary = "Search/filter products with bounded pagination", description = "active=true (default), false or all. supplierId filters existing links, including archived suppliers. lowStock=true includes active products at/below threshold. Sort fields: name, sku, unitPrice, stockQuantity, createdAt. Syntax: name,asc. Search matches name or SKU literally, ignoring case.")
    public ProductPage list(@RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(defaultValue = "true") String active, @RequestParam(required = false) UUID supplierId,
            @RequestParam(defaultValue = "false") boolean lowStock) {
        return service.list(search, page, size, sort, active, supplierId, lowStock);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update catalogue fields or archive (admin only)", description = "Omitted fields are unchanged; only description and supplierId can be null. Stock cannot be edited. Archiving is one-way.")
    public ProductView patch(@PathVariable UUID id, @RequestBody java.util.Map<String, Object> patch) { return service.patch(id, patch); }

    @GetMapping("/{id}")
    @Operation(summary = "Read a product by UUID")
    public ProductView get(@PathVariable UUID id) { return service.get(id); }
}
