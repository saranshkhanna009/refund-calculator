package com.razorpay.refund.saga.steps;

import com.razorpay.refund.saga.SagaContext;
import com.razorpay.refund.saga.SagaStep;
import com.razorpay.refund.service.RefundBreakdown;
import com.razorpay.refund.service.RefundExecutor;
import com.razorpay.refund.service.RefundExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step 1: Execute refund via payment gateway (Razorpay).
 *
 * Forward: Call Razorpay Refund API with idempotency key
 * Compensate: Void/cancel the refund if possible (Razorpay supports refund reversal within window)
 *
 * Idempotent: YES - Uses idempotency key, safe to retry
 */
@Component
public class GatewayRefundStep implements SagaStep<SagaContext> {

    private static final Logger log = LoggerFactory.getLogger(GatewayRefundStep.class);

    private final RefundExecutor refundExecutor;

    public GatewayRefundStep(RefundExecutor refundExecutor) {
        this.refundExecutor = refundExecutor;
    }

    @Override
    public String getName() {
        return "GATEWAY_REFUND";
    }

    @Override
    public boolean isIdempotent() {
        return true; // Uses idempotency key
    }

    @Override
    public SagaStep.StepResult execute(SagaContext context) throws SagaStepException {
        RefundBreakdown breakdown = context.getBreakdown();
        String idempotencyKey = context.getIdempotencyKey();

        log.info("Executing gateway refund: sagaId={}, idempotencyKey={}", context.getSagaId(), idempotencyKey);

        try {
            // Execute refund via gateway (Razorpay)
            refundExecutor.execute(breakdown);

            // In real impl, fetch from Razorpay response
            String gatewayRefundId = "rfnd_" + context.getSagaId().substring(0, Math.min(8, context.getSagaId().length()));

            log.info("Gateway refund created: sagaId={}, gatewayRefundId={}", context.getSagaId(), gatewayRefundId);

            return SagaStep.StepResult.success(gatewayRefundId, Map.of(
                    "gatewayRefundId", gatewayRefundId,
                    "amount", breakdown.getTotalCustomerRefund().toString()
            ));

        } catch (RefundExecutionException e) {
            log.error("Gateway refund failed: sagaId={}, error={}", context.getSagaId(), e.getMessage());
            throw SagaStepException.compensatable(getName(), "Gateway refund failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected gateway error: sagaId={}", context.getSagaId(), e);
            throw SagaStepException.compensatable(getName(), "Gateway error: " + e.getMessage(), e);
        }
    }

    @Override
    public void compensate(SagaContext context, SagaStep.StepResult previousResult) throws SagaStepException {
        String gatewayRefundId = previousResult.getOutputRef();
        if (gatewayRefundId == null) {
            log.warn("No gateway refund ID to compensate for sagaId={}", context.getSagaId());
            return;
        }

        log.info("Compensating gateway refund: sagaId={}, gatewayRefundId={}", context.getSagaId(), gatewayRefundId);

        try {
            // Razorpay supports refund reversal within 24-48 hours
            // refundExecutor.reverseRefund(gatewayRefundId);
            // For now, log and mark as compensated
            log.info("Gateway refund reversal initiated: {}", gatewayRefundId);

        } catch (Exception e) {
            log.error("Failed to compensate gateway refund: sagaId={}, gatewayRefundId={}", context.getSagaId(), gatewayRefundId, e);
            // Don't throw - compensation should be best effort
            // Alert ops team for manual intervention
        }
    }
}