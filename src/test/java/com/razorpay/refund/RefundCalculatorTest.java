package com.razorpay.refund;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.policy.StandardRefundPolicy;
import com.razorpay.refund.service.RefundCalculator;
import com.razorpay.refund.service.RefundBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the exact scenario you described:
 * - Order: ₹700
 * - Payment: ₹300 Wallet + ₹400 Online
 * - Platform fee: 2% (non-refundable)
 * - GST: 18% on online only (reversible)
 * - Split: Proportional
 */
class RefundCalculatorTest {

    private final RefundCalculator calculator = new RefundCalculator(new StandardRefundPolicy());

    @Test
    void yourExactScenario_fullReturn() {
        // Given: ₹700 order, paid ₹300 wallet + ₹400 online
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // When: Full return
        RefundBreakdown result = calculator.calculateFullReturn(orderTotal, allocation);

        // Then: Verify the breakdown
        System.out.println("=== FULL RETURN BREAKDOWN ===");
        System.out.println(result);

        // Platform fee: 2% of 700 = ₹14 (non-refundable)
        assertEquals(Money.of("14.00"), result.getPlatformFee());
        assertEquals(Money.zero(), result.getFeeRefundedToCustomer());
        assertEquals(Money.of("14.00"), result.getFeeRetainedByPlatform());

        // Refundable pool: 700 - 14 = ₹686
        assertEquals(Money.of("686.00"), result.getRefundablePool());

        // Proportional split:
        // Wallet: 686 * (300/700) = ₹294
        // Online: 686 * (400/700) = ₹392
        assertEquals(Money.of("294.00"), result.getWalletRefund());
        assertEquals(Money.of("392.00"), result.getMerchantOnlineRefund());
        assertEquals(Money.of("686.00"), result.getTotalCustomerRefund());

        // GST on online portion (₹400 includes GST)
        // Base = 400 / 1.18 = 338.98, GST = 61.02
        Money expectedGst = Money.of("61.02");
        assertEquals(expectedGst, result.getGstOnOnlinePortion());
        assertTrue(result.isGstReversible());

        System.out.println("\nCustomer receives: ₹294 (wallet) + ₹392 (online) = ₹686");
        System.out.println("Platform keeps: ₹14 fee");
        System.out.println("Merchant reverses GST credit: ₹61.02 in GSTR-1");
    }

    @Test
    void partialReturn_halfQuantity() {
        // Given: Same order, but customer returns half (₹350 worth)
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));
        Money returnAmount = Money.of("350.00"); // Half the order

        // When: Partial return
        RefundBreakdown result = calculator.calculate(orderTotal, allocation, returnAmount);

        // Then: Fee on return amount only (2% of 350 = ₹7)
        assertEquals(Money.of("7.00"), result.getPlatformFee());
        assertEquals(Money.of("7.00"), result.getFeeRetainedByPlatform());

        // Refundable pool: 350 - 7 = ₹343
        assertEquals(Money.of("343.00"), result.getRefundablePool());

        // Proportional split of 343:
        // Wallet: 343 * (300/700) = ₹147
        // Online: 343 * (400/700) = ₹196
        assertEquals(Money.of("147.00"), result.getWalletRefund());
        assertEquals(Money.of("196.00"), result.getMerchantOnlineRefund());

        // GST on online return portion (₹196 includes GST)
        // Base = 196 / 1.18 = 166.10, GST = 29.90
        Money expectedGst = Money.of("29.90");
        assertEquals(expectedGst, result.getGstOnOnlinePortion());

        System.out.println("\n=== PARTIAL RETURN (₹350) ===");
        System.out.println(result);
    }

    @Test
    void onlyWalletPayment_noGst() {
        // Edge case: 100% wallet, no online → no GST
        Money orderTotal = Money.of("500.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("500.00")
        ));

        RefundBreakdown result = calculator.calculateFullReturn(orderTotal, allocation);

        assertEquals(Money.of("10.00"), result.getPlatformFee()); // 2% of 500
        assertEquals(Money.of("490.00"), result.getRefundablePool());
        assertEquals(Money.of("490.00"), result.getWalletRefund());
        assertEquals(Money.zero(), result.getMerchantOnlineRefund());
        assertEquals(Money.zero(), result.getGstOnOnlinePortion());
    }

    @Test
    void roundingErrorHandling() {
        // Test that rounding errors are absorbed by largest share
        Money orderTotal = Money.of("100.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("33.33"),
                PaymentMethod.ONLINE, Money.of("66.67")
        ));

        RefundBreakdown result = calculator.calculateFullReturn(orderTotal, allocation);

        // Total refund should exactly match refundable pool (98.00 after 2% fee)
        assertEquals(Money.of("98.00"), result.getTotalCustomerRefund());
        assertEquals(Money.of("98.00"), result.getRefundablePool());
    }
}