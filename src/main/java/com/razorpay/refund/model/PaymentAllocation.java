package com.razorpay.refund.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * Represents how the original order was paid — split across payment methods.
 * Immutable.
 */
public final class PaymentAllocation {
    private final Map<PaymentMethod, Money> allocations;

    public PaymentAllocation(Map<PaymentMethod, Money> allocations) {
        this.allocations = Map.copyOf(allocations);
        validate();
    }

    private void validate() {
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("At least one payment allocation required");
        }
        for (Money amount : allocations.values()) {
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("All allocation amounts must be positive");
            }
        }
    }

    public Money get(PaymentMethod method) {
        return allocations.getOrDefault(method, Money.zero());
    }

    public Money total() {
        return allocations.values().stream()
                .reduce(Money.zero(), Money::add);
    }

    public Map<PaymentMethod, Money> getAll() {
        return allocations;
    }

    /** Proportion of a given method in the total payment (0.0 to 1.0) */
    public BigDecimal proportion(PaymentMethod method) {
        Money methodAmount = get(method);
        Money total = total();
        if (total.isZero()) {
            return BigDecimal.ZERO;
        }
        return methodAmount.getAmount().divide(total.getAmount(), 10, RoundingMode.HALF_EVEN);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentAllocation)) return false;
        PaymentAllocation that = (PaymentAllocation) o;
        return Objects.equals(allocations, that.allocations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allocations);
    }

    @Override
    public String toString() {
        return allocations.toString();
    }
}