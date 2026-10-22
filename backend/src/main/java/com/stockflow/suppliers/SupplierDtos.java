package com.stockflow.suppliers;

import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;

public final class SupplierDtos {
    private SupplierDtos() {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "SupplierInput")
    public record Input(@NotBlank @Size(max = 160) String name, @Email @Size(max = 254) String email,
            @Size(max = 32) String phone, @Size(max = 500) String address) {
        public Input { name = name == null ? null : name.trim(); email = clean(email); phone = clean(phone); address = clean(address); }
        private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "SupplierView")
    public record View(UUID id, String name, String email, String phone, String address, boolean active, Instant createdAt, Instant updatedAt) {
        static View of(Supplier s) { return new View(s.getId(), s.getName(), s.getEmail(), s.getPhone(), s.getAddress(), s.isActive(), s.getCreatedAt(), s.getUpdatedAt()); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "SupplierPage")
    public record Page(List<View> content, int page, int size, long totalElements, int totalPages) {}
}
