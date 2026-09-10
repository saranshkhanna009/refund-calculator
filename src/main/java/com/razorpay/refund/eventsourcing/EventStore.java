package com.razorpay.refund.eventsourcing;





import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;



/**
 * Event store for persisting and retrieving domain events.
 * Provides optimistic locking via version numbers.
 */
public interface EventStore {

    /**
     * Append events to an aggregate's event stream.
     * Fails if expectedVersion doesn't match (optimistic locking).
     */
    void appendEvents(String aggregateId, int expectedVersion, List<RefundEvent> events);

    /**
     * Get all events for an aggregate.
     */
    List<RefundEvent> getEvents(String aggregateId);

    /**
     * Get events from a specific version (for snapshots/projections).
     */
    List<RefundEvent> getEventsFromVersion(String aggregateId, int fromVersion);

    /**
     * Get events by correlation ID (for distributed tracing).
     */
    List<RefundEvent> getEventsByCorrelationId(String correlationId);

    /**
     * Get events by event type (for debugging/auditing).
     */
    List<RefundEvent> getEventsByType(String eventType, LocalDateTime from, LocalDateTime to);

    // ===== Test Implementation =====

    class InMemoryEventStore implements EventStore {
        private final Map<String, List<RefundEvent>> streams = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Integer> versions = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void appendEvents(String aggregateId, int expectedVersion, List<RefundEvent> events) {
            int currentVersion = versions.getOrDefault(aggregateId, 0);
            if (currentVersion != expectedVersion) {
                throw new OptimisticLockException(
                        "Version mismatch for " + aggregateId + ": expected " + expectedVersion + ", found " + currentVersion);
            }

            List<RefundEvent> existing = streams.getOrDefault(aggregateId, new java.util.ArrayList<>());
            List<RefundEvent> updated = new java.util.ArrayList<>(existing);
            updated.addAll(events);
            streams.put(aggregateId, updated);
            versions.put(aggregateId, currentVersion + events.size());
        }

        @Override
        public List<RefundEvent> getEvents(String aggregateId) {
            return List.copyOf(streams.getOrDefault(aggregateId, List.of()));
        }

        @Override
        public List<RefundEvent> getEventsFromVersion(String aggregateId, int fromVersion) {
            return streams.getOrDefault(aggregateId, List.of()).stream()
                    .filter(e -> e.getVersion() >= fromVersion)
                    .toList();
        }

        @Override
        public List<RefundEvent> getEventsByCorrelationId(String correlationId) {
            return streams.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> correlationId.equals(e.getCorrelationId()))
                    .toList();
        }

        @Override
        public List<RefundEvent> getEventsByType(String eventType, LocalDateTime from, LocalDateTime to) {
            return streams.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> e.getEventType().equals(eventType))
                    .filter(e -> e.getTimestamp().isAfter(from) && e.getTimestamp().isBefore(to))
                    .toList();
        }

        public void clear() {
            streams.clear();
            versions.clear();
        }
    }

    class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) { super(message); }
    }
}