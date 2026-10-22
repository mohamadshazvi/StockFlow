package com.stockflow.suppliers;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> lockById(UUID id);
}
