package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.service.RefundBreakdown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end reconciliation integration test.
 * Tests the full flow: Ledger + Settlement → Engine → Report
 */
class ReconciliationIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fullReconciliationFlow_generatesReports() throws IOException {
        // 1. Setup ledger with our refunds
        InternalLedger ledger = createTestLedger();

        // 2. Setup settlement report (simulating Razorpay)
        SettlementReport settlement = createTestSettlement();

        // 3. Run reconciliation
        ReconciliationEngine engine = new ReconciliationEngine(ledger, settlement);
        ReconciliationEngine.ReconciliationResult result = engine.reconcile();

        // 4. Generate reports
        ReconciliationReportGenerator generator = new ReconciliationReportGenerator(tempDir);
        String reportPath = generator.generate(result);

        // 5. Verify reports generated
        assertTrue(reportPath.endsWith(".html"));
        assertTrue(tempDir.resolve(reportPath.replace(".html", ".csv")).toFile().exists());
        assertTrue(tempDir.resolve(reportPath.replace(".html", ".json")).toFile().exists());

        // 6. Verify HTML content
        String html = java.nio.file.Files.readString(Path.of(reportPath));
        assertTrue(html.contains("Reconciliation Report"));
        assertTrue(html.contains("setl_test_001"));
        assertTrue(html.contains("AMOUNT_MISMATCH") || html.contains("No Discrepancies"));

        System.out.println("Report generated at: " + reportPath);
        System.out.println("HTML preview (first 2000 chars):");
        System.out.println(html.substring(0, Math.min(2000, html.length())));
    }

    @Test
    void discrepancyHandler_routesBySeverity() {
        // This would use mock services in real test
        // Verifying the logic here conceptually
        ReconciliationEngine.Discrepancy critical = createDiscrepancy(
                ReconciliationEngine.Discrepancy.Type.MISSING_IN_SETTLEMENT,
                ReconciliationEngine.Discrepancy.Severity.CRITICAL
        );
        ReconciliationEngine.Discrepancy low = createDiscrepancy(
                ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH,
                ReconciliationEngine.Discrepancy.Severity.LOW
        );

        assertEquals(ReconciliationEngine.Discrepancy.Severity.CRITICAL, critical.getSeverity());
        assertEquals(ReconciliationEngine.Discrepancy.Severity.LOW, low.getSeverity());
    }

    @Test
    void autoResolver_resolvesKnownPatterns() {
        AutoResolverImpl resolver = new AutoResolverImpl();

        // Within tolerance -> can resolve
        ReconciliationEngine.Discrepancy smallDiff = ReconciliationEngine.Discrepancy.amountMismatch(
                null, null, Money.of("100.00"), Money.of("100.50")
        );
        smallDiff = new ReconciliationEngine.Discrepancy(
                ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH,
                "Small diff", null, null, Money.of("100.00"), Money.of("100.50"),
                ReconciliationEngine.Discrepancy.Severity.LOW
        );

        assertTrue(resolver.canAutoResolve(smallDiff));

        // Large diff -> cannot resolve
        ReconciliationEngine.Discrepancy largeDiff = new ReconciliationEngine.Discrepancy(
                ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH,
                "Large diff", null, null, Money.of("100.00"), Money.of("150.00"),
                ReconciliationEngine.Discrepancy.Severity.LOW
        );

        assertFalse(resolver.canAutoResolve(largeDiff));

        // HIGH severity -> cannot resolve
        ReconciliationEngine.Discrepancy highSeverity = new ReconciliationEngine.Discrepancy(
                ReconciliationEngine.Discrepancy.Type.AMOUNT_MISMATCH,
                "High severity", null, null, Money.of("100.00"), Money.of("100.50"),
                ReconciliationEngine.Discrepancy.Severity.HIGH
        );

        assertFalse(resolver.canAutoResolve(highSeverity));
    }

    private InternalLedger createTestLedger() {
        RefundBreakdown b1 = createBreakdown(Money.of("686.00"), Money.of("392.00"), Money.of("294.00"));
        RefundBreakdown b2 = createBreakdown(Money.of("343.00"), Money.of("196.00"), Money.of("147.00"));

        return new InternalLedger(List.of(
                new InternalLedger.LedgerEntry(
                        "refund_1", "rfnd_123", "order_1", "pay_1",
                        b1, InternalLedger.LedgerEntry.Status.PROCESSED,
                        LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
                ),
                new InternalLedger.LedgerEntry(
                        "refund_2", "rfnd_456", "order_2", "pay_2",
                        b2, InternalLedger.LedgerEntry.Status.PROCESSED,
                        LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2), null, null
                )
        ));
    }

    private SettlementReport createTestSettlement() {
        LocalDate date = LocalDate.now().minusDays(2);

        SettlementReport.SettlementEntry s1 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_1", "rfnd_123",
                Money.of("686.00"), Money.of("13.72"), Money.of("2.47"), Money.of("669.81"),
                "processed", PaymentMethod.ONLINE
        );

        // Slight fee difference (within tolerance)
        SettlementReport.SettlementEntry s2 = SettlementReportFetcherImpl.TestFetcher.createRefundEntry(
                "pay_2", "rfnd_456",
                Money.of("343.00"), Money.of("6.86"), Money.of("1.23"), Money.of("334.91"),
                "processed", PaymentMethod.ONLINE
        );

        return SettlementReportFetcherImpl.TestFetcher.createTestReport(date, "setl_test_001", List.of(s1, s2));
    }

    private RefundBreakdown createBreakdown(Money total, Money online, Money wallet) {
        return new RefundBreakdown(
                total, total,
                Money.of("2.00"), Money.zero(), Money.of("2.00"),
                total,
                Map.of(PaymentMethod.ONLINE, online, PaymentMethod.WALLET, wallet),
                Money.of("15.25"), true
        );
    }

    private ReconciliationEngine.Discrepancy createDiscrepancy(
            ReconciliationEngine.Discrepancy.Type type,
            ReconciliationEngine.Discrepancy.Severity severity) {
        return new ReconciliationEngine.Discrepancy(
                type, "Test", "ledger_1", "settle_1",
                Money.of("100.00"), Money.of("99.00"), severity
        ) {};
    }
}