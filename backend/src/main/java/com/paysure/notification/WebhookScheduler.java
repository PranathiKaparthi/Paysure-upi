package com.paysure.notification;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "paysure.webhooks", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class WebhookScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookScheduler.class);

    private final WebhookDeliveryWorker worker;
    private final Clock clock;

    public WebhookScheduler(WebhookDeliveryWorker worker, Clock clock) {
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paysure.webhooks.poll-millis:1000}", initialDelay = 4000)
    void poll() {
        try {
            worker.deliverDue(clock.instant());
        } catch (RuntimeException e) {
            log.error("Webhook delivery poll failed", e);
        }
    }
}
