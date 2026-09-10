package com.razorpay.refund.idempotency;

/**
 * Exception thrown when idempotency operations fail.
 * Distinguishes between:
 * - Duplicate request (caller should return cached result)
 * - System error (caller should retry with backoff)
 */
public class IdempotencyException extends RuntimeException {

    private final boolean retryable;

    public IdempotencyException(String message) {
        this(message, false);
    }

    public IdempotencyException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public IdempotencyException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public IdempotencyException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static IdempotencyException duplicate(IdempotencyKey key) {
        return new IdempotencyException("Duplicate request for key: " + key);
    }

    public static IdempotencyException lockFailed(IdempotencyKey key) {
        return new IdempotencyException("Could not acquire lock for: " + key, true);
    }

    public static IdempotencyException systemError(String message, Throwable cause) {
        return new IdempotencyException(message, cause, true);
    }
}