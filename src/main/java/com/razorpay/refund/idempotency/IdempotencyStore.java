package com.razorpay.refund.idempotency;

import com.razorpay.refund.service.RefundBreakdown;

import java.time.Instant;
import java.util.Optional;

/**
 * Interface for idempotency storage. Implementations:
 * - Redis (production) - distributed, TTL support
 * - In-memory (testing) - fast, no external deps
 * - Database (audit) - permanent record
 */
public interface IdempotencyStore {

    /**
     * Try to reserve the key for processing.
     * @return true if this is the first request (we own the lock), false if duplicate
     */
    boolean tryAcquire(IdempotencyKey key, long ttlSeconds);

    /**
     * Store the successful result. Called after refund completes.
     */
    void storeResult(IdempotencyKey key, RefundBreakdown result, long ttlSeconds);

    /**
     * Get cached result if exists.
     */
    Optional<RefundBreakdown> getResult(IdempotencyKey key);

    /**
     * Mark as failed (allows retry with same key after TTL).
     */
    void markFailed(IdempotencyKey key, String error, long ttlSeconds);

    /**
     * Check if key exists (for monitoring/debugging).
     */
    boolean exists(IdempotencyKey key);

    /**
     * Get status without result (PROCESSING, COMPLETED, FAILED, EXPIRED).
     */
    Status getStatus(IdempotencyKey key);

    enum Status {
        UNKNOWN,      // Key not found
        PROCESSING,   // Acquired but not yet completed
        COMPLETED,    // Result stored
        FAILED,       // Marked failed, can retry
        EXPIRED       // TTL passed, can retry
    }
}