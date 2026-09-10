package com.razorpay.refund.saga.steps;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.saga.SagaContext;
import com.razorpay.refund.saga.SagaStep;
import com.razorpay.refund.service.RefundBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step 3: Credit wallet/cashback portion to customer.
 *
 * Forward: Call internal wallet service to credit amount
 * Compensate: Debit wallet (reverse credit)
 *
 * Idempotent: YES - Uses sagaId as idempotency key for wallet service
 */
@Component
public class WalletCreditStep implements SagaStep<SagaContext> {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditStep.class);

    private final WalletService walletService;

    public WalletCreditStep(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public String getName() {
        return "WALLET_CREDIT";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public SagaStep.StepResult execute(SagaContext context) throws SagaStepException {
        RefundBreakdown breakdown = context.getBreakdown();
        Money walletAmount = breakdown.getWalletRefund();

        // Skip if no wallet portion
        if (walletAmount == null || walletAmount.isZero()) {
            log.info("No wallet portion, skipping: sagaId={}", context.getSagaId());
            return SagaStep.StepResult.success("SKIPPED", Map.of("reason", "no_wallet_portion"));
        }

        log.info("Crediting wallet: sagaId={}, amount={}", context.getSagaId(), walletAmount);

        try {
            // Call wallet service with idempotency key
            String walletTxnId = walletService.credit(
                    context.getOrderId(),  // or customer ID from context
                    walletAmount,
                    "Refund for order " + context.getOrderId(),
                    context.getSagaId()  // Idempotency key
            );

            log.info("Wallet credited: sagaId={}, walletTxnId={}", context.getSagaId(), walletTxnId);

            return SagaStep.StepResult.success(walletTxnId, Map.of(
                    "walletTxnId", walletTxnId,
                    "amount", walletAmount.toString()
            ));

        } catch (WalletException e) {
            log.error("Wallet credit failed: sagaId={}", context.getSagaId(), e);
            throw SagaStepException.compensatable(getName(), "Wallet credit failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected wallet error: sagaId={}", context.getSagaId(), e);
            throw SagaStepException.compensatable(getName(), "Wallet error: " + e.getMessage(), e);
        }
    }

    @Override
    public void compensate(SagaContext context, SagaStep.StepResult previousResult) throws SagaStepException {
        String walletTxnId = previousResult.getOutputRef();
        if (walletTxnId == null || "SKIPPED".equals(walletTxnId)) {
            return; // Nothing to compensate
        }

        log.info("Compensating wallet credit: sagaId={}, walletTxnId={}", context.getSagaId(), walletTxnId);

        try {
            walletService.debit(walletTxnId, "Saga compensation: " + context.getSagaId());
            log.info("Wallet debit completed: {}", walletTxnId);

        } catch (Exception e) {
            log.error("Failed to compensate wallet: sagaId={}, walletTxnId={}", context.getSagaId(), walletTxnId, e);
            // Best effort - alert ops for manual reversal
        }
    }

    // Service interfaces
    public interface WalletService {
        String credit(String userId, Money amount, String description, String idempotencyKey) throws WalletException;
        void debit(String walletTxnId, String reason) throws WalletException;
    }

    public static class WalletException extends Exception {
        public WalletException(String message) { super(message); }
        public WalletException(String message, Throwable cause) { super(message, cause); }
    }
}