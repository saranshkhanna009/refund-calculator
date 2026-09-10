package com.razorpay.refund.service;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of a refund calculation.
 * Contains everything needed for: customer communication, ledger entries, tax filing, reconciliation.
 */
public final class RefundBreakdown {

    private final Money orderTotal;
    private final Money returnAmount;
    private final Money platformFee;
    private final Money feeRefundedToCustomer;
    private final Money feeRetainedByPlatform;
    private final Money refundablePool;
    private final Map<PaymentMethod, Money> refundByMethod;
    private final Money gstOnOnlinePortion;
    private final boolean gstReversible;

    public RefundBreakdown(
            Money orderTotal,
            Money returnAmount,
            Money platformFee,
            Money feeRefundedToCustomer,
            Money feeRetainedByPlatform,
            Money refundablePool,
            Map<PaymentMethod, Money> refundByMethod,
            Money gstOnOnlinePortion,
            boolean gstReversible) {

        this.orderTotal = Objects.requireNonNull(orderTotal);
        this.returnAmount = Objects.requireNonNull(returnAmount);
        this.platformFee = Objects.requireNonNull(platformFee);
        this.feeRefundedToCustomer = Objects.requireNonNull(feeRefundedToCustomer);
        this.feeRetainedByPlatform = Objects.requireNonNull(feeRetainedByPlatform);
        this.refundablePool = Objects.requireNonNull(refundablePool);
        this.refundByMethod = Map.copyOf(Objects.requireNonNull(refundByMethod));
        this.gstOnOnlinePortion = Objects.requireNonNull(gstOnOnlinePortion);
        this.gstReversible = gstReversible;
    }

    // Getters
    public Money getOrderTotal() { return orderTotal; }
    public Money getReturnAmount() { return returnAmount; }
    public Money getPlatformFee() { return platformFee; }
    public Money getFeeRefundedToCustomer() { return feeRefundedToCustomer; }
    public Money getFeeRetainedByPlatform() { return feeRetainedByPlatform; }
    public Money getRefundablePool() { return refundablePool; }
    public Map<PaymentMethod, Money> getRefundByMethod() { return refundByMethod; }
    public Money getGstOnOnlinePortion() { return gstOnOnlinePortion; }
    public boolean isGstReversible() { return gstReversible; }

    /** Total cash outflow to customer (sum of all method refunds) */
    public Money getTotalCustomerRefund() {
        return refundByMethod.values().stream().reduce(Money.zero(), Money::add);
    }

    /** Amount merchant receives back from payment gateway (online portion minus gateway fees) */
    public Money getMerchantOnlineRefund() {
        return refundByMethod.getOrDefault(PaymentMethod.ONLINE, Money.zero());
    }

    /** Amount credited back to wallet (no gateway cost) */
    public Money getWalletRefund() {
        return refundByMethod.getOrDefault(PaymentMethod.WALLET, Money.zero());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefundBreakdown)) return false;
        RefundBreakdown that = (RefundBreakdown) o;
        return gstReversible == that.gstReversible &&
                Objects.equals(orderTotal, that.orderTotal) &&
                Objects.equals(returnAmount, that.returnAmount) &&
                Objects.equals(platformFee, that.platformFee) &&
                Objects.equals(feeRefundedToCustomer, that.feeRefundedToCustomer) &&
                Objects.equals(feeRetainedByPlatform, that.feeRetainedByPlatform) &&
                Objects.equals(refundablePool, that.refundablePool) &&
                Objects.equals(refundByMethod, that.refundByMethod) &&
                Objects.equals(gstOnOnlinePortion, that.gstOnOnlinePortion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderTotal, returnAmount, platformFee, feeRefundedToCustomer,
                feeRetainedByPlatform, refundablePool, refundByMethod, gstOnOnlinePortion, gstReversible);
    }

    @Override
    public String toString() {
        return "RefundBreakdown{" +
                "orderTotal=" + orderTotal +
                ", returnAmount=" + returnAmount +
                ", platformFee=" + platformFee +
                ", feeRefunded=" + feeRefundedToCustomer +
                ", feeRetained=" + feeRetainedByPlatform +
                ", refundablePool=" + refundablePool +
                ", refundByMethod=" + refundByMethod +
                ", gstOnOnline=" + gstOnOnlinePortion +
                ", gstReversible=" + gstReversible +
                '}';
    }
}