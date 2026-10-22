package com.stockflow.suppliers;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "suppliers")
public class Supplier {
    @Id private UUID id;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 254) private String email;
    @Column(length = 32) private String phone;
    @Column(length = 500) private String address;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Supplier() {}
    public Supplier(SupplierDtos.Input input) {
        id = UUID.randomUUID(); createdAt = Instant.now(); update(input, true);
    }
    public void update(SupplierDtos.Input input, boolean active) {
        name = input.name(); email = input.email(); phone = input.phone(); address = input.address();
        this.active = active; updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
