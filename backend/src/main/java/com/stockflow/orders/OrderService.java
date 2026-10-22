package com.stockflow.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.stockflow.common.ListQuery;
import com.stockflow.common.PatchInput;
import com.stockflow.common.error.ApiProblem;
import com.stockflow.products.Product;
import com.stockflow.products.ProductRepository;
import com.stockflow.users.UserRepository;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stockflow.orders.OrderDtos.*;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final String currency;
    public OrderService(OrderRepository orders, OrderItemRepository items, ProductRepository products, UserRepository users,
            JdbcTemplate jdbc, Validator validator, @Value("${app.currency:LKR}") String currency) {
        this.orders = orders; this.items = items; this.products = products; this.users = users; this.jdbc = jdbc; this.validator = validator;
        this.currency = currency.trim().toUpperCase(Locale.ROOT);
        if (Currency.getInstance(this.currency).getDefaultFractionDigits() != 2)
            throw new IllegalArgumentException("APP_CURRENCY must be an ISO currency with two fractional digits.");
    }

    @Transactional(timeout = 10)
    public View create(Create input, UUID actorId) {
        PatchInput.validate(input, validator);
        users.findById(actorId).filter(user -> user.isActive())
                .orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please sign in again."));
        Set<UUID> unique = new HashSet<>();
        for (var item : input.items()) if (!unique.add(item.productId()))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "DUPLICATE_ORDER_PRODUCT", "Each product may appear only once in an order.");
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        Map<UUID, Product> locked = new HashMap<>();
        // Hexadecimal UUID ordering matches PostgreSQL's ordering and future transition locks.
        for (UUID productId : unique.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            Product product = products.lockById(productId)
                    .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "An order product was not found."));
            if (!product.isActive()) throw new ApiProblem(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED", "An order product is archived. Remove it before creating the draft.");
            locked.put(productId, product);
        }
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (var item : input.items()) total = total.add(locked.get(item.productId()).getUnitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        Long number = jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class);
        var order = orders.saveAndFlush(new CustomerOrder(String.format(Locale.ROOT, "SF-%08d", number), input.customerName(), input.reference(), currency, total.setScale(2), actorId));
        var snapshots = input.items().stream().map(item -> new OrderItem(order.getId(), locked.get(item.productId()), item.quantity())).toList();
        items.saveAllAndFlush(snapshots);
        // Locks protect a consistent snapshot only: drafts never reserve or change stock.
        return View.of(order, items.findByOrderIdOrderBySkuSnapshotAscIdAsc(order.getId()));
    }

    public View get(UUID id) {
        var order = orders.findById(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found."));
        return View.of(order, items.findByOrderIdOrderBySkuSnapshotAscIdAsc(id));
    }

    public Page list(String status, Instant createdFrom, Instant createdTo, int page, int size, String sort) {
        var pageable = ListQuery.page(page, size, sort, Set.of("createdAt", "orderNumber", "total"));
        if (!status.isEmpty() && !Set.of("DRAFT", "CONFIRMED", "CANCELLED").contains(status))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS", "Status must be DRAFT, CONFIRMED or CANCELLED, or omitted for all.");
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "createdFrom must be before the exclusive createdTo timestamp.");
        Specification<CustomerOrder> filter = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!status.isEmpty()) predicates.add(cb.equal(root.get("status"), status));
            if (createdFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            if (createdTo != null) predicates.add(cb.lessThan(root.get("createdAt"), createdTo));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = orders.findAll(filter, pageable);
        return new Page(result.getContent().stream().map(Summary::of).toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
