package com.razorpay.refund.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock executor for testing idempotency without external dependencies.
 * Records all calls and can simulate failures.
 */
public class MockRefundExecutor implements RefundExecutor {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicReference<RefundBreakdown> lastBreakdown = new AtomicReference<>();
    private final AtomicReference<Exception> errorToThrow = new AtomicReference<>();

    @Override
    public void execute(RefundBreakdown breakdown) throws RefundExecutionException {
        callCount.incrementAndGet();
        lastBreakdown.set(breakdown);

        Exception error = errorToThrow.getAndSet(null);
        if (error != null) {
            if (error instanceof RefundExecutionException) {
                throw (RefundExecutionException) error;
            }
            throw new RefundExecutionException("Mock error: " + error.getMessage(), error);
        }
    }

    @Override
    public boolean isRefunded(String gatewayRefundId) {
        return false;
    }

    // Test helpers
    public int getCallCount() {
        return callCount.get();
    }

    public RefundBreakdown getLastBreakdown() {
        return lastBreakdown.get();
    }

    public void setErrorToThrow(Exception error) {
        errorToThrow.set(error);
    }

    public void reset() {
        callCount.set(0);
        lastBreakdown.set(null);
        errorToThrow.set(null);
    }
}
