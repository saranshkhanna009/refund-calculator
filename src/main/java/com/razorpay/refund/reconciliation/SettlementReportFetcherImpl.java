package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Production implementation: fetches settlement report from Razorpay.
 *
 * Two approaches:
 * 1. SFTP: Razorpay drops CSV/Excel on SFTP server daily ~4-5 AM
 * 2. API: Razorpay Settlement API (requires partnership)
 *
 * This implementation simulates both for demo/testing.
 */
public class SettlementReportFetcherImpl implements ReconciliationJob.SettlementReportFetcher {

    private final RazorpaySettlementApiClient apiClient;
    private final SftpSettlementDownloader sftpDownloader;
    private final SettlementReportParser parser;

    public SettlementReportFetcherImpl(
            RazorpaySettlementApiClient apiClient,
            SftpSettlementDownloader sftpDownloader,
            SettlementReportParser parser) {
        this.apiClient = apiClient;
        this.sftpDownloader = sftpDownloader;
        this.parser = parser;
    }

    @Override
    public SettlementReport fetch(LocalDate settlementDate) throws IOException {
        // Try API first (real-time), fallback to SFTP
        try {
            return fetchFromApi(settlementDate);
        } catch (Exception e) {
            log.warn("API fetch failed, falling back to SFTP: {}", e.getMessage());
            return fetchFromSftp(settlementDate);
        }
    }

    private SettlementReport fetchFromApi(LocalDate settlementDate) {
        // Call Razorpay Settlement API
        // GET /v1/settlements?settlement_date=2024-01-15
        // Returns paginated list of settlements
        String apiResponse = apiClient.getSettlements(settlementDate);
        return parser.parse(apiResponse);
    }

    private SettlementReport fetchFromSftp(LocalDate settlementDate) throws IOException {
        // Download from SFTP: /settlements/YYYY/MM/DD/settlement_YYYYMMDD.csv
        String remotePath = String.format("/settlements/%tY/%<tm/%<td/settlement_%<tY%<tm%<td.csv", settlementDate);
        Path localFile = sftpDownloader.download(remotePath);
        String content = Files.readString(localFile);
        return parser.parseCsv(content);
    }

    // ===== Test Implementation (for unit tests) =====

    public static class TestFetcher implements ReconciliationJob.SettlementReportFetcher {
        private final List<SettlementReport> reports = new ArrayList<>();

        public void addReport(SettlementReport report) {
            reports.add(report);
        }

        @Override
        public SettlementReport fetch(LocalDate settlementDate) {
            return reports.stream()
                    .filter(r -> r.getSettlementDate().equals(settlementDate))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No settlement report for " + settlementDate));
        }

        // Helper to create test settlement report
        public static SettlementReport createTestReport(LocalDate date, String settlementId,
                                                        List<SettlementReport.SettlementEntry> entries) {
            return new SettlementReport(settlementId, date, entries);
        }

        public static SettlementReport.SettlementEntry createRefundEntry(
                String paymentId, String refundId, Money gross, Money fee, Money tax, Money net,
                String status, PaymentMethod method) {
            return new SettlementReport.SettlementEntry(
                    paymentId, refundId, SettlementReport.SettlementType.REFUND,
                    gross, fee, tax, net, status, "UTR" + UUID.randomUUID().toString().substring(0, 8),
                    LocalDate.now().minusDays(2), method
            );
        }

        public static SettlementReport.SettlementEntry createPaymentEntry(
                String paymentId, Money gross, Money fee, Money tax, Money net,
                String status, PaymentMethod method) {
            return new SettlementReport.SettlementEntry(
                    paymentId, null, SettlementReport.SettlementType.PAYMENT,
                    gross, fee, tax, net, status, "UTR" + UUID.randomUUID().toString().substring(0, 8),
                    LocalDate.now().minusDays(2), method
            );
        }
    }

    // ===== Stubs for production dependencies =====

    interface RazorpaySettlementApiClient {
        String getSettlements(LocalDate date);
    }

    interface SftpSettlementDownloader {
        Path download(String remotePath) throws IOException;
    }

    interface SettlementReportParser {
        SettlementReport parse(String content);
        SettlementReport parseCsv(String csvContent);
    }

    private static final Logger log = LoggerFactory.getLogger(SettlementReportFetcherImpl.class);
}