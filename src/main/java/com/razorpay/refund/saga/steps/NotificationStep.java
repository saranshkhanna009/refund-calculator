package com.razorpay.refund.saga.steps;

import com.razorpay.refund.saga.SagaContext;
import com.razorpay.refund.saga.SagaStep;
import com.razorpay.refund.service.RefundBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step 4: Send refund confirmation notification (email/SMS/push).
 *
 * Forward: Send notification via notification service
 * Compensate: Send "refund failed" notification (best effort)
 *
 * Idempotent: YES - Notification service handles deduplication
 * Non-critical: Failure doesn't block refund, just logs warning
 */
@Component
public class NotificationStep implements SagaStep<SagaContext> {

    private static final Logger log = LoggerFactory.getLogger(NotificationStep.class);

    private final NotificationService notificationService;

    public NotificationStep(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String getName() {
        return "NOTIFICATION";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public SagaStep.StepResult execute(SagaContext context) throws SagaStepException {
        RefundBreakdown breakdown = context.getBreakdown();

        log.info("Sending refund notification: sagaId={}", context.getSagaId());

        try {
            String notificationId = notificationService.sendRefundConfirmation(
                    context.getOrderId(),
                    breakdown,
                    context.getSagaId()
            );

            log.info("Notification sent: sagaId={}, notificationId={}", context.getSagaId(), notificationId);

            return SagaStep.StepResult.success(notificationId, Map.of(
                    "notificationId", notificationId,
                    "channels", "EMAIL,SMS,PUSH"
            ));

        } catch (Exception e) {
            // Notification failure is NON-BLOCKING
            // Log warning but don't fail the saga
            log.warn("Notification failed (non-blocking): sagaId={}, error={}", context.getSagaId(), e.getMessage());
            return SagaStep.StepResult.success("FAILED", Map.of("error", e.getMessage()));
        }
    }

    @Override
    public void compensate(SagaContext context, SagaStep.StepResult previousResult) throws SagaStepException {
        // Send "refund reversed/failed" notification
        log.info("Sending compensation notification: sagaId={}", context.getSagaId());

        try {
            notificationService.sendRefundFailed(
                    context.getOrderId(),
                    "Refund was reversed due to processing issue",
                    context.getSagaId()
            );
        } catch (Exception e) {
            log.warn("Compensation notification failed: sagaId={}", context.getSagaId(), e);
            // Best effort
        }
    }

    public interface NotificationService {
        String sendRefundConfirmation(String orderId, RefundBreakdown breakdown, String sagaId);
        void sendRefundFailed(String orderId, String reason, String sagaId);
    }
}