package com.stockflow.inventory;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.stockflow.inventory.MovementDtos.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inventory")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }
    @PostMapping("/stock-movements")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record a stock receipt or signed adjustment (admin only)", description = "Receipts must be positive. Adjustments must be nonzero and have a reason. Actor/time are server-owned. Changes are atomic and serialized on the product row. This POST is NOT idempotent: after a network timeout, inspect history before retrying. Movements cannot be edited/deleted.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Immutable movement recorded and stock updated", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = View.class)))
    public ResponseEntity<View> record(@Valid @RequestBody Input input, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(201).body(service.record(input, UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/products/{id}/stock-movements")
    @Operation(summary = "Read product stock history, including archived products", description = "Zero-based pagination; size 1–100; sort=createdAt,desc (default) or createdAt,asc. ID provides a stable tie-breaker.")
    public Page history(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return service.history(id, page, size, sort);
    }
}
