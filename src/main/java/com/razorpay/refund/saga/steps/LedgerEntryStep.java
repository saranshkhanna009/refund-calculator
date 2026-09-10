package com.razorpay.refund.saga.steps;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.reconciliation.InternalLedger;
import com.razorpay.refund.saga.SagaContext;
import com.razorpay.refund.saga.SagaStep;
import com.razorpay.refund.service.RefundBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Step 2: Write refund entry to internal ledger.
 *
 * Forward: Create ledger entry with status PROCESSED
 * Compensate: Mark ledger entry as COMPENSATED/VOIDED
 *
 * Idempotent: YES - Uses sagaId as unique constraint
 */
@Component
public class LedgerEntryStep implements SagaStep<SagaContext> {

    private static final Logger log = LoggerFactory.getLogger(LedgerEntryStep.class);

    private final InternalLedgerRepository ledgerRepository;

    public LedgerEntryStep(InternalLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public String getName() {
        return "LEDGER_ENTRY";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public SagaStep.StepResult execute(SagaContext context) throws SagaStepException {
        RefundBreakdown breakdown = context.getBreakdown();
        SagaStep.StepResult gatewayResult = context.getStepResult("GATEWAY_REFUND");
        String gatewayRefundId = gatewayResult != null ? gatewayResult.getOutputRef() : null;

        log.info("Creating ledger entry: sagaId={}, gatewayRefundId={}", context.getSagaId(), gatewayRefundId);

        try {
            InternalLedger.LedgerEntry entry = new InternalLedger.LedgerEntry(
                    context.getSagaId(),           // Our internal refund ID = sagaId
                    gatewayRefundId,               // Razorpay refund ID
                    context.getOrderId(),
                    context.getAllocation().getAll().keySet().stream().findFirst().orElseThrow() // payment ID
                            .name(), // Simplified
                    breakdown,
                    InternalLedger.LedgerEntry.Status.PROCESSED,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    null,
                    null
            );

            ledgerRepository.save(entry);

            log.info("Ledger entry created: sagaId={}, ledgerId={}", context.getSagaId(), entry.getRefundId());

            return SagaStep.StepResult.success(entry.getRefundId(), Map.of(
                    "ledgerId", entry.getRefundId(),
                    "gatewayRefundId", gatewayRefundId
            ));

        } catch (Exception e) {
            log.error("Ledger entry failed: sagaId={}", context.getSagaId(), e);
            throw SagaStepException.compensatable(getName(), "Ledger save failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void compensate(SagaContext context, SagaStep.StepResult previousResult) throws SagaStepException {
        String ledgerId = previousResult.getOutputRef();
        if (ledgerId == null) {
            log.warn("No ledger ID to compensate for sagaId={}", context.getSagaId());
            return;
        }

        log.info("Compensating ledger entry: sagaId={}, ledgerId={}", context.getSagaId(), ledgerId);

        try {
            // Mark as COMPENSATED (soft delete) - don't hard delete for audit
            ledgerRepository.markCompensated(ledgerId, "Saga compensation: " + context.getSagaId());
            log.info("Ledger entry compensated: {}", ledgerId);

        } catch (Exception e) {
            log.error("Failed to compensate ledger: sagaId={}, ledgerId={}", context.getSagaId(), ledgerId, e);
            // Best effort - alert ops
        }
    }

    // Repository interface
    public interface InternalLedgerRepository {
        void save(InternalLedger.LedgerEntry entry);
        void markCompensated(String ledgerId, String reason);
    }
}