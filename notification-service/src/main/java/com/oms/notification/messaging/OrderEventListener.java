package com.oms.notification.messaging;

import com.oms.common.events.OrderEvent;
import com.oms.common.events.Topics;
import com.oms.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private final NotificationService notificationService;

    public OrderEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS,
            id = "order-events",
            groupId = NotificationService.CONSUMER_GROUP)
    public void onOrderEvent(OrderEvent event) {
        notificationService.handle(event);
    }
}