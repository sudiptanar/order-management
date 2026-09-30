package com.oms.common.messaging.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The outbox poller is a scheduled task, so scheduling has to be switched on. */
@Configuration
@EnableScheduling
public class OutboxConfig {
}