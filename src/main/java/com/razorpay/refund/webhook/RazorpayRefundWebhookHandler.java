package com.razorpay.refund.webhook;

import com.razorpay.refund.idempotency.IdempotencyKey;
import com.razorpay.refund.idempotency.IdempotencyStore;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.service.IdempotentRefundService;
import com.razorpay.refund.service.RefundBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook handler for Razorpay refund callbacks.
 *
 * Razorpay webhook retry policy:
 * - Retries 3 times with exponential backoff (10m, 1h, 24h)
 * - If all retries fail, goes to manual review queue
 * - Each retry sends SAME payload with SAME event ID
 *
 * Our idempotency key uses: refund:{orderId}:{razorpayRefundId}
 * This handles:
 * 1. Duplicate webhook delivery (same event ID)
 * 2. Concurrent webhook + manual API call
 * 3. Retry after temporary failure
 */
@RestController
@RequestMapping("/webhooks/razorpay")
public class RazorpayRefundWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(RazorpayRefundWebhookHandler.class);

    private final IdempotentRefundService refundService;
    private final IdempotencyStore idempotencyStore;

    public RazorpayRefundWebhookHandler(IdempotentRefundService refundService, IdempotencyStore idempotencyStore) {
        this.refundService = refundService;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * Razorpay refund webhook payload (simplified).
     * Real payload has more fields - see Razorpay docs.
     */
    public record RefundWebhookPayload(
            String event,           // "refund.processed", "refund.failed"
            Payload payload
    ) {
        public record Payload(
                String id,              // Razorpay refund ID (e.g., "rfnd_123")
                String entity,          // "refund"
                int amount,             // In paise
                String currency,        // "INR"
                String payment_id,      // Original payment ID
                String order_id,        // Order ID
                String status,          // "processed", "failed"
                String speed_processed, // "normal", "instant"
                String notes            // Custom metadata
        ) {}
    }

    @PostMapping("/refund")
    public ResponseEntity<Void> handleRefundWebhook(@RequestBody RefundWebhookPayload webhook) {
        String eventId = webhook.payload().id(); // Razorpay refund ID
        String orderId = webhook.payload().order_id();

        log.info("Received Razorpay webhook: event={}, refundId={}, orderId={}",
                webhook.event(), eventId, orderId);

        // Only process successful refunds
        if (!"refund.processed".equals(webhook.event())) {
            log.info("Ignoring non-processed event: {}", webhook.event());
            return ResponseEntity.ok().build();
        }

        // Generate idempotency key from Razorpay's own refund ID
        // This is the KEY insight: use gateway's ID, not our own
        IdempotencyKey key = IdempotencyKey.of(orderId, eventId);

        // Check if already processed (fast path for duplicates)
        if (idempotencyStore.getStatus(key) == IdempotencyStore.Status.COMPLETED) {
            log.info("Duplicate webhook ignored: {}", key);
            return ResponseEntity.ok().build();
        }

        try {
            // Reconstruct original payment allocation from order
            // In real system, fetch from database
            PaymentAllocation allocation = reconstructAllocation(orderId, webhook.payload().amount());

            // Process with idempotency
            RefundBreakdown breakdown = refundService.processRefund(
                    orderId,
                    eventId, // Use Razorpay refund ID as returnId
                    Money.ofPaise(webhook.payload().amount()),
                    allocation,
                    Money.ofPaise(webhook.payload().amount())
            );

            log.info("Webhook processed successfully: key={}, refunded={}", key, breakdown.getTotalCustomerRefund());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Webhook processing failed: key={}, error={}", key, e.getMessage());
            // Return 5xx so Razorpay retries (they only retry on 5xx, not 4xx)
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * In real implementation, fetch from order/payment database.
     * This is a simplified version for demo.
     */
    private PaymentAllocation reconstructAllocation(String orderId, int amountPaise) {
        // Example: fetch from DB based on orderId
        // For demo, assume 30/70 wallet/online split
        Money total = Money.ofPaise(amountPaise);
        Money wallet = total.multiply(0.3);
        Money online = total.subtract(wallet);

        return new PaymentAllocation(Map.of(
                PaymentMethod.WALLET, wallet,
                PaymentMethod.ONLINE, online
        ));
    }

    /**
     * Health check endpoint for monitoring
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}