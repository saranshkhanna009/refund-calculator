package com.razorpay.refund.service;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.policy.RefundPolicy;

import java.util.Map;
import java.util.Objects;

/**
 * Core refund calculation service. Stateless, thread-safe.
 * All business logic delegated to {@link RefundPolicy} — easy to test and swap.
 */
public final class RefundCalculator {

    private final RefundPolicy policy;

    public RefundCalculator(RefundPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    /**
     * Calculate refund breakdown for a full or partial return.
     *
     * @param orderTotal        original order amount
     * @param originalAllocation how the customer paid
     * @param returnAmount      amount being returned (≤ orderTotal). For full return, equals orderTotal.
     * @return detailed refund breakdown
     */
    public RefundBreakdown calculate(Money orderTotal, PaymentAllocation originalAllocation, Money returnAmount) {
        validateInputs(orderTotal, originalAllocation, returnAmount);

        // 1. Platform fee on the RETURN amount (not full order for partial returns)
        Money platformFee = policy.calculatePlatformFee(returnAmount);
        Money feeToRefund = policy.isPlatformFeeRefundable() ? platformFee : Money.zero();

        // 2. Refundable pool = return amount - non-refundable fee
        Money nonRefundableFee = platformFee.subtract(feeToRefund);
        Money refundablePool = returnAmount.subtract(nonRefundableFee);

        // 3. Split refundable pool across payment methods
        Map<PaymentMethod, Money> methodRefunds = policy.splitRefund(refundablePool, originalAllocation);

        // 4. Calculate GST on online portion of the RETURN
        Money onlineReturnPortion = methodRefunds.getOrDefault(PaymentMethod.ONLINE, Money.zero());
        Money gstAmount = policy.calculateGst(onlineReturnPortion);
        boolean gstReversible = policy.isGstReversible();

        return new RefundBreakdown(
                orderTotal,
                returnAmount,
                platformFee,
                feeToRefund,
                nonRefundableFee,
                refundablePool,
                methodRefunds,
                gstAmount,
                gstReversible
        );
    }

    /** Convenience for full return */
    public RefundBreakdown calculateFullReturn(Money orderTotal, PaymentAllocation originalAllocation) {
        return calculate(orderTotal, originalAllocation, orderTotal);
    }

    private void validateInputs(Money orderTotal, PaymentAllocation originalAllocation, Money returnAmount) {
        if (returnAmount.compareTo(orderTotal) > 0) {
            throw new IllegalArgumentException("Return amount cannot exceed order total");
        }
        if (returnAmount.compareTo(Money.zero()) <= 0) {
            throw new IllegalArgumentException("Return amount must be positive");
        }
        if (!originalAllocation.total().equals(orderTotal)) {
            throw new IllegalArgumentException("Payment allocation total must equal order total");
        }
    }
}