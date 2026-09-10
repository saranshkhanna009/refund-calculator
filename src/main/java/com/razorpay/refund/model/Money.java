package com.razorpay.refund.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable money value object. Never use float/double for money.
 * Uses BigDecimal with INR scale (2 decimal places) and HALF_EVEN rounding (banker's rounding).
 */
public final class Money {
    public static final Currency INR = Currency.getInstance("INR");
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /**
     * Create Money from paise (smallest currency unit).
     * Razorpay and most Indian gateways use paise.
     */
    public static Money ofPaise(long paise) {
        return new Money(BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100)));
    }

    public long toPaise() {
        return amount.movePointRight(2).longValueExact();
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(BigDecimal factor) {
        return new Money(this.amount.multiply(factor));
    }

    public Money multiply(double factor) {
        return multiply(BigDecimal.valueOf(factor));
    }

    public Money divide(BigDecimal divisor) {
        return new Money(this.amount.divide(divisor, SCALE, ROUNDING));
    }

    public Money min(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) <= 0 ? this : other;
    }

    public Money max(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0 ? this : other;
    }

    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money)) return false;
        Money money = (Money) o;
        return amount.equals(money.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount);
    }

    @Override
    public String toString() {
        return "₹" + amount.stripTrailingZeros().toPlainString();
    }

    private void assertSameCurrency(Money other) {
        // In a real system, you'd track currency per Money instance
        // For this exercise, we assume INR only
    }
}