package com.oms.payment.service;

import com.oms.common.messaging.SimulatedFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ordering as {@code CUST-BOOM} throws a retryable exception, so the command is retried with
 * backoff and then dead-lettered. See {@code docs/DEMO.md}.
 */
@Component
public class FailureSimulator {

    private final String failingCustomerId;

    public FailureSimulator(@Value("${oms.simulation.failing-customer-id:CUST-BOOM}") String failingCustomerId) {
        this.failingCustomerId = failingCustomerId;
    }

    public void failIfRequested(String customerId) {
        if (failingCustomerId.equals(customerId)) {
            throw new SimulatedFailureException(
                    "Simulated payment gateway timeout for customer " + failingCustomerId);
        }
    }
}