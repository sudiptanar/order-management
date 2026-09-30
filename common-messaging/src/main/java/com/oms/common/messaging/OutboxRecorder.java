package com.oms.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a service is allowed to emit a message: record the intent in the same transaction
 * as the state change, and let {@link OutboxPublisher} do the sending.
 *
 * <p>{@link Propagation#MANDATORY} enforces that - a call from outside a transaction fails loudly
 * rather than quietly reintroducing the dual-write it exists to prevent.
 */
@Service
public class OutboxRecorder {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxMessage record(String topic, String messageKey, Object payload) {
        return record(topic, messageKey, payload, payload.getClass().getSimpleName(), messageKey);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxMessage record(String topic, String messageKey, Object payload,
                                String aggregateType, String aggregateId) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxMessage message = new OutboxMessage(
                    aggregateType, aggregateId, topic, messageKey, payload.getClass().getName(), json);
            return repository.save(message);
        } catch (JsonProcessingException e) {
            // A payload we cannot serialize will never serialize. Do not retry it.
            throw new NonRetryableMessageException(
                    "Cannot serialize outbox payload of type " + payload.getClass().getName(), e);
        }
    }
}