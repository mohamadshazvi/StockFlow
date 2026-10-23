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
    private final OrderLifecycleService lifecycle;
    public OrderController(OrderService service, OrderLifecycleService lifecycle) { this.service = service; this.lifecycle = lifecycle; }
    @PostMapping
    @Operation(summary = "Create a draft order (admin or staff)", description = "1–100 unique active products; quantities 1–1000000. Prices/totals and actor are server-owned. Snapshots are immutable; draft creation does not reserve/deduct stock. This POST is not idempotent: inspect recent orders before retrying a lost response. Draft editing is not supported.")
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

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an order (admin or staff)", description = "No request body. Locks the order then products in ascending UUID order. Atomically deducts stock and records movements. Repeated confirmation of a confirmed order returns 200 without effects or audit changes. Cancelled orders, archived products, insufficient stock and lock conflicts return 409. After a lost response, refresh the order before explicitly retrying.")
    public View confirm(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String body) {
        noBody(body);
        return lifecycle.confirm(id, UUID.fromString(jwt.getSubject()));
    }
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order (admin or staff)", description = "No request body. Cancels a draft without stock effects; restores confirmed quantities, including archived products, in one transaction. Repeated cancellation returns 200 without effects. Confirmation audit metadata is preserved. Overflow or lock conflicts return 409; cancelled orders cannot be confirmed again.")
    public View cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String body) {
        noBody(body);
        return lifecycle.cancel(id, UUID.fromString(jwt.getSubject()));
    }
    private static void noBody(String body) {
        if (body != null && !body.isBlank()) throw new com.stockflow.common.error.ApiProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "This operation does not accept a request body.");
    }
}
