package com.razorpay.refund.policy;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Standard Razorpay-like refund policy:
 * - Platform fee: 2% of order total, NON-refundable
 * - GST: 18% on online payments only, REVERSIBLE (merchant claims credit)
 * - Split: Proportional to original payment method amounts
 */
public final class StandardRefundPolicy implements RefundPolicy {

    private static final BigDecimal PLATFORM_FEE_RATE = new BigDecimal("0.02"); // 2%
    private static final BigDecimal GST_RATE = new BigDecimal("0.18"); // 18%

    @Override
    public Money calculatePlatformFee(Money orderTotal) {
        return orderTotal.multiply(PLATFORM_FEE_RATE);
    }

    @Override
    public boolean isPlatformFeeRefundable() {
        return false; // Platform keeps the fee
    }

    @Override
    public Money calculateGst(Money onlineAmount) {
        // onlineAmount includes GST. Base = onlineAmount / 1.18
        // GST = onlineAmount - base = onlineAmount * (0.18/1.18)
        BigDecimal gstFactor = GST_RATE.divide(BigDecimal.ONE.add(GST_RATE), 10, RoundingMode.HALF_EVEN);
        return onlineAmount.multiply(gstFactor);
    }

    @Override
    public boolean isGstReversible() {
        return true; // Merchant reverses GST credit in GSTR-1
    }

    @Override
    public Map<PaymentMethod, Money> splitRefund(Money refundableAmount, PaymentAllocation originalAllocation) {
        return proportionalSplit(refundableAmount, originalAllocation);
    }
}