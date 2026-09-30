package com.oms.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The outbox and idempotency ledger live in {@code common-messaging}, outside this application's
 * package. Component scanning is widened to {@code com.oms}, and entity/repository scanning has to
 * be listed explicitly because it otherwise defaults to this class's own package.
 */
@SpringBootApplication(scanBasePackages = "com.oms")
@EntityScan({"com.oms.order", "com.oms.common.messaging"})
@EnableJpaRepositories({"com.oms.order", "com.oms.common.messaging"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}