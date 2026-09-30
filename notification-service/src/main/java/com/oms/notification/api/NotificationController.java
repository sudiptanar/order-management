package com.oms.notification.api;

import com.oms.notification.domain.Notification;
import com.oms.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public record NotificationView(UUID id, UUID orderId, String customerId, String channel,
                                   String eventType, String subject, String body, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getOrderId(), n.getCustomerId(), n.getChannel(),
                    n.getEventType(), n.getSubject(), n.getBody(), n.getCreatedAt());
        }
    }

    @GetMapping
    public List<NotificationView> recent(@RequestParam(defaultValue = "50") int limit) {
        return notificationService.recent(Math.min(limit, 200)).stream()
                .map(NotificationView::of).toList();
    }

    @GetMapping("/order/{orderId}")
    public List<NotificationView> forOrder(@PathVariable UUID orderId) {
        return notificationService.forOrder(orderId).stream().map(NotificationView::of).toList();
    }
}