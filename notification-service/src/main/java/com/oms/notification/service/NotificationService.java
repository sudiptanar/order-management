package com.oms.notification.service;

import com.oms.common.events.OrderEvent;
import com.oms.common.events.Topics;
import com.oms.common.messaging.IdempotencyGuard;
import com.oms.notification.domain.Notification;
import com.oms.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * A pure subscriber: it reacts to facts and never replies. Nothing in the saga waits for it, so it
 * can be down for an hour and orders still complete - it catches up from its own committed offset
 * when it returns. That independence is the payoff of publish/subscribe over direct calls.
 *
 * <p>It still needs the idempotency guard. Sending a customer two "order confirmed" emails because
 * of a consumer rebalance is exactly the kind of duplicate that at-least-once delivery produces.
 */
@Service
public class NotificationService {

    public static final String CONSUMER_GROUP = "notification-service.order-events";
    private static final String CHANNEL_EMAIL = "EMAIL";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final IdempotencyGuard idempotencyGuard;

    public NotificationService(NotificationRepository repository, IdempotencyGuard idempotencyGuard) {
        this.repository = repository;
        this.idempotencyGuard = idempotencyGuard;
    }

    @Transactional
    public void handle(OrderEvent event) {
        if (!idempotencyGuard.firstTimeSeeing(event.messageId(), CONSUMER_GROUP, Topics.ORDER_EVENTS)) {
            return;
        }

        Notification notification = render(event);
        repository.save(notification);

        // Stands in for the email/SMS gateway call. In a real service that call is a side effect
        // outside the database transaction, which needs its own care: send after commit, and make
        // the send itself idempotent with a provider-side key.
        log.info("EMAIL to customer {} | {} | {}",
                event.customerId(), notification.getSubject(), notification.getBody());
    }

    private Notification render(OrderEvent event) {
        String shortId = event.orderId().toString().substring(0, 8);
        String subject;
        String body;

        switch (event.type()) {
            case ORDER_CREATED -> {
                subject = "We have your order #" + shortId;
                body = "Thanks! Order " + event.orderId() + " for " + event.totalAmount()
                        + " is being processed. We will confirm shortly.";
            }
            case ORDER_CONFIRMED -> {
                subject = "Order #" + shortId + " is confirmed";
                body = "Good news - order " + event.orderId() + " is confirmed and paid ("
                        + event.totalAmount() + ").";
            }
            case ORDER_CANCELLED -> {
                subject = "Order #" + shortId + " could not be completed";
                body = "Sorry - order " + event.orderId() + " was cancelled. Reason: "
                        + (event.detail() == null ? "not specified" : event.detail())
                        + ". Nothing has been charged.";
            }
            default -> {
                subject = "Update on order #" + shortId;
                body = String.valueOf(event.detail());
            }
        }

        return new Notification(event.orderId(), event.customerId(), CHANNEL_EMAIL,
                event.type().name(), subject, body);
    }

    @Transactional(readOnly = true)
    public List<Notification> forOrder(UUID orderId) {
        return repository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(int limit) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }
}