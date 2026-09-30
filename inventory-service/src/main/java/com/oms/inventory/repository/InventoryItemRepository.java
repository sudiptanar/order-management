package com.oms.inventory.repository;

import com.oms.inventory.domain.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    /**
     * Row-level {@code SELECT ... FOR UPDATE}.
     *
     * <p>Optimistic locking alone would be wrong here. Two concurrent orders for the last unit
     * would both read "1 available", both pass the check, and one would fail at commit with an
     * unhelpful version conflict. Taking the lock before checking makes the read-then-write
     * genuinely atomic.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.productId = :productId")
    Optional<InventoryItem> lockByProductId(@Param("productId") String productId);

    List<InventoryItem> findAllByOrderByProductIdAsc();
}