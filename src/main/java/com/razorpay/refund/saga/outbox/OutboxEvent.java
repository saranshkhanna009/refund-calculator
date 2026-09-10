package com.razorpay.refund.saga.outbox;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Event stored in outbox table for reliable publishing.
 * Uses transactional outbox pattern: write event + business data in SAME transaction.
 * Separate publisher reads outbox and publishes to message broker (Kafka/RabbitMQ).
 */
public final class OutboxEvent {

    private final String eventId;
    private final String eventType;
    private final String aggregateId;      // Saga ID, Order ID, etc.
    private final String aggregateType;    // "REFUND_SAGA", "ORDER", etc.
    private final Map<String, Object> payload;
    private final LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private int retryCount;
    private String lastError;

    @JsonCreator
    public OutboxEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("aggregateId") String aggregateId,
            @JsonProperty("aggregateType") String aggregateType,
            @JsonProperty("payload") Map<String, Object> payload,
            @JsonProperty("createdAt") LocalDateTime createdAt,
            @JsonProperty("publishedAt") LocalDateTime publishedAt,
            @JsonProperty("retryCount") int retryCount,
            @JsonProperty("lastError") String lastError) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.retryCount = retryCount;
        this.lastError = lastError;
    }

    public static OutboxEvent create(String eventType, String aggregateId, String aggregateType, Map<String, Object> payload) {
        return new OutboxEvent(
                UUID.randomUUID().toString(),
                eventType,
                aggregateId,
                aggregateType,
                payload,
                LocalDateTime.now(),
                null,
                0,
                null
        );
    }

    // Getters
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public Map<String, Object> getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }

    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    // === Standard Event Types ===
    public static final class EventTypes {
        public static final String REFUND_SAGA_STARTED = "REFUND_SAGA_STARTED";
        public static final String REFUND_SAGA_COMPLETED = "REFUND_SAGA_COMPLETED";
        public static final String REFUND_SAGA_FAILED = "REFUND_SAGA_FAILED";
        public static final String REFUND_SAGA_COMPENSATED = "REFUND_SAGA_COMPENSATED";
        public static final String REFUND_SAGA_COMPENSATION_FAILED = "REFUND_SAGA_COMPENSATION_FAILED";
        public static final String REFUND_GATEWAY_CREATED = "REFUND_GATEWAY_CREATED";
        public static final String REFUND_LEDGER_CREATED = "REFUND_LEDGER_CREATED";
        public static final String REFUND_WALLET_CREDITED = "REFUND_WALLET_CREDITED";
        public static final String REFUND_NOTIFICATION_SENT = "REFUND_NOTIFICATION_SENT";
    }
}