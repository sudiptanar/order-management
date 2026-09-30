package com.oms.inventory.repository;

import com.oms.inventory.domain.InventoryReservation;
import com.oms.inventory.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderId(UUID orderId);

    List<InventoryReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    boolean existsByOrderIdAndStatus(UUID orderId, ReservationStatus status);
}