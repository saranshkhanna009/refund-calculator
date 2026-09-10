package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Auto-resolves known low-severity discrepancy patterns.
 *
 * Common auto-resolvable patterns:
 * 1. Rounding differences < ₹1 (within tolerance)
 * 2. Timing differences (settled in adjacent batch)
 * 3. Known fee calculation variations (bank-specific MDR)
 * 4. Reversed refunds (chargeback) that appear in next settlement
 */
@Component
public class AutoResolverImpl implements DiscrepancyHandlerImpl.AutoResolver {

    private static final Logger log = LoggerFactory.getLogger(AutoResolverImpl.class);

    // Known patterns that are safe to auto-resolve
    private static final Set<String> AUTO_RESOLVABLE_TYPES = Set.of(
            "AMOUNT_MISMATCH",  // If within tolerance
            "TIMING_DIFFERENCE"
    );

    // Bank-specific fee variations (some banks charge different MDR)
    private static final Set<String> KNOWN_FEE_VARIATIONS = Set.of(
            "HDFC", "ICICI", "SBI", "AXIS", "KOTAK"
    );

    @Override
    public boolean canAutoResolve(ReconciliationEngine.Discrepancy discrepancy) {
        // Only auto-resolve LOW severity
        if (discrepancy.getSeverity() != ReconciliationEngine.Discrepancy.Severity.LOW) {
            return false;
        }

        // Only known types
        if (!AUTO_RESOLVABLE_TYPES.contains(discrepancy.getType().name())) {
            return false;
        }

        // For amount mismatch: check if within tolerance
        if (discrepancy.getType() == ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH) {
            Money diff = discrepancy.getExpectedAmount().subtract(discrepancy.getActualAmount());
            return diff.getAmount().abs().compareTo(Money.of("1.00").getAmount()) <= 0;
        }

        // Timing differences are usually auto-resolvable
        return true;
    }

    @Override
    public void resolve(ReconciliationEngine.Discrepancy discrepancy) {
        log.info("Auto-resolving discrepancy: {}", discrepancy.getDescription());

        // In production:
        // 1. Update ledger status to SETTLED
        // 2. Add reconciliation note
        // 3. Mark discrepancy as RESOLVED_AUTO
        // 4. Update metrics

        // For now, just log
        log.debug("Resolved: type={}, ledgerRefundId={}, settlementRefundId={}",
                discrepancy.getType(),
                discrepancy.getLedgerRefundId(),
                discrepancy.getSettlementRefundId());
    }

    /**
     * Checks if a fee variation is a known bank-specific difference.
     */
    public boolean isKnownFeeVariation(String bankName, Money expectedFee, Money actualFee) {
        if (!KNOWN_FEE_VARIATIONS.contains(bankName.toUpperCase())) {
            return false;
        }

        Money diff = expectedFee.subtract(actualFee);
        // Allow up to 0.1% difference for known banks
        Money tolerance = expectedFee.multiply(0.001);
        return diff.getAmount().abs().compareTo(tolerance.getAmount()) <= 0;
    }
}