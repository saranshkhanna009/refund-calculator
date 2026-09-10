package com.razorpay.refund;

import com.razorpay.refund.idempotency.IdempotencyStore;
import com.razorpay.refund.idempotency.InMemoryIdempotencyStore;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.policy.StandardRefundPolicy;
import com.razorpay.refund.service.IdempotentRefundService;
import com.razorpay.refund.service.RefundBreakdown;
import com.razorpay.refund.service.RefundCalculator;
import com.razorpay.refund.service.RefundExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the idempotent refund service.
 * Verifies:
 * - First request processes normally
 * - Duplicate requests return cached result (no double execution)
 * - Concurrent requests: only one processes
 * - Failed requests can be retried
 * - Different returnIds for same order are independent
 */
class IdempotentRefundServiceTest {

    private IdempotentRefundService service;
    private RefundCalculator calculator;
    private InMemoryIdempotencyStore idempotencyStore;
    private MockRefundExecutor mockExecutor;

    @BeforeEach
    void setUp() {
        calculator = new RefundCalculator(new StandardRefundPolicy());
        idempotencyStore = new InMemoryIdempotencyStore();
        mockExecutor = new MockRefundExecutor();
        service = new IdempotentRefundService(calculator, idempotencyStore, mockExecutor);
    }

    @Test
    void firstRequest_processesNormally() {
        // Given
        String orderId = "order_123";
        String returnId = "return_456";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // When
        RefundBreakdown result = service.processFullRefund(orderId, returnId, orderTotal, allocation);

        // Then
        assertEquals(Money.of("686.00"), result.getTotalCustomerRefund());
        assertEquals(1, mockExecutor.getCallCount());
        assertEquals(Money.of("294.00"), result.getWalletRefund());
        assertEquals(Money.of("392.00"), result.getMerchantOnlineRefund());
    }

    @Test
    void duplicateRequest_returnsCachedResult_noDoubleExecution() {
        // Given: same orderId + returnId
        String orderId = "order_123";
        String returnId = "return_456";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // When: first request
        RefundBreakdown first = service.processFullRefund(orderId, returnId, orderTotal, allocation);

        // When: duplicate request (simulates webhook retry or user double-click)
        RefundBreakdown second = service.processFullRefund(orderId, returnId, orderTotal, allocation);

        // Then: same result, executor called ONLY ONCE
        assertEquals(first, second);
        assertEquals(1, mockExecutor.getCallCount(), "Executor should not be called twice for duplicate");
    }

    @Test
    void differentReturnId_sameOrder_independent() {
        // Given: same order, different return requests
        String orderId = "order_123";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // When: two different returns
        RefundBreakdown first = service.processFullRefund(orderId, "return_1", orderTotal, allocation);
        RefundBreakdown second = service.processFullRefund(orderId, "return_2", orderTotal, allocation);

        // Then: both processed independently
        assertEquals(2, mockExecutor.getCallCount());
        assertEquals(first.getTotalCustomerRefund(), second.getTotalCustomerRefund());
    }

    @Test
    void failedRequest_canRetryAfterFailure() {
        // Given
        String orderId = "order_123";
        String returnId = "return_456";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // First attempt: executor fails
        mockExecutor.setErrorToThrow(new RefundExecutionException("Gateway timeout"));

        // When: first attempt fails
        assertThrows(RuntimeException.class, () ->
                service.processFullRefund(orderId, returnId, orderTotal, allocation));

        assertEquals(1, mockExecutor.getCallCount());
        assertEquals(IdempotencyStore.Status.FAILED, service.getStatus(orderId, returnId));

        // When: retry (clear error)
        mockExecutor.reset();
        RefundBreakdown result = service.processFullRefund(orderId, returnId, orderTotal, allocation);

        // Then: succeeds on retry
        assertEquals(Money.of("686.00"), result.getTotalCustomerRefund());
        assertEquals(2, mockExecutor.getCallCount());
        assertEquals(IdempotencyStore.Status.COMPLETED, service.getStatus(orderId, returnId));
    }

    @Test
    void partialReturn_thenFullReturn_differentKeys() {
        // Given
        String orderId = "order_123";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // When: partial return first
        RefundBreakdown partial = service.processRefund(orderId, "return_partial", orderTotal, allocation, Money.of("350.00"));

        // When: full return (different returnId)
        RefundBreakdown full = service.processFullRefund(orderId, "return_full", orderTotal, allocation);

        // Then: both processed
        assertEquals(2, mockExecutor.getCallCount());
        assertEquals(Money.of("343.00"), partial.getTotalCustomerRefund()); // 350 - 7 fee
        assertEquals(Money.of("686.00"), full.getTotalCustomerRefund());   // 700 - 14 fee
    }

    @Test
    void statusCheck_beforeAndAfterProcessing() {
        // Given
        String orderId = "order_123";
        String returnId = "return_456";
        Money orderTotal = Money.of("700.00");
        PaymentAllocation allocation = new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, Money.of("300.00"),
                PaymentMethod.ONLINE, Money.of("400.00")
        ));

        // Before processing
        assertEquals(IdempotencyStore.Status.UNKNOWN, service.getStatus(orderId, returnId));

        // Process
        service.processFullRefund(orderId, returnId, orderTotal, allocation);

        // After processing
        assertEquals(IdempotencyStore.Status.COMPLETED, service.getStatus(orderId, returnId));
    }
}