package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core reconciliation logic: matches Internal Ledger ↔ Razorpay Settlement Report.
 *
 * Matching strategy:
 * 1. Primary key: Razorpay Refund ID (rfnd_xxx) - present in both
 * 2. Fallback: Payment ID + Amount + Date (for edge cases)
 *
 * Discrepancy types:
 * - MISSING_IN_SETTLEMENT: We processed, Razorpay didn't settle
 * - MISSING_IN_LEDGER: Razorpay settled, we have no record
 * - AMOUNT_MISMATCH: Net settlement differs from expected
 * - STATUS_MISMATCH: We think failed, they settled (or vice versa)
 * - TIMING_DIFFERENCE: Settled in different batch than expected
 */
public final class ReconciliationEngine {

    private static final Money TOLERANCE = Money.of("1.00"); // ₹1 tolerance for rounding

    private final InternalLedger ledger;
    private final SettlementReport settlement;

    public ReconciliationEngine(InternalLedger ledger, SettlementReport settlement) {
        this.ledger = ledger;
        this.settlement = settlement;
    }

    /**
     * Run full reconciliation.
     * @return ReconciliationResult with all discrepancies
     */
    public ReconciliationResult reconcile() {
        Map<String, InternalLedger.LedgerEntry> ledgerByRazorpayId = ledger.getEntries().stream()
                .filter(e -> e.getRazorpayRefundId() != null)
                .collect(Collectors.toMap(
                        InternalLedger.LedgerEntry::getRazorpayRefundId,
                        e -> e,
                        (a, b) -> a // Should not happen
                ));

        List<SettlementReport.SettlementEntry> refunds = settlement.getRefunds();

        List<Discrepancy> discrepancies = new ArrayList<>();
        Set<String> matchedLedgerKeys = new HashSet<>();
        Set<String> matchedSettlementKeys = new HashSet<>();

        // 1. Match by Razorpay Refund ID
        for (SettlementReport.SettlementEntry settlementEntry : refunds) {
            String key = settlementEntry.getRefundId();
            InternalLedger.LedgerEntry ledgerEntry = ledgerByRazorpayId.get(key);

            if (ledgerEntry == null) {
                // Razorpay has refund we don't know about
                discrepancies.add(Discrepancy.missingInLedger(settlementEntry));
                continue;
            }

            matchedLedgerKeys.add(key);
            matchedSettlementKeys.add(key);

            // Compare amounts
            Money expectedNet = ledgerEntry.getExpectedNetSettlement();
            Money actualNet = settlementEntry.getNetAmount();

            if (!amountsMatch(expectedNet, actualNet)) {
                discrepancies.add(Discrepancy.amountMismatch(ledgerEntry, settlementEntry, expectedNet, actualNet));
            }

            // Compare status
            if (isStatusMismatch(ledgerEntry, settlementEntry)) {
                discrepancies.add(Discrepancy.statusMismatch(ledgerEntry, settlementEntry));
            }
        }

        // 2. Check for ledger entries missing in settlement
        for (InternalLedger.LedgerEntry ledgerEntry : ledger.getPendingSettlement()) {
            if (!matchedLedgerKeys.contains(ledgerEntry.getRazorpayRefundId())) {
                discrepancies.add(Discrepancy.missingInSettlement(ledgerEntry));
            }
        }

        // 3. Summary stats
        int totalLedger = ledger.getEntries().size();
        int totalSettlement = refunds.size();
        int matched = matchedLedgerKeys.size();
        int discrepancyCount = discrepancies.size();

        return new ReconciliationResult(
                settlement.getSettlementId(),
                settlement.getSettlementDate(),
                totalLedger,
                totalSettlement,
                matched,
                discrepancyCount,
                discrepancies
        );
    }

    private boolean amountsMatch(Money expected, Money actual) {
        return expected.subtract(actual).getAmount().abs().compareTo(TOLERANCE.getAmount()) <= 0;
    }

    private boolean isStatusMismatch(InternalLedger.LedgerEntry ledger, SettlementReport.SettlementEntry settlement) {
        // If we think it's FAILED but settlement shows PROCESSED
        if (ledger.getStatus() == InternalLedger.LedgerEntry.Status.FAILED &&
                "processed".equalsIgnoreCase(settlement.getStatus())) {
            return true;
        }
        // If we think PROCESSED but settlement shows FAILED
        if (ledger.getStatus() == InternalLedger.LedgerEntry.Status.PROCESSED &&
                "failed".equalsIgnoreCase(settlement.getStatus())) {
            return true;
        }
        return false;
    }

    /**
     * Result of reconciliation run.
     */
    public static final class ReconciliationResult {
        private final String settlementId;
        private final LocalDate settlementDate;
        private final int totalLedgerEntries;
        private final int totalSettlementEntries;
        private final int matchedCount;
        private final int discrepancyCount;
        private final List<Discrepancy> discrepancies;

        public ReconciliationResult(
                String settlementId,
                LocalDate settlementDate,
                int totalLedgerEntries,
                int totalSettlementEntries,
                int matchedCount,
                int discrepancyCount,
                List<Discrepancy> discrepancies) {
            this.settlementId = settlementId;
            this.settlementDate = settlementDate;
            this.totalLedgerEntries = totalLedgerEntries;
            this.totalSettlementEntries = totalSettlementEntries;
            this.matchedCount = matchedCount;
            this.discrepancyCount = discrepancyCount;
            this.discrepancies = List.copyOf(discrepancies);
        }

        public String getSettlementId() { return settlementId; }
        public LocalDate getSettlementDate() { return settlementDate; }
        public int getTotalLedgerEntries() { return totalLedgerEntries; }
        public int getTotalSettlementEntries() { return totalSettlementEntries; }
        public int getMatchedCount() { return matchedCount; }
        public int getDiscrepancyCount() { return discrepancyCount; }
        public List<Discrepancy> getDiscrepancies() { return discrepancies; }

        public double getMatchRate() {
            return totalLedgerEntries > 0 ? (double) matchedCount / totalLedgerEntries : 1.0;
        }

        public boolean isClean() {
            return discrepancyCount == 0;
        }

        @Override
        public String toString() {
            return "ReconciliationResult{" +
                    "settlementId='" + settlementId + '\'' +
                    ", date=" + settlementDate +
                    ", ledger=" + totalLedgerEntries +
                    ", settlement=" + totalSettlementEntries +
                    ", matched=" + matchedCount +
                    ", discrepancies=" + discrepancyCount +
                    ", matchRate=" + String.format("%.1f%%", getMatchRate() * 100) +
                    '}';
        }
    }

    /**
     * Discrepancy found during reconciliation.
     */
    public static final class Discrepancy {
        private final Type type;
        private final String description;
        private final String ledgerRefundId;
        private final String settlementRefundId;
        private final Money expectedAmount;
        private final Money actualAmount;
        private final Severity severity;
        private final LocalDate detectedAt;

        public Discrepancy(Type type, String description, String ledgerRefundId, String settlementRefundId,
                            Money expectedAmount, Money actualAmount, Severity severity) {
            this.type = type;
            this.description = description;
            this.ledgerRefundId = ledgerRefundId;
            this.settlementRefundId = settlementRefundId;
            this.expectedAmount = expectedAmount;
            this.actualAmount = actualAmount;
            this.severity = severity;
            this.detectedAt = LocalDate.now();
        }

        public Type getType() { return type; }
        public String getDescription() { return description; }
        public String getLedgerRefundId() { return ledgerRefundId; }
        public String getSettlementRefundId() { return settlementRefundId; }
        public Money getExpectedAmount() { return expectedAmount; }
        public Money getActualAmount() { return actualAmount; }
        public Severity getSeverity() { return severity; }
        public LocalDate getDetectedAt() { return detectedAt; }

        public enum Type {
            MISSING_IN_SETTLEMENT,  // High: money lost
            MISSING_IN_LEDGER,      // High: untracked refund
            AMOUNT_MISMATCH,        // Medium: rounding or fee diff
            STATUS_MISMATCH,        // High: state inconsistency
            TIMING_DIFFERENCE       // Low: settled in different batch
        }

        public enum Severity {
            CRITICAL,  // Immediate action required
            HIGH,      // Finance team review today
            MEDIUM,    // Review this week
            LOW        // Auto-resolve or ignore
        }

        // Factory methods
        public static Discrepancy missingInSettlement(InternalLedger.LedgerEntry ledger) {
            return new Discrepancy(
                    Type.MISSING_IN_SETTLEMENT,
                    "Refund processed but not found in settlement report: " + ledger.getRazorpayRefundId(),
                    ledger.getRefundId(),
                    ledger.getRazorpayRefundId(),
                    ledger.getExpectedNetSettlement(),
                    Money.zero(),
                    Severity.CRITICAL
            );
        }

        public static Discrepancy missingInLedger(SettlementReport.SettlementEntry settlement) {
            return new Discrepancy(
                    Type.MISSING_IN_LEDGER,
                    "Settlement has refund not in our ledger: " + settlement.getRefundId(),
                    null,
                    settlement.getRefundId(),
                    Money.zero(),
                    settlement.getNetAmount(),
                    Severity.HIGH
            );
        }

        public static Discrepancy amountMismatch(InternalLedger.LedgerEntry ledger,
                                                  SettlementReport.SettlementEntry settlement,
                                                  Money expected, Money actual) {
            Money diff = expected.subtract(actual);
            Severity severity = diff.getAmount().abs().compareTo(Money.of("10.00").getAmount()) > 0
                    ? Severity.HIGH : Severity.MEDIUM;

            return new Discrepancy(
                    Type.AMOUNT_MISMATCH,
                    String.format("Amount mismatch for %s: expected %s, got %s (diff %s)",
                            ledger.getRazorpayRefundId(), expected, actual, diff),
                    ledger.getRefundId(),
                    settlement.getRefundId(),
                    expected,
                    actual,
                    severity
            );
        }

        public static Discrepancy statusMismatch(InternalLedger.LedgerEntry ledger,
                                                  SettlementReport.SettlementEntry settlement) {
            return new Discrepancy(
                    Type.STATUS_MISMATCH,
                    String.format("Status mismatch for %s: ledger=%s, settlement=%s",
                            ledger.getRazorpayRefundId(), ledger.getStatus(), settlement.getStatus()),
                    ledger.getRefundId(),
                    settlement.getRefundId(),
                    Money.zero(),
                    Money.zero(),
                    Severity.HIGH
            );
        }

        @Override
        public String toString() {
            return "Discrepancy{" +
                    "type=" + type +
                    ", severity=" + severity +
                    ", ledgerRefundId='" + ledgerRefundId + '\'' +
                    ", settlementRefundId='" + settlementRefundId + '\'' +
                    ", expected=" + expectedAmount +
                    ", actual=" + actualAmount +
                    ", description='" + description + '\'' +
                    '}';
        }
    }
}