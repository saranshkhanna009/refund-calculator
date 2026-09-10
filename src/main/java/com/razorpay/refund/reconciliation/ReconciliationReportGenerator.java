package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates human-readable reconciliation reports.
 * Output formats: HTML (for email/dashboard), CSV (for finance), JSON (for API).
 */
public class ReconciliationReportGenerator implements ReconciliationJob.ReconciliationReportGenerator {

    private final Path outputDir;

    public ReconciliationReportGenerator(@Value("${reconciliation.report.dir:/tmp/reconciliation}") Path outputDir) {
        this.outputDir = outputDir;
    }

    @Override
    public String generate(ReconciliationEngine.ReconciliationResult result) throws IOException {
        Files.createDirectories(outputDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String baseName = "reconciliation_" + result.getSettlementId() + "_" + timestamp;

        // Generate all formats
        Path htmlPath = outputDir.resolve(baseName + ".html");
        Path csvPath = outputDir.resolve(baseName + ".csv");
        Path jsonPath = outputDir.resolve(baseName + ".json");

        Files.writeString(htmlPath, generateHtml(result));
        Files.writeString(csvPath, generateCsv(result));
        Files.writeString(jsonPath, generateJson(result));

        return htmlPath.toString();
    }

    private String generateHtml(ReconciliationEngine.ReconciliationResult result) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Reconciliation Report - %s</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 40px; }
                        .header { border-bottom: 2px solid #e0e0e0; padding-bottom: 20px; margin-bottom: 30px; }
                        .summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 30px; }
                        .card { background: #f8f9fa; border-radius: 8px; padding: 20px; }
                        .card.critical { border-left: 4px solid #dc3545; }
                        .card.high { border-left: 4px solid #fd7e14; }
                        .card.medium { border-left: 4px solid #ffc107; }
                        .card.low { border-left: 4px solid #28a745; }
                        .card h3 { margin: 0 0 10px 0; font-size: 14px; color: #666; }
                        .card .value { font-size: 32px; font-weight: bold; }
                        table { width: 100%%; border-collapse: collapse; }
                        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #e0e0e0; }
                        th { background: #f8f9fa; font-weight: 600; }
                        .severity-critical { background: #fff5f5; }
                        .severity-high { background: #fff8f0; }
                        .severity-medium { background: #fffdf5; }
                        .severity-low { background: #f0fff4; }
                        .money { font-family: monospace; }
                        .badge { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
                        .badge-critical { background: #dc3545; color: white; }
                        .badge-high { background: #fd7e14; color: white; }
                        .badge-medium { background: #ffc107; color: black; }
                        .badge-low { background: #28a745; color: white; }
                    </style>
                </head>
                <body>
                """.formatted(result.getSettlementId()));

        // Header
        html.append("""
                <div class="header">
                    <h1>🔄 Reconciliation Report</h1>
                    <p><strong>Settlement ID:</strong> %s | <strong>Date:</strong> %s | <strong>Generated:</strong> %s</p>
                </div>
                """.formatted(
                result.getSettlementId(),
                result.getSettlementDate(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        // Summary cards
        html.append("""
                <div class="summary">
                    <div class="card"><h3>Ledger Entries</h3><div class="value">%d</div></div>
                    <div class="card"><h3>Settlement Entries</h3><div class="value">%d</div></div>
                    <div class="card"><h3>Matched</h3><div class="value">%d</div></div>
                    <div class="card"><h3>Match Rate</h3><div class="value">%.1f%%</div></div>
                </div>
                """.formatted(
                result.getTotalLedgerEntries(),
                result.getTotalSettlementEntries(),
                result.getMatchedCount(),
                result.getMatchRate() * 100
        ));

        // Discrepancies by severity
        Map<ReconciliationEngine.Discrepancy.Severity, Long> severityCounts = result.getDiscrepancies().stream()
                .collect(Collectors.groupingBy(ReconciliationEngine.Discrepancy::getSeverity, Collectors.counting()));

        html.append("<h2>Discrepancies by Severity</h2><div class='summary'>");
        for (ReconciliationEngine.Discrepancy.Severity sev : ReconciliationEngine.Discrepancy.Severity.values()) {
            long count = severityCounts.getOrDefault(sev, 0L);
            html.append("""
                    <div class="card %s"><h3>%s</h3><div class="value">%d</div></div>
                    """.formatted(sev.name().toLowerCase(), sev.name(), count));
        }
        html.append("</div>");

        // Discrepancy table
        if (!result.getDiscrepancies().isEmpty()) {
            html.append("""
                    <h2>Discrepancy Details</h2>
                    <table>
                        <thead>
                            <tr>
                                <th>Severity</th>
                                <th>Type</th>
                                <th>Ledger Refund ID</th>
                                <th>Settlement Refund ID</th>
                                <th>Expected</th>
                                <th>Actual</th>
                                <th>Description</th>
                            </tr>
                        </thead>
                        <tbody>
                    """);

            for (ReconciliationEngine.Discrepancy d : result.getDiscrepancies()) {
                html.append("""
                        <tr class="severity-%s">
                            <td><span class="badge badge-%s">%s</span></td>
                            <td>%s</td>
                            <td class="money">%s</td>
                            <td class="money">%s</td>
                            <td class="money">%s</td>
                            <td class="money">%s</td>
                            <td>%s</td>
                        </tr>
                        """.formatted(
                        d.getSeverity().name().toLowerCase(),
                        d.getSeverity().name().toLowerCase(),
                        d.getSeverity().name(),
                        d.getType().name(),
                        d.getLedgerRefundId() != null ? d.getLedgerRefundId() : "-",
                        d.getSettlementRefundId() != null ? d.getSettlementRefundId() : "-",
                        d.getExpectedAmount(),
                        d.getActualAmount(),
                        d.getDescription()
                ));
            }

            html.append("</tbody></table>");
        } else {
            html.append("<h2>✅ No Discrepancies Found</h2><p class='card' style='background:#d4edda; color:#155724;'>All entries matched perfectly!</p>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String generateCsv(ReconciliationEngine.ReconciliationResult result) {
        StringBuilder csv = new StringBuilder();
        csv.append("Severity,Type,Ledger Refund ID,Settlement Refund ID,Expected Amount,Actual Amount,Description\n");

        for (ReconciliationEngine.Discrepancy d : result.getDiscrepancies()) {
            csv.append(String.format("%s,%s,%s,%s,%s,%s,\"%s\"\n",
                    d.getSeverity(),
                    d.getType(),
                    d.getLedgerRefundId() != null ? d.getLedgerRefundId() : "",
                    d.getSettlementRefundId() != null ? d.getSettlementRefundId() : "",
                    d.getExpectedAmount(),
                    d.getActualAmount(),
                    d.getDescription().replace("\"", "\"\"")
            ));
        }

        return csv.toString();
    }

    private String generateJson(ReconciliationEngine.ReconciliationResult result) {
        return """
                {
                  "settlementId": "%s",
                  "settlementDate": "%s",
                  "generatedAt": "%s",
                  "summary": {
                    "totalLedgerEntries": %d,
                    "totalSettlementEntries": %d,
                    "matchedCount": %d,
                    "discrepancyCount": %d,
                    "matchRate": %.4f
                  },
                  "discrepancies": [
                %s
                  ]
                }
                """.formatted(
                result.getSettlementId(),
                result.getSettlementDate(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                result.getTotalLedgerEntries(),
                result.getTotalSettlementEntries(),
                result.getMatchedCount(),
                result.getDiscrepancyCount(),
                result.getMatchRate(),
                result.getDiscrepancies().stream()
                        .map(this::discrepancyToJson)
                        .collect(Collectors.joining(",\n"))
        );
    }

    private String discrepancyToJson(ReconciliationEngine.Discrepancy d) {
        return """
                    {
                      "severity": "%s",
                      "type": "%s",
                      "ledgerRefundId": %s,
                      "settlementRefundId": %s,
                      "expectedAmount": %s,
                      "actualAmount": %s,
                      "description": "%s"
                    }
                """.formatted(
                d.getSeverity(),
                d.getType(),
                d.getLedgerRefundId() != null ? "\"" + d.getLedgerRefundId() + "\"" : "null",
                d.getSettlementRefundId() != null ? "\"" + d.getSettlementRefundId() + "\"" : "null",
                d.getExpectedAmount() != null ? "\"" + d.getExpectedAmount() + "\"" : "null",
                d.getActualAmount() != null ? "\"" + d.getActualAmount() + "\"" : "null",
                d.getDescription().replace("\"", "\\\"")
        );
    }
}