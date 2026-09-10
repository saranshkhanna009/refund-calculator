package com.razorpay.refund.idempotency;

import java.util.Objects;

/**
 * Immutable idempotency key for refund operations.
 * Format: refund:{orderId}:{returnId}:{attempt}
 *
 * Used to prevent duplicate processing of:
 * - Customer retry clicks
 * - Webhook redeliveries (Razorpay retries 3x with exponential backoff)
 * - Internal service retries
 */
public final class IdempotencyKey {

    private final String value;

    public IdempotencyKey(String value) {
        this.value = Objects.requireNonNull(value);
    }

    public static IdempotencyKey of(String orderId, String returnId) {
        return new IdempotencyKey("refund:" + orderId + ":" + returnId);
    }

    public static IdempotencyKey of(String orderId, String returnId, int attempt) {
        return new IdempotencyKey("refund:" + orderId + ":" + returnId + ":" + attempt);
    }

    public static IdempotencyKey fromString(String key) {
        if (!key.startsWith("refund:")) {
            throw new IllegalArgumentException("Invalid idempotency key format: " + key);
        }
        return new IdempotencyKey(key);
    }

    public String getValue() {
        return value;
    }

    public String getOrderId() {
        String[] parts = value.split(":");
        return parts.length >= 2 ? parts[1] : null;
    }

    public String getReturnId() {
        String[] parts = value.split(":");
        return parts.length >= 3 ? parts[2] : null;
    }

    public int getAttempt() {
        String[] parts = value.split(":");
        return parts.length >= 4 ? Integer.parseInt(parts[3]) : 0;
    }

    public IdempotencyKey withAttempt(int attempt) {
        return new IdempotencyKey(value + ":" + attempt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyKey)) return false;
        IdempotencyKey that = (IdempotencyKey) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}