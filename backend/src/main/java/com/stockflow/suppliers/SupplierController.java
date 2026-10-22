package com.stockflow.suppliers;

import java.net.URI;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import static com.stockflow.suppliers.SupplierDtos.*;

@RestController
@RequestMapping("/api/v1/suppliers")
@Tag(name = "Suppliers")
@SecurityRequirement(name = "bearerAuth")
public class SupplierController {
    private final SupplierService service;
    public SupplierController(SupplierService service) { this.service = service; }
    @GetMapping @Operation(summary = "Search suppliers; active=true, false or all; size 1–100")
    public Page list(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "true") String active,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) { return service.list(search, active, page, size, sort); }
    @GetMapping("/{id}") public View get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @PreAuthorize("hasRole('ADMIN')") @Operation(summary = "Create a supplier (admin only)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Supplier created", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = View.class)))
    public ResponseEntity<View> create(@Valid @RequestBody Input input) {
        var result = service.create(input); return ResponseEntity.created(URI.create("/api/v1/suppliers/" + result.id())).body(result);
    }
    @PatchMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Edit or archive supplier", description = "Omitted fields are unchanged. email, phone and address can be null. Cannot unarchive; existing linked products remain linked.")
    public View patch(@PathVariable UUID id, @RequestBody Map<String, Object> patch) { return service.patch(id, patch); }
}
