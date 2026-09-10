package com.razorpay.refund.eventsourcing;

import java.util.List;

/**
 * Interface for publishing domain events.
 * Implementations: Kafka, RabbitMQ, in-memory for testing.
 */
public interface EventPublisher {
    void publish(RefundEvent event);
    void publishBatch(List<RefundEvent> events);
    void close();
}