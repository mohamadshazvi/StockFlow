package com.stockflow.inventory;

import java.util.Set;
import java.util.UUID;
import com.stockflow.common.ListQuery;
import com.stockflow.common.PatchInput;
import com.stockflow.common.error.ApiProblem;
import com.stockflow.products.ProductRepository;
import com.stockflow.users.UserRepository;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stockflow.inventory.MovementDtos.*;

@Service
@Transactional(readOnly = true)
public class InventoryService {
    private final ProductRepository products;
    private final MovementRepository movements;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    public InventoryService(ProductRepository products, MovementRepository movements, UserRepository users, JdbcTemplate jdbc, Validator validator) {
        this.products = products; this.movements = movements; this.users = users; this.jdbc = jdbc; this.validator = validator;
    }

    @Transactional(timeout = 10)
    public View record(Input input, UUID actorId) {
        PatchInput.validate(input, validator);
        int delta = input.quantityDelta();
        if (delta == 0 || (input.type().equals("RECEIPT") && delta < 0))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Receipts require a positive quantity; adjustments require a nonzero signed quantity.");
        if (input.type().equals("ADJUSTMENT") && input.reason() == null)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason is required for stock adjustments.");
        var actor = users.findById(actorId).filter(u -> u.isActive())
                .orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please sign in again."));
        if (!actor.getRole().equals("ADMIN")) throw new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only administrators may change stock.");
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        var product = products.lockById(input.productId()).orElseThrow(() -> missing());
        if (!product.isActive()) throw new ApiProblem(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED", "Stock cannot be manually changed for an archived product.");
        long next = (long) product.getStockQuantity() + delta;
        if (next < 0) throw new ApiProblem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "The adjustment would make stock negative. Review current availability.");
        if (next > Integer.MAX_VALUE) throw new ApiProblem(HttpStatus.CONFLICT, "STOCK_LIMIT_EXCEEDED", "The resulting stock exceeds the supported maximum of 2147483647.");
        product.applyStockMovement(delta);
        products.flush();
        // Both writes share this transaction: an insert failure rolls the balance back.
        return View.of(movements.saveAndFlush(new StockMovement(product.getId(), input.type(), delta, input.reason(), input.reference(), actorId)));
    }

    public Page history(UUID productId, int page, int size, String sort) {
        var pageable = ListQuery.page(page, size, sort, Set.of("createdAt"));
        if (!products.existsById(productId)) throw missing();
        var result = movements.findByProductId(productId, pageable);
        return new Page(result.getContent().stream().map(View::of).toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    private static ApiProblem missing() { return new ApiProblem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found."); }
}
