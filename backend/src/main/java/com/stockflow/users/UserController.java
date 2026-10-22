package com.stockflow.users;

import java.net.URI;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }
    @GetMapping public UserService.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "email,asc") String sort) { return service.list(page, size, sort); }
    @PostMapping @Operation(summary = "Create an account with an initial 12–72 UTF-8 byte password")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created; password is not returned", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = UserService.View.class)))
    public ResponseEntity<UserService.View> create(@Valid @RequestBody UserService.Create input) {
        // Users have a list route, not an individual GET endpoint.
        return ResponseEntity.created(URI.create("/api/v1/users")).body(service.create(input));
    }
    @PatchMapping("/{id}") @Operation(summary = "Update role/activity; preserve at least one active admin", description = "Only role and active are accepted. Password and email changes are not part of this endpoint.")
    public UserService.View patch(@PathVariable UUID id, @RequestBody Map<String, Object> patch) { return service.patch(id, patch); }
}
