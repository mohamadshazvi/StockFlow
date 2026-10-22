package com.stockflow.orders;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.stockflow.orders.OrderDtos.*;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    @PostMapping
    @Operation(summary = "Create a draft order (admin or staff)", description = "1–100 unique active products; quantities 1–1000000. Prices/totals and actor are server-owned. Snapshots are immutable; draft creation does not reserve/deduct stock. This POST is not idempotent: inspect recent orders before retrying a lost response. Draft editing, confirmation and cancellation are not enabled in Phase 5.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Draft saved with authoritative price snapshots and Location header", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = View.class)))
    public ResponseEntity<View> create(@Valid @RequestBody Create input, @AuthenticationPrincipal Jwt jwt) {
        var result = service.create(input, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.created(URI.create("/api/v1/orders/" + result.id())).body(result);
    }
    @GetMapping
    @Operation(summary = "List business order summaries", description = "Optional status filter and inclusive createdFrom/exclusive createdTo ISO 8601 timestamps. Sort: createdAt, orderNumber or total with asc/desc. Page size 1–100; lists omit items.")
    public Page list(@RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) { return service.list(status, createdFrom, createdTo, page, size, sort); }
    @GetMapping("/{id}") @Operation(summary = "View order and saved item snapshots")
    public View get(@PathVariable UUID id) { return service.get(id); }
}
