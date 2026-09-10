package com.razorpay.refund.service;

import com.razorpay.refund.idempotency.IdempotencyKey;
import com.razorpay.refund.idempotency.IdempotencyStore;
import com.razorpay.refund.idempotency.IdempotencyException;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * High-level refund service with built-in idempotency.
 *
 * Flow:
 * 1. Generate idempotency key from orderId + returnId
 * 2. Try to acquire lock (prevents concurrent processing)
 * 3. If duplicate -> return cached result immediately
 * 4. Calculate refund using RefundCalculator
 * 5. Execute refund (gateway calls, ledger entries) - OUTSIDE this class
 * 6. Store result in idempotency store
 * 7. Release lock
 *
 * Thread-safe: Multiple calls with same key -> only ONE processes.
 * Crash-safe: If process crashes after step 4, lock expires, next retry picks up.
 */
@Service
public class IdempotentRefundService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRefundService.class);

    private final RefundCalculator calculator;
    private final IdempotencyStore idempotencyStore;
    private final RefundExecutor refundExecutor; // Interface for actual gateway calls

    public IdempotentRefundService(
            RefundCalculator calculator,
            IdempotencyStore idempotencyStore,
            RefundExecutor refundExecutor) {
        this.calculator = calculator;
        this.idempotencyStore = idempotencyStore;
        this.refundExecutor = refundExecutor;
    }

    /**
     * Process a refund with full idempotency guarantee.
     *
     * @param orderId       original order ID
     * @param returnId      return request ID (unique per return attempt)
     * @param orderTotal    original order amount
     * @param allocation    how customer originally paid
     * @param returnAmount  amount being returned (full or partial)
     * @return refund breakdown (cached if duplicate)
     */
    public RefundBreakdown processRefund(
            String orderId,
            String returnId,
            Money orderTotal,
            PaymentAllocation allocation,
            Money returnAmount) {

        IdempotencyKey key = IdempotencyKey.of(orderId, returnId);
        log.info("Processing refund: key={}", key);

        // 1. Try to acquire idempotency lock
        if (!idempotencyStore.tryAcquire(key, 86400)) { // 24h TTL
            log.warn("Duplicate or concurrent request detected: {}", key);
            return getCachedResult(key);
        }

        try {
            // 2. Calculate refund breakdown
            RefundBreakdown breakdown = calculator.calculate(orderTotal, allocation, returnAmount);
            log.info("Calculated refund: totalCustomerRefund={}", breakdown.getTotalCustomerRefund());

            // 3. Execute actual refund (gateway calls, ledger, notifications)
            // This is where you'd call Razorpay Refund API, update database, etc.
            refundExecutor.execute(breakdown);

            // 4. Store successful result
            idempotencyStore.storeResult(key, breakdown, 86400); // 24h TTL
            log.info("Refund completed and cached: {}", key);

            return breakdown;

        } catch (Exception e) {
            // 5. Mark as failed (allows retry after TTL)
            log.error("Refund failed for {}: {}", key, e.getMessage());
            idempotencyStore.markFailed(key, e.getMessage(), 3600); // 1h TTL for retry
            throw new RefundProcessingException("Refund failed: " + e.getMessage(), e);
        }
    }

    /**
     * Full return convenience method.
     */
    public RefundBreakdown processFullRefund(
            String orderId,
            String returnId,
            Money orderTotal,
            PaymentAllocation allocation) {
        return processRefund(orderId, returnId, orderTotal, allocation, orderTotal);
    }

    /**
     * Get cached result for duplicate requests.
     */
    private RefundBreakdown getCachedResult(IdempotencyKey key) {
        return idempotencyStore.getResult(key)
                .orElseThrow(() -> new IdempotencyException(
                        "Duplicate request but no cached result for: " + key));
    }

    /**
     * Check status without processing (for polling/UI).
     */
    public IdempotencyStore.Status getStatus(String orderId, String returnId) {
        return idempotencyStore.getStatus(IdempotencyKey.of(orderId, returnId));
    }
}