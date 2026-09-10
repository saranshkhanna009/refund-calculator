package com.razorpay.refund.service;

/**
 * Exception thrown when refund execution fails at gateway level.
 * Contains enough info for retry logic and alerting.
 */
public class RefundExecutionException extends Exception {

    private final String gatewayRefundId;
    private final boolean retryable;
    private final RefundBreakdown breakdown;

    public RefundExecutionException(String message) {
        this(message, null, false, null);
    }

    public RefundExecutionException(String message, Throwable cause) {
        this(message, null, false, cause);
    }

    public RefundExecutionException(String message, String gatewayRefundId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.gatewayRefundId = gatewayRefundId;
        this.retryable = retryable;
        this.breakdown = null;
    }

    public RefundExecutionException(String message, RefundBreakdown breakdown, boolean retryable, Throwable cause) {
        super(message, cause);
        this.gatewayRefundId = null;
        this.retryable = retryable;
        this.breakdown = breakdown;
    }

    public String getGatewayRefundId() {
        return gatewayRefundId;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public RefundBreakdown getBreakdown() {
        return breakdown;
    }

    public static RefundExecutionException gatewayError(String gatewayRefundId, String message, Throwable cause) {
        return new RefundExecutionException(message, gatewayRefundId, true, cause);
    }

    public static RefundExecutionException nonRetryable(String message) {
        return new RefundExecutionException(message, null, false, null);
    }
}