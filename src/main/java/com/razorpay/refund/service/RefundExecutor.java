package com.razorpay.refund.service;

/**
 * Interface for executing the actual refund side effects.
 * Separated from calculation for testability and separation of concerns.
 *
 * Implementations:
 * - RazorpayRefundExecutor: calls Razorpay Refund API, updates ledger, sends webhooks
 * - MockRefundExecutor: for testing (records calls, no external deps)
 */
public interface RefundExecutor {

    /**
     * Execute the refund based on calculated breakdown.
     * Should be idempotent at the gateway level too (use gateway's idempotency key).
     *
     * @param breakdown the calculated refund amounts per payment method
     * @throws RefundExecutionException if any gateway call fails
     */
    void execute(RefundBreakdown breakdown) throws RefundExecutionException;

    /**
     * Check if a refund has already been processed at gateway level.
     * Used for reconciliation.
     */
    boolean isRefunded(String gatewayRefundId);
}