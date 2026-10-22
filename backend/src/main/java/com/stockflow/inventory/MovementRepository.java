package com.stockflow.inventory;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** No update or delete operations are exposed for this append-only ledger. */
public interface MovementRepository extends Repository<StockMovement, UUID> {
    StockMovement saveAndFlush(StockMovement movement);
    Page<StockMovement> findByProductId(UUID productId, Pageable pageable);
}
