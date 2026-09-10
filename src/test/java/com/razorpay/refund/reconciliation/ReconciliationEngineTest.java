package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.service.RefundBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the reconciliation engine.
 * Verifies matching logic and discrepancy detection.
 */
class ReconciliationEngineTest {

    private InternalLedger ledger;
    private SettlementReport settlement;

    @BeforeEach
    void setUp() {
        // Create test ledger entries
        RefundBreakdown breakdown1 = createBreakdown(Money.of("686.00"), Money.of("392.00"), Money.of("294.00"));
        RefundBreakdown breakdown2 = createBreakdown(Money.of("343.00"), Money.of("196.00"), Money.of("147.00"));

        InternalLedger.LedgerEntry entry1 = new InternalLedger.LedgerEntry(
                "refund_1", "rfnd_123", "order_1", "pay_1",
                breakdown1, InternalLedger.LedgerEntry.Status.PROCESSED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        InternalLedger.LedgerEntry entry2 = new InternalLedger.LedgerEntry(
                "refund_2", "rfnd_456", "order_2", "pay_2",
                breakdown2, InternalLedger.LedgerEntry.Status.PROCESSED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        // Entry 3: Failed in our system but actually settled
        RefundBreakdown breakdown3 = createBreakdown(Money.of("100.00"), Money.of("100.00"), Money.zero());
        InternalLedger.LedgerEntry entry3 = new InternalLedger.LedgerEntry(
                "refund_3", "rfnd_789", "order_3", "pay_3",
                breakdown3, InternalLedger.LedgerEntry.Status.FAILED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        ledger = new InternalLedger(List.of(entry1, entry2, entry3));

        // Create settlement report
        LocalDate settlementDate = LocalDate.now().minusDays(2);

        // Entry 1: Matches perfectly
        SettlementReport.SettlementEntry s1 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_1", "rfnd_123",
                Money.of("686.00"), Money.of("13.72"), Money.of("2.47"), Money.of("669.81"),
                "processed", PaymentMethod.ONLINE
        );

        // Entry 2: Amount mismatch (fee difference)
        SettlementReport.SettlementEntry s2 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_2", "rfnd_456",
                Money.of("343.00"), Money.of("6.86"), Money.of("1.23"), Money.of("334.91"),
                "processed", PaymentMethod.ONLINE
        );

        // Entry 3: We think FAILED, but settlement shows PROCESSED
        SettlementReport.SettlementEntry s3 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_3", "rfnd_789",
                Money.of("100.00"), Money.of("2.00"), Money.of("0.36"), Money.of("97.64"),
                "processed", PaymentMethod.ONLINE
        );

        // Entry 4: Extra refund in settlement (missing in our ledger)
        SettlementReport.SettlementEntry s4 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_4", "rfnd_999",
                Money.of("500.00"), Money.of("10.00"), Money.of("1.80"), Money.of("488.20"),
                "processed", PaymentMethod.ONLINE
        );

        settlement = SettlementReportFetcherImpl.TestFetcher.createTestReport(
                settlementDate, "setl_test_001", List.of(s1, s2, s3, s4)
        );
    }

    @Test
    void perfectMatch_noDiscrepancies() {
        // Create ledger with only matching entries
        RefundBreakdown b1 = createBreakdown(Money.of("686.00"), Money.of("392.00"), Money.of("294.00"));
        InternalLedger.LedgerEntry e1 = new InternalLedger.LedgerEntry(
                "refund_1", "rfnd_123", "order_1", "pay_1",
                b1, InternalLedger.LedgerEntry.Status.PROCESSED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        InternalLedger cleanLedger = new InternalLedger(List.of(e1));

        SettlementReport.SettlementEntry s1 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_1", "rfnd_123",
                Money.of("686.00"), Money.of("13.72"), Money.of("2.47"), Money.of("669.81"),
                "processed", PaymentMethod.ONLINE
        );

        SettlementReport cleanSettlement = SettlementReportFetcherImpl.TestFetcher.createTestReport(
                LocalDate.now().minusDays(2), "setl_clean", List.of(s1)
        );

        ReconciliationEngine engine = new ReconciliationEngine(cleanLedger, cleanSettlement);
        ReconciliationEngine.ReconciliationResult result = engine.reconcile();

        assertTrue(result.isClean());
        assertEquals(1, result.getMatchedCount());
        assertEquals(0, result.getDiscrepancyCount());
    }

    @Test
    void detectsAllDiscrepancyTypes() {
        ReconciliationEngine engine = new ReconciliationEngine(ledger, settlement);
        ReconciliationEngine.ReconciliationResult result = engine.reconcile();

        System.out.println("Result: " + result);
        result.getDiscrepancies().forEach(System.out::println);

        // Should find 4 discrepancies:
        // 1. AMOUNT_MISMATCH for rfnd_456 (fee difference)
        // 2. STATUS_MISMATCH for rfnd_789 (we think FAILED, they say PROCESSED)
        // 3. MISSING_IN_LEDGER for rfnd_999 (extra in settlement)
        // 4. MISSING_IN_SETTLEMENT for refund_2 (rfnd_456) - wait, this is matched
        // Actually: refund_2 (rfnd_456) IS in settlement but with amount mismatch
        // So missing_in_settlement would be for any ledger entry NOT in settlement

        assertEquals(3, result.getDiscrepancyCount());

        // Verify types
        List<ReconciliationEngine.Discrepancy.Type> types = result.getDiscrepancies().stream()
                .map(ReconciliationEngine.Discrepancy::getType)
                .toList();

        assertTrue(types.contains(ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH));
        assertTrue(types.contains(ReconciliationEngine.Discrepancy.Type.STATUS_MISMATCH));
        assertTrue(types.contains(ReconciliationEngine.Discrepancy.Type.MISSING_IN_LEDGER));
    }

    @Test
    void amountMismatch_severityBasedOnDifference() {
        // Large difference -> HIGH severity
        RefundBreakdown b = createBreakdown(Money.of("1000.00"), Money.of("1000.00"), Money.zero());
        InternalLedger.LedgerEntry ledgerEntry = new InternalLedger.LedgerEntry(
                "refund_big", "rfnd_big", "order_big", "pay_big",
                b, InternalLedger.LedgerEntry.Status.PROCESSED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        InternalLedger l = new InternalLedger(List.of(ledgerEntry));

        // Settlement shows ₹100 less (big difference)
        SettlementReport.SettlementEntry s = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_big", "rfnd_big",
                Money.of("1000.00"), Money.of("20.00"), Money.of("3.60"), Money.of("900.00"), // ₹76 less than expected!
                "processed", PaymentMethod.ONLINE
        );

        SettlementReport sett = SettlementReportFetcherImpl.TestFetcher.createTestReport(
                LocalDate.now().minusDays(2), "setl_big", List.of(s)
        );

        ReconciliationEngine engine = new ReconciliationEngine(l, sett);
        ReconciliationEngine.ReconciliationResult result = engine.reconcile();

        ReconciliationEngine.Discrepancy disc = result.getDiscrepancies().get(0);
        assertEquals(ReconciliationEngine.Discrepancy.Severity.HIGH, disc.getSeverity());
    }

    @Test
    void toleranceHandlesRoundingDifferences() {
        // Small difference (₹0.50) should be within tolerance
        RefundBreakdown b = createBreakdown(Money.of("100.00"), Money.of("100.00"), Money.zero());
        InternalLedger.LedgerEntry ledgerEntry = new InternalLedger.LedgerEntry(
                "refund_tol", "rfnd_tol", "order_tol", "pay_tol",
                b, InternalLedger.LedgerEntry.Status.PROCESSED,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
        );

        InternalLedger l = new InternalLedger(List.of(ledgerEntry));

        // Settlement shows ₹0.50 less (within ₹1 tolerance)
        SettlementReport.SettlementEntry s = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_tol", "rfnd_tol",
                Money.of("100.00"), Money.of("2.00"), Money.of("0.36"), Money.of("97.14"), // Expected ~97.64
                "processed", PaymentMethod.ONLINE
        );

        SettlementReport sett = SettlementReportFetcherImpl.TestFetcher.createTestReport(
                LocalDate.now().minusDays(2), "setl_tol", List.of(s)
        );

        ReconciliationEngine engine = new ReconciliationEngine(l, sett);
        ReconciliationEngine.ReconciliationResult result = engine.reconcile();

        // Should be clean (within tolerance)
        assertTrue(result.isClean());
    }

    private RefundBreakdown createBreakdown(Money total, Money online, Money wallet) {
        // Simplified - in real test use RefundCalculator
        return new RefundBreakdown(
                total, total,
                Money.of("2.00"), Money.zero(), Money.of("2.00"),
                total,
                Map.of(PaymentMethod.ONLINE, online, PaymentMethod.WALLET, wallet),
                Money.of("15.25"), true
        );
    }
}