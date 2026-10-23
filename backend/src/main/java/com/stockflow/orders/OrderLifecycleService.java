package com.stockflow.orders;

import java.time.Instant;
import java.util.*;
import com.stockflow.common.error.ApiProblem;
import com.stockflow.inventory.MovementRepository;
import com.stockflow.inventory.StockMovement;
import com.stockflow.products.Product;
import com.stockflow.products.ProductRepository;
import com.stockflow.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.stockflow.orders.OrderDtos.*;

@Service
public class OrderLifecycleService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final MovementRepository movements;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    public OrderLifecycleService(OrderRepository orders, OrderItemRepository items, ProductRepository products,
            MovementRepository movements, UserRepository users, JdbcTemplate jdbc) {
        this.orders = orders; this.items = items; this.products = products; this.movements = movements; this.users = users; this.jdbc = jdbc;
    }

    @Transactional(timeout = 10)
    public View confirm(UUID id, UUID actor) { return transition(id, actor, false); }

    @Transactional(timeout = 10)
    public View cancel(UUID id, UUID actor) { return transition(id, actor, true); }

    private View transition(UUID id, UUID actor, boolean cancellation) {
        users.findById(actor).filter(user -> user.isActive())
                .orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please sign in again."));
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        // Never load the order before acquiring its lock: repeats must see the committed state.
        var order = orders.lockById(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found."));
        var lines = items.findByOrderIdOrderBySkuSnapshotAscIdAsc(id);
        if (order.getStatus().equals(cancellation ? "CANCELLED" : "CONFIRMED")) return View.of(order, lines);
        if (!cancellation && !order.getStatus().equals("DRAFT"))
            throw new ApiProblem(HttpStatus.CONFLICT, "INVALID_ORDER_STATE", "A cancelled order cannot be confirmed.");

        var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!cancellation || order.getStatus().equals("CONFIRMED")) {
            // Match draft-creation UUID ordering. Order lock always precedes product locks.
            var sorted = lines.stream().sorted(Comparator.comparing(line -> line.getProductId().toString())).toList();
            Map<UUID, Product> locked = new HashMap<>();
            for (var line : sorted) locked.put(line.getProductId(), products.lockById(line.getProductId())
                    .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "An order product was not found.")));
            // Validate every product after locking, before changing any balance.
            for (var line : sorted) {
                var product = locked.get(line.getProductId());
                if (!cancellation && !product.isActive())
                    throw new ApiProblem(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED", "An order product is archived. This draft cannot be confirmed.");
                long next = (long) product.getStockQuantity() + (cancellation ? line.getQuantity() : -line.getQuantity());
                if (next < 0) throw new ApiProblem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Not enough stock for " + line.getSkuSnapshot() + ". Refresh availability before trying again.");
                if (next > Integer.MAX_VALUE) throw new ApiProblem(HttpStatus.CONFLICT, "STOCK_LIMIT_EXCEEDED", "Restoring this order would exceed the supported stock limit. An administrator must reconcile availability first.");
            }
            at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for (var line : sorted) {
                locked.get(line.getProductId()).applyStockMovement(cancellation ? line.getQuantity() : -line.getQuantity());
                movements.saveAndFlush(StockMovement.forOrder(line.getProductId(), id, cancellation, line.getQuantity(), order.getOrderNumber(), actor, at));
            }
        }
        if (cancellation) order.cancel(actor, at); else order.confirm(actor, at);
        // Flush here so any constraint/insert failure is propagated through the transaction proxy.
        // Controller advice translates lock failures only after that proxy has rolled back.
        products.flush(); orders.flush();
        return View.of(order, lines);
    }
}
