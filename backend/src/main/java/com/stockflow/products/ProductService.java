package com.stockflow.products;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.stockflow.common.error.ApiProblem;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stockflow.products.ProductDtos.*;
import com.stockflow.common.PatchInput;
import com.stockflow.common.ListQuery;
import java.util.Map;
import jakarta.validation.Validator;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    public ProductService(ProductRepository products, JdbcTemplate jdbc, Validator validator) { this.products = products; this.jdbc = jdbc; this.validator = validator; }

    @Transactional(timeout = 10)
    public ProductView create(CreateProduct input) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        if (products.existsBySku(input.sku())) throw new ApiProblem(HttpStatus.CONFLICT, "DUPLICATE_SKU", "A product with this SKU already exists.");
        checkSupplier(input.supplierId());
        return ProductView.of(products.saveAndFlush(new Product(input.sku(), input.name(), input.description(),
                new BigDecimal(input.unitPrice()).setScale(2), input.lowStockThreshold(), input.supplierId())));
    }

    private void checkSupplier(UUID id) {
        if (id != null) {
            List<Boolean> active = jdbc.query("SELECT active FROM suppliers WHERE id=? FOR SHARE",
                    (rs, index) -> rs.getBoolean(1), id);
            if (active.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier not found.");
            if (!active.getFirst()) throw new ApiProblem(HttpStatus.CONFLICT, "SUPPLIER_ARCHIVED", "This supplier is archived.");
        }
    }

    @Transactional(timeout = 10)
    public ProductView patch(UUID id, Map<String, Object> patch) {
        PatchInput.check(patch, "sku", "name", "description", "unitPrice", "lowStockThreshold", "supplierId", "active");
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        Product product = products.lockById(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found."));
        boolean active = PatchInput.bool(patch, "active", product.isActive());
        if (!product.isActive() && active) throw new ApiProblem(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED", "Archived products cannot be reactivated.");
        var values = PatchInput.validate(new CreateProduct(
                PatchInput.text(patch, "sku", product.getSku(), false), PatchInput.text(patch, "name", product.getName(), false),
                PatchInput.text(patch, "description", product.getDescription(), true),
                PatchInput.text(patch, "unitPrice", product.getUnitPrice().toPlainString(), false),
                PatchInput.integer(patch, "lowStockThreshold", product.getLowStockThreshold()),
                PatchInput.uuid(patch, "supplierId", product.getSupplierId())), validator);
        if (!values.sku().equals(product.getSku()) && products.existsBySku(values.sku()))
            throw new ApiProblem(HttpStatus.CONFLICT, "DUPLICATE_SKU", "A product with this SKU already exists.");
        if (!java.util.Objects.equals(values.supplierId(), product.getSupplierId())) checkSupplier(values.supplierId());
        product.update(values, active);
        products.flush();
        return ProductView.of(product);
    }

    public ProductView get(UUID id) {
        return products.findById(id).map(ProductView::of)
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found."));
    }

    public ProductPage list(String search, int page, int size, String sort, String active, UUID supplierId, boolean lowStock) {
        var pageable = ListQuery.page(page, size, sort, Set.of("name", "sku", "unitPrice", "stockQuantity", "createdAt"));
        String term = ListQuery.term(search);
        Boolean state = ListQuery.active(active);
        Specification<Product> filter = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.or(cb.like(cb.lower(root.get("name")), term, '\\'), cb.like(cb.lower(root.get("sku")), term, '\\')));
            if (state != null) predicates.add(cb.equal(root.get("active"), state));
            if (supplierId != null) predicates.add(cb.equal(root.get("supplierId"), supplierId));
            if (lowStock) { predicates.add(cb.isTrue(root.get("active"))); predicates.add(cb.lessThanOrEqualTo(root.get("stockQuantity"), root.get("lowStockThreshold"))); }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return ProductPage.of(products.findAll(filter, pageable));
    }
}
