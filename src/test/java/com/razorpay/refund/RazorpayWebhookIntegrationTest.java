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
import com.razorpay.refund.webhook.RazorpayRefundWebhookHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test simulating Razorpay webhook flow:
 * 1. First webhook delivery -> processes refund
 * 2. Duplicate webhook (retry) -> returns cached result
 * 3. Failed webhook -> 500 triggers Razorpay retry
 */
class RazorpayWebhookIntegrationTest {

    private RazorpayRefundWebhookHandler handler;
    private IdempotentRefundService refundService;
    private InMemoryIdempotencyStore idempotencyStore;
    private MockRefundExecutor mockExecutor;

    @BeforeEach
    void setUp() {
        RefundCalculator calculator = new RefundCalculator(new StandardRefundPolicy());
        idempotencyStore = new InMemoryIdempotencyStore();
        mockExecutor = new MockRefundExecutor();
        refundService = new IdempotentRefundService(calculator, idempotencyStore, mockExecutor);
        handler = new RazorpayRefundWebhookHandler(refundService, idempotencyStore);
    }

    @Test
    void webhookFirstDelivery_processesRefund() {
        // Given: Razorpay webhook payload
        var payload = new RazorpayRefundWebhookHandler.RefundWebhookPayload.Payload(
                "rfnd_123456",  // Razorpay refund ID
                "refund",
                68600,          // 686.00 INR in paise
                "INR",
                "pay_abc123",   // Original payment ID
                "order_789",    // Order ID
                "processed",
                "normal",
                "{}"
        );
        var webhook = new RazorpayRefundWebhookHandler.RefundWebhookPayload("refund.processed", payload);

        // When
        var response = handler.handleRefundWebhook(webhook);

        // Then
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(1, mockExecutor.getCallCount());

        RefundBreakdown breakdown = mockExecutor.getLastBreakdown();
        assertEquals(Money.of("686.00"), breakdown.getTotalCustomerRefund());
    }

    @Test
    void webhookDuplicateDelivery_returnsCachedResult() {
        // Given
        var payload = new RazorpayRefundWebhookHandler.RefundWebhookPayload.Payload(
                "rfnd_123456",
                "refund",
                68600,
                "INR",
                "pay_abc123",
                "order_789",
                "processed",
                "normal",
                "{}"
        );
        var webhook = new RazorpayRefundWebhookHandler.RefundWebhookPayload("refund.processed", payload);

        // When: first delivery
        handler.handleRefundWebhook(webhook);
        int firstCallCount = mockExecutor.getCallCount();

        // When: duplicate delivery (Razorpay retry)
        handler.handleRefundWebhook(webhook);
        int secondCallCount = mockExecutor.getCallCount();

        // Then: executor called only once
        assertEquals(firstCallCount, secondCallCount);
        assertEquals(1, secondCallCount);
    }

    @Test
    void webhookFailedEvent_ignored() {
        // Given: failed refund webhook
        var payload = new RazorpayRefundWebhookHandler.RefundWebhookPayload.Payload(
                "rfnd_failed_123",
                "refund",
                68600,
                "INR",
                "pay_abc123",
                "order_789",
                "failed",
                "normal",
                "{}"
        );
        var webhook = new RazorpayRefundWebhookHandler.RefundWebhookPayload("refund.failed", payload);

        // When
        var response = handler.handleRefundWebhook(webhook);

        // Then: 200 OK but no processing
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(0, mockExecutor.getCallCount());
    }

    @Test
    void webhookProcessingFailure_returns500_forRetry() {
        // Given
        var payload = new RazorpayRefundWebhookHandler.RefundWebhookPayload.Payload(
                "rfnd_123456",
                "refund",
                68600,
                "INR",
                "pay_abc123",
                "order_789",
                "processed",
                "normal",
                "{}"
        );
        var webhook = new RazorpayRefundWebhookHandler.RefundWebhookPayload("refund.processed", payload);

        // Simulate executor failure
        mockExecutor.setErrorToThrow(new RuntimeException("Gateway down"));

        // When
        var response = handler.handleRefundWebhook(webhook);

        // Then: 500 so Razorpay retries
        assertEquals(500, response.getStatusCodeValue());
        assertEquals(IdempotencyStore.Status.FAILED, idempotencyStore.getStatus(
                new com.razorpay.refund.idempotency.IdempotencyKey("refund:order_789:rfnd_123456")));
    }

    @Test
    void webhookRetryAfterFailure_succeeds() {
        // Given
        var payload = new RazorpayRefundWebhookHandler.RefundWebhookPayload.Payload(
                "rfnd_123456",
                "refund",
                68600,
                "INR",
                "pay_abc123",
                "order_789",
                "processed",
                "normal",
                "{}"
        );
        var webhook = new RazorpayRefundWebhookHandler.RefundWebhookPayload("refund.processed", payload);

        // First attempt: fails
        mockExecutor.setErrorToThrow(new RuntimeException("Gateway down"));
        handler.handleRefundWebhook(webhook);
        assertEquals(500, handler.handleRefundWebhook(webhook).getStatusCodeValue());

        // Second attempt (Razorpay retry): succeeds
        mockExecutor.reset();
        var response = handler.handleRefundWebhook(webhook);

        // Then
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(2, mockExecutor.getCallCount()); // First failed, second succeeded
        assertEquals(IdempotencyStore.Status.COMPLETED, idempotencyStore.getStatus(
                new com.razorpay.refund.idempotency.IdempotencyKey("refund:order_789:rfnd_123456")));
    }
}