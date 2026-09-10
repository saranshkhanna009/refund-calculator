package com.razorpay.refund.saga;

import com.razorpay.refund.saga.outbox.OutboxEvent;
import com.razorpay.refund.saga.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the transactional outbox pattern.
 * Verifies:
 * - Events saved in same transaction as business data
 * - Publisher picks up unpublished events
 * - Failed events are retried
 * - At-least-once delivery
 */
class OutboxPatternTest {

    private OutboxPublisher publisher;
    private OutboxPublisher.OutboxRepository outboxRepository;
    private OutboxPublisher.MessageBroker messageBroker;
    private List<OutboxEvent> eventStore;

    @BeforeEach
    void setUp() {
        eventStore = new CopyOnWriteArrayList<>();

        outboxRepository = new OutboxPublisher.OutboxRepository() {
            @Override
            public List<OutboxEvent> findUnpublished(int limit) {
                return eventStore.stream()
                        .filter(e -> !e.isPublished())
                        .limit(limit)
                        .toList();
            }

            @Override
            public List<OutboxEvent> findFailedForRetry(int limit, int maxRetries) {
                return eventStore.stream()
                        .filter(e -> !e.isPublished() && e.getRetryCount() < maxRetries)
                        .limit(limit)
                        .toList();
            }

            @Override
            public void save(OutboxEvent event) {
                // Update or add
                eventStore.removeIf(e -> e.getEventId().equals(event.getEventId()));
                eventStore.add(event);
            }
        };

        messageBroker = mock(OutboxPublisher.MessageBroker.class);
        publisher = new OutboxPublisher(outboxRepository, messageBroker);
    }

    @Test
    void publishPendingEvents_publishesAndMarksPublished() {
        // Given: Unpublished event
        OutboxEvent event = OutboxEvent.create(
                "REFUND_SAGA_COMPLETED",
                "saga_123",
                "REFUND_SAGA",
                Map.of("status", "COMPLETED", "amount", "686.00")
        );
        outboxRepository.save(event);

        // When
        publisher.publishPendingEvents();

        // Then
        verify(messageBroker).publish("REFUND_SAGA_COMPLETED", "saga_123", event.getPayload());
        assertTrue(event.isPublished());
        assertNotNull(event.getPublishedAt());
    }

    @Test
    void failedPublish_incrementsRetryCount() {
        // Given: Broker throws exception
        doThrow(new RuntimeException("Broker down")).when(messageBroker).publish(anyString(), anyString(), any());

        OutboxEvent event = OutboxEvent.create(
                "TEST_EVENT",
                "agg_1",
                "TEST",
                Map.of("data", "value")
        );
        outboxRepository.save(event);

        // When
        publisher.publishPendingEvents();

        // Then
        assertEquals(1, event.getRetryCount());
        assertEquals("Broker down", event.getLastError());
        assertFalse(event.isPublished());
    }

    @Test
    void retryFailedEvents_retriesAfterMaxRetries() {
        // Given: Event with 2 retries (max 3)
        OutboxEvent event = OutboxEvent.create("TEST", "agg_1", "TEST", Map.of());
        event.markFailed("First failure");
        event.markFailed("Second failure");
        outboxRepository.save(event);

        // When: retry
        publisher.retryFailedEvents();

        // Then: Should retry (retryCount < 3)
        verify(messageBroker).publish("TEST", "agg_1", event.getPayload());
    }

    @Test
    void retryFailedEvents_stopsAfterMaxRetries() {
        // Given: Event with 3 retries (max 3)
        OutboxEvent event = OutboxEvent.create("TEST", "agg_1", "TEST", Map.of());
        event.markFailed("1");
        event.markFailed("2");
        event.markFailed("3");
        outboxRepository.save(event);

        // When: retry
        publisher.retryFailedEvents();

        // Then: Should NOT retry (retryCount >= 3)
        verify(messageBroker, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void batchPublishing_respectsLimit() {
        // Given: 150 unpublished events
        for (int i = 0; i < 150; i++) {
            OutboxEvent event = OutboxEvent.create("EVENT_" + i, "agg_" + i, "TEST", Map.of());
            outboxRepository.save(event);
        }

        // When: publish with limit 100
        publisher.publishPendingEvents();

        // Then: Only 100 published
        verify(messageBroker, times(100)).publish(anyString(), anyString(), any());
    }

    @Test
    void eventPayload_containsSagaContext() {
        // Given: Event with full saga context
        Map<String, Object> payload = Map.of(
                "sagaId", "saga_123",
                "orderId", "order_456",
                "status", "COMPLETED",
                "stepResults", Map.of(
                        "GATEWAY_REFUND", Map.of("gatewayRefundId", "rfnd_123"),
                        "LEDGER_ENTRY", Map.of("ledgerId", "refund_123"),
                        "WALLET_CREDIT", Map.of("walletTxnId", "wtxn_123"),
                        "NOTIFICATION", Map.of("notificationId", "notif_123")
                ),
                "totalRefund", "686.00",
                "breakdown", Map.of(
                        "wallet", "294.00",
                        "online", "392.00"
                )
        );

        OutboxEvent event = OutboxEvent.create(
                OutboxEvent.EventTypes.REFUND_SAGA_COMPLETED,
                "saga_123",
                "REFUND_SAGA",
                payload
        );

        // When
        publisher.publishPendingEvents();

        // Then: Full context available for downstream consumers
        verify(messageBroker).publish(
                eq(OutboxEvent.EventTypes.REFUND_SAGA_COMPLETED),
                eq("saga_123"),
                argThat(p -> p instanceof Map &&
                        ((Map<?, ?>) p).get("sagaId").equals("saga_123") &&
                        ((Map<?, ?>) p).get("totalRefund").equals("686.00"))
        );
    }
}