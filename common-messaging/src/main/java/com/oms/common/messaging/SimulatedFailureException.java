package com.oms.common.messaging;

/**
 * Raised on purpose by the failure simulators, so the retry-then-dead-letter path can be
 * demonstrated on demand instead of waiting for a real outage.
 */
public class SimulatedFailureException extends RetryableMessageException {

    public SimulatedFailureException(String message) {
        super(message);
    }
}