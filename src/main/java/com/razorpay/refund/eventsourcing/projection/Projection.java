package com.razorpay.refund.eventsourcing.projection;

import com.razorpay.refund.eventsourcing.RefundEvent;

/**
 * Interface for event projections (read models).
 * Each projection handles events it cares about and updates its read model.
 * Projections are eventually consistent - they process events asynchronously.
 */
public interface Projection {

    /**
     * Handle a domain event.
     * Implementations should be idempotent (same event processed multiple times = same result).
     */
    void handle(RefundEvent event);

    /**
     * Get projection name for logging/monitoring.
     */
    String getName();

    /**
     * Reset projection state (for testing/rebuilding).
     */
    void reset();
}