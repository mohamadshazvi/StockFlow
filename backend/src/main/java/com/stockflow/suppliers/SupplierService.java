package com.stockflow.suppliers;

import java.util.*;
import com.stockflow.common.*;
import com.stockflow.common.error.ApiProblem;
import jakarta.validation.Validator;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stockflow.suppliers.SupplierDtos.*;

@Service
@Transactional(readOnly = true)
public class SupplierService {
    private final SupplierRepository suppliers;
    private final Validator validator;
    private final JdbcTemplate jdbc;
    public SupplierService(SupplierRepository suppliers, Validator validator, JdbcTemplate jdbc) { this.suppliers = suppliers; this.validator = validator; this.jdbc = jdbc; }
    @Transactional public View create(Input input) { return View.of(suppliers.saveAndFlush(new Supplier(input))); }
    public View get(UUID id) { return suppliers.findById(id).map(View::of).orElseThrow(() -> missing()); }
    public Page list(String search, String active, int page, int size, String sort) {
        var pageable = ListQuery.page(page, size, sort, Set.of("name", "createdAt"));
        String term = ListQuery.term(search); Boolean state = ListQuery.active(active);
        Specification<Supplier> spec = (root, query, cb) -> state == null ? cb.like(cb.lower(root.get("name")), term, '\\')
                : cb.and(cb.equal(root.get("active"), state), cb.like(cb.lower(root.get("name")), term, '\\'));
        var result = suppliers.findAll(spec, pageable);
        return new Page(result.getContent().stream().map(View::of).toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    @Transactional(timeout = 10)
    public View patch(UUID id, Map<String, Object> patch) {
        PatchInput.check(patch, "name", "email", "phone", "address", "active");
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        Supplier supplier = suppliers.lockById(id).orElseThrow(() -> missing());
        boolean active = PatchInput.bool(patch, "active", supplier.isActive());
        if (!supplier.isActive() && active) throw new ApiProblem(HttpStatus.CONFLICT, "SUPPLIER_ARCHIVED", "Archived suppliers cannot be reactivated.");
        Input input = PatchInput.validate(new Input(PatchInput.text(patch, "name", supplier.getName(), false),
                PatchInput.text(patch, "email", supplier.getEmail(), true), PatchInput.text(patch, "phone", supplier.getPhone(), true),
                PatchInput.text(patch, "address", supplier.getAddress(), true)), validator);
        supplier.update(input, active); suppliers.flush(); return View.of(supplier);
    }
    private static ApiProblem missing() { return new ApiProblem(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier not found."); }
}
