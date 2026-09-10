package com.razorpay.refund.idempotency;

import com.razorpay.refund.service.RefundBreakdown;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed idempotency store using Redisson.
 *
 * Key design:
 * - Lock key: "idempotency:lock:{key}" - prevents concurrent processing
 * - Status key: "idempotency:status:{key}" - PROCESSING/COMPLETED/FAILED
 * - Result key: "idempotency:result:{key}" - serialized RefundBreakdown
 *
 * TTL strategy:
 * - Lock: 30s (should complete well before)
 * - Status/Result: 24h (covers webhook retry window)
 * - Failed: 1h (allows quick retry)
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private final RedissonClient redisson;
    private static final String LOCK_PREFIX = "idempotency:lock:";
    private static final String STATUS_PREFIX = "idempotency:status:";
    private static final String RESULT_PREFIX = "idempotency:result:";

    public RedisIdempotencyStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public boolean tryAcquire(IdempotencyKey key, long ttlSeconds) {
        RLock lock = redisson.getLock(LOCK_PREFIX + key.getValue());

        // Try to acquire lock with short timeout (fail fast if another request is processing)
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (!acquired) {
            return false; // Another request is processing
        }

        try {
            // Double-check: maybe result already exists (completed before we got lock)
            if (exists(key)) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                return false;
            }

            // Mark as PROCESSING
            RBucket<String> statusBucket = redisson.getBucket(STATUS_PREFIX + key.getValue());
            statusBucket.set("PROCESSING", Duration.ofSeconds(ttlSeconds));

            return true; // We own this request
        } catch (Exception e) {
            // Release lock on error
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            throw new IdempotencyException("Failed to acquire idempotency lock", e);
        }
    }

    @Override
    public void storeResult(IdempotencyKey key, RefundBreakdown result, long ttlSeconds) {
        String fullKey = RESULT_PREFIX + key.getValue();
        RBucket<RefundBreakdown> bucket = redisson.getBucket(fullKey);
        bucket.set(result, Duration.ofSeconds(ttlSeconds));

        // Update status
        RBucket<String> statusBucket = redisson.getBucket(STATUS_PREFIX + key.getValue());
        statusBucket.set("COMPLETED", Duration.ofSeconds(ttlSeconds));

        // Release lock
        releaseLock(key);
    }

    @Override
    public Optional<RefundBreakdown> getResult(IdempotencyKey key) {
        RBucket<RefundBreakdown> bucket = redisson.getBucket(RESULT_PREFIX + key.getValue());
        RefundBreakdown result = bucket.get();
        return Optional.ofNullable(result);
    }

    @Override
    public void markFailed(IdempotencyKey key, String error, long ttlSeconds) {
        RBucket<String> statusBucket = redisson.getBucket(STATUS_PREFIX + key.getValue());
        statusBucket.set("FAILED:" + error, Duration.ofSeconds(ttlSeconds));

        releaseLock(key);
    }

    @Override
    public boolean exists(IdempotencyKey key) {
        return redisson.getBucket(STATUS_PREFIX + key.getValue()).isExists()
                || redisson.getBucket(RESULT_PREFIX + key.getValue()).isExists();
    }

    @Override
    public Status getStatus(IdempotencyKey key) {
        RBucket<String> statusBucket = redisson.getBucket(STATUS_PREFIX + key.getValue());
        String status = statusBucket.get();

        if (status == null) {
            // Check if result exists (completed but status expired)
            if (redisson.getBucket(RESULT_PREFIX + key.getValue()).isExists()) {
                return Status.COMPLETED;
            }
            return Status.UNKNOWN;
        }

        if (status.equals("PROCESSING")) {
            return Status.PROCESSING;
        }
        if (status.equals("COMPLETED")) {
            return Status.COMPLETED;
        }
        if (status.startsWith("FAILED")) {
            return Status.FAILED;
        }
        return Status.UNKNOWN;
    }

    private void releaseLock(IdempotencyKey key) {
        RLock lock = redisson.getLock(LOCK_PREFIX + key.getValue());
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}