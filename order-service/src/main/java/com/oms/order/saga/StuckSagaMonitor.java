package com.oms.order.saga;

import com.oms.order.domain.SagaInstance;
import com.oms.order.domain.SagaState;
import com.oms.order.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sagas need timeouts. A participant can crash after consuming a command and before replying, and
 * then no message will ever arrive to move the state machine along - the saga simply sits there.
 *
 * <p>This job only reports. Deciding what to do about a stuck saga - resend the command, force
 * compensation, page someone - is a business call, and doing it automatically without that
 * decision is how you end up double-charging customers.
 */
@Component
public class StuckSagaMonitor {

    private static final Logger log = LoggerFactory.getLogger(StuckSagaMonitor.class);

    private static final List<SagaState> IN_FLIGHT = List.of(
            SagaState.AWAITING_INVENTORY,
            SagaState.AWAITING_PAYMENT,
            SagaState.COMPENSATING_PAYMENT,
            SagaState.COMPENSATING_INVENTORY);

    private final SagaInstanceRepository repository;
    private final Duration threshold;

    public StuckSagaMonitor(SagaInstanceRepository repository,
                            @Value("${oms.saga.stuck-after:PT2M}") Duration threshold) {
        this.repository = repository;
        this.threshold = threshold;
    }

    @Scheduled(fixedDelayString = "${oms.saga.monitor-interval-ms:30000}")
    @Transactional(readOnly = true)
    public void reportStuckSagas() {
        Instant cutoff = Instant.now().minus(threshold);
        List<SagaInstance> stuck = repository.findByStateInAndUpdatedAtBefore(IN_FLIGHT, cutoff);
        for (SagaInstance saga : stuck) {
            log.warn("STUCK SAGA saga={} order={} state={} step={} unchanged since {}",
                    saga.getSagaId(), saga.getOrderId(), saga.getState(),
                    saga.getCurrentStep(), saga.getUpdatedAt());
        }
    }
}