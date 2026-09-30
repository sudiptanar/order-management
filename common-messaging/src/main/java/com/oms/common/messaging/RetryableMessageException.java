package com.oms.common.messaging;

/**
 * Thrown for a failure that a later attempt might survive: a downstream timeout, a lock
 * contention, a temporarily unreachable dependency. These go through the backoff and only
 * reach the dead-letter topic once the attempts are exhausted.
 */
public class RetryableMessageException extends RuntimeException {

    public RetryableMessageException(String message) {
        super(message);
    }

    public RetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}