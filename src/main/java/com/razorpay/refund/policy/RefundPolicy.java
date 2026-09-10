package com.razorpay.refund.policy;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Encapsulates all refund business rules for a merchant.
 * Different merchants can have different policies.
 * Immutable — safe to share across threads.
 */
public interface RefundPolicy {

    /**
     * Calculate the platform fee for a given order amount.
     * @param orderTotal the full order amount
     * @return fee amount (non-negative)
     */
    Money calculatePlatformFee(Money orderTotal);

    /**
     * Is the platform fee refunded on return?
     * @return true if fee is returned to customer, false if platform keeps it
     */
    boolean isPlatformFeeRefundable();

    /**
     * Calculate GST component for a given online payment amount.
     * @param onlineAmount the amount paid via online methods
     * @return GST amount (typically 18% of base amount)
     */
    Money calculateGst(Money onlineAmount);

    /**
     * Is GST reversed on refund?
     * @return true if GST credit is reversed to customer
     */
    boolean isGstReversible();

    /**
     * How to split refund across payment methods.
     * @param refundableAmount the total amount available for refund (after fee deduction)
     * @param originalAllocation how the customer originally paid
     * @return map of payment method -> refund amount
     */
    Map<PaymentMethod, Money> splitRefund(Money refundableAmount, PaymentAllocation originalAllocation);

    /**
     * Default implementation: proportional split (most common).
     * Override for wallet-first, online-first, etc.
     */
    default Map<PaymentMethod, Money> proportionalSplit(Money refundableAmount, PaymentAllocation originalAllocation) {
        Map<PaymentMethod, Money> result = new EnumMap<>(PaymentMethod.class);
        Money totalPaid = originalAllocation.total();

        for (PaymentMethod method : originalAllocation.getAll().keySet()) {
            BigDecimal proportion = originalAllocation.proportion(method);
            Money share = refundableAmount.multiply(proportion);
            result.put(method, share);
        }

        // Fix rounding errors: adjust the largest share to make sum exact
        Money sum = result.values().stream().reduce(Money.zero(), Money::add);
        Money diff = refundableAmount.subtract(sum);
        if (!diff.isZero()) {
            PaymentMethod largest = result.entrySet().stream()
                    .max(Map.Entry.comparingByValue((a, b) -> a.compareTo(b)))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            result.put(largest, result.get(largest).add(diff));
        }

        return result;
    }
}