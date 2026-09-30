package com.oms.common.messaging;

/**
 * Thrown when a message can never succeed, no matter how many times it is redelivered:
 * malformed payload, unknown aggregate, violated invariant.
 *
 * <p>Retrying these is pure waste, so {@code KafkaErrorHandlingConfig} routes them straight
 * to the dead-letter topic with no backoff.
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}