package com.razorpay.refund.idempotency;

import com.razorpay.refund.service.RefundBreakdown;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory idempotency store for testing and local development.
 * Thread-safe using ConcurrentHashMap + per-key ReentrantLock.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(IdempotencyKey key, long ttlSeconds) {
        String k = key.getValue();
        ReentrantLock lock = locks.computeIfAbsent(k, unused -> new ReentrantLock());

        // Try to acquire lock (non-blocking for test speed)
        if (!lock.tryLock()) {
            return false;
        }

        try {
            Entry existing = store.get(k);
            if (existing != null && existing.status == Status.COMPLETED) {
                return false; // Already processed
            }
            if (existing != null && existing.status == Status.PROCESSING) {
                return false; // Currently processing
            }

            store.put(k, new Entry(Status.PROCESSING, null, null));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void storeResult(IdempotencyKey key, RefundBreakdown result, long ttlSeconds) {
        String k = key.getValue();
        ReentrantLock lock = locks.computeIfAbsent(k, unused -> new ReentrantLock());
        lock.lock();
        try {
            store.put(k, new Entry(Status.COMPLETED, result, null));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<RefundBreakdown> getResult(IdempotencyKey key) {
        Entry entry = store.get(key.getValue());
        return entry != null ? Optional.ofNullable(entry.result) : Optional.empty();
    }

    @Override
    public void markFailed(IdempotencyKey key, String error, long ttlSeconds) {
        String k = key.getValue();
        ReentrantLock lock = locks.computeIfAbsent(k, unused -> new ReentrantLock());
        lock.lock();
        try {
            store.put(k, new Entry(Status.FAILED, null, error));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean exists(IdempotencyKey key) {
        return store.containsKey(key.getValue());
    }

    @Override
    public Status getStatus(IdempotencyKey key) {
        Entry entry = store.get(key.getValue());
        return entry != null ? entry.status : Status.UNKNOWN;
    }

    // Test helper: clear all
    public void clear() {
        store.clear();
        locks.clear();
    }

    private static class Entry {
        final Status status;
        final RefundBreakdown result;
        final String error;

        Entry(Status status, RefundBreakdown result, String error) {
            this.status = status;
            this.result = result;
            this.error = error;
        }
    }
}