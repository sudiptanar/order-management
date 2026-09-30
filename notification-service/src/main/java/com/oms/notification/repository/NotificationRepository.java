package com.oms.notification.repository;

import com.oms.notification.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);
}