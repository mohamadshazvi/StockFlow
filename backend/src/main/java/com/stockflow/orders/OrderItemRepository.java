package com.stockflow.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface OrderItemRepository extends Repository<OrderItem, UUID> {
    List<OrderItem> saveAllAndFlush(Iterable<OrderItem> items);
    List<OrderItem> findByOrderIdOrderBySkuSnapshotAscIdAsc(UUID orderId);
}
