package com.oms.inventory.service;

import com.oms.common.messaging.SimulatedFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Collection;

/**
 * Makes the retry-then-dead-letter path reachable on demand.
 *
 * <p>Without a switch like this you cannot demonstrate the error handling at all - you would have
 * to wait for a real outage, or kill a container mid-saga and hope the timing lands. Ordering the
 * configured product throws a retryable exception every time, so the backoff runs and the command
 * lands in {@code order.commands.reserve-inventory.DLT}.
 *
 * <p>The product id is adjustable at runtime because that is what the dead-letter workflow
 * requires: you fix the cause first, then replay. Having to restart the service in between would
 * make the replay demo considerably less convincing.
 *
 * <p>This is a test hook and would not exist in a real service.
 */
@Component
public class FailureSimulator {

    private static final Logger log = LoggerFactory.getLogger(FailureSimulator.class);

    /** Volatile: written by an HTTP thread, read by Kafka listener threads. */
    private volatile String failingProductId;

    public FailureSimulator(@Value("${oms.simulation.failing-product-id:P-BOOM}") String failingProductId) {
        this.failingProductId = failingProductId;
    }

    public void failIfRequested(Collection<String> productIds) {
        String trigger = this.failingProductId;
        if (trigger != null && !trigger.isBlank() && productIds.contains(trigger)) {
            throw new SimulatedFailureException(
                    "Simulated transient inventory failure triggered by product " + trigger);
        }
    }

    public String getFailingProductId() {
        return failingProductId;
    }

    /** Pass an empty value to disable the simulator entirely. */
    public void setFailingProductId(String failingProductId) {
        log.warn("Failure simulator retargeted from '{}' to '{}'", this.failingProductId, failingProductId);
        this.failingProductId = failingProductId;
    }
}