package com.razorpay.refund.saga.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls outbox table and publishes events to message broker.
 * Runs every 5 seconds, publishes in batches of 100.
 * Guarantees at-least-once delivery (idempotent consumers required).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final MessageBroker messageBroker;

    public OutboxPublisher(OutboxRepository outboxRepository, MessageBroker messageBroker) {
        this.outboxRepository = outboxRepository;
        this.messageBroker = messageBroker;
    }

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findUnpublished(100);

        if (events.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                messageBroker.publish(event.getEventType(), event.getAggregateId(), event.getPayload());
                event.markPublished();
                outboxRepository.save(event);
            } catch (Exception e) {
                log.warn("Failed to publish event {}: {}", event.getEventId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxRepository.save(event);
            }
        }
    }

    /**
     * Scheduled job to retry failed events (runs every 5 minutes).
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional
    public void retryFailedEvents() {
        List<OutboxEvent> events = outboxRepository.findFailedForRetry(50, 3); // Max 3 retries

        for (OutboxEvent event : events) {
            try {
                messageBroker.publish(event.getEventType(), event.getAggregateId(), event.getPayload());
                event.markPublished();
                outboxRepository.save(event);
                log.info("Retried and published event: {}", event.getEventId());
            } catch (Exception e) {
                log.warn("Retry failed for event {}: {}", event.getEventId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxRepository.save(event);
            }
        }
    }

    // Interfaces
    public interface OutboxRepository {
        List<OutboxEvent> findUnpublished(int limit);
        List<OutboxEvent> findFailedForRetry(int limit, int maxRetries);
        void save(OutboxEvent event);
    }

    public interface MessageBroker {
        void publish(String topic, String key, Object payload);
    }
}