package com.razorpay.refund.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handles discrepancies detected during reconciliation.
 *
 * Actions by severity:
 * - CRITICAL: Create PagerDuty incident, Slack alert #finance-urgent, auto-create Jira ticket
 * - HIGH: Create Jira ticket, assign to finance team, Slack #finance-recon
 * - MEDIUM: Add to weekly reconciliation review queue
 * - LOW: Log for audit, auto-resolve if pattern matches known issues
 */
@Component
public class DiscrepancyHandlerImpl implements ReconciliationJob.DiscrepancyHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyHandlerImpl.class);

    private final TicketingService ticketingService;
    private final AlertingService alertingService;
    private final AutoResolver autoResolver;

    public DiscrepancyHandlerImpl(
            TicketingService ticketingService,
            AlertingService alertingService,
            AutoResolver autoResolver) {
        this.ticketingService = ticketingService;
        this.alertingService = alertingService;
        this.autoResolver = autoResolver;
    }

    @Override
    public void handle(ReconciliationEngine.Discrepancy discrepancy) {
        log.info("Handling discrepancy: type={}, severity={}, ledgerRefundId={}",
                discrepancy.getType(), discrepancy.getSeverity(), discrepancy.getLedgerRefundId());

        try {
            switch (discrepancy.getSeverity()) {
                case CRITICAL -> handleCritical(discrepancy);
                case HIGH -> handleHigh(discrepancy);
                case MEDIUM -> handleMedium(discrepancy);
                case LOW -> handleLow(discrepancy);
            }
        } catch (Exception e) {
            log.error("Failed to handle discrepancy: {}", discrepancy, e);
            // Don't throw - continue processing other discrepancies
        }
    }

    private void handleCritical(ReconciliationEngine.Discrepancy d) {
        // 1. PagerDuty / OpsGenie incident
        alertingService.createCriticalIncident(
                "Reconciliation: " + d.getType().name(),
                d.getDescription()
        );

        // 2. Slack urgent channel
        alertingService.postToSlack("#finance-urgent",
                ":rotating_light: *CRITICAL Reconciliation Discrepancy*\n" +
                "*Type:* " + d.getType() + "\n" +
                "*Ledger Refund:* " + (d.getLedgerRefundId() != null ? d.getLedgerRefundId() : "N/A") + "\n" +
                "*Settlement Refund:* " + (d.getSettlementRefundId() != null ? d.getSettlementRefundId() : "N/A") + "\n" +
                "*Expected:* " + d.getExpectedAmount() + "\n" +
                "*Actual:* " + d.getActualAmount() + "\n" +
                "*Description:* " + d.getDescription()
        );

        // 3. Auto-create Jira ticket with high priority
        ticketingService.createTicket(
                "RECON-" + d.getType().name(),
                d.getDescription(),
                "CRITICAL",
                Map.of(
                        "ledgerRefundId", d.getLedgerRefundId(),
                        "settlementRefundId", d.getSettlementRefundId(),
                        "expectedAmount", d.getExpectedAmount().toString(),
                        "actualAmount", d.getActualAmount().toString()
                )
        );
    }

    private void handleHigh(ReconciliationEngine.Discrepancy d) {
        // 1. Jira ticket
        ticketingService.createTicket(
                "RECON-" + d.getType().name(),
                d.getDescription(),
                "HIGH",
                Map.of(
                        "ledgerRefundId", d.getLedgerRefundId(),
                        "settlementRefundId", d.getSettlementRefundId()
                )
        );

        // 2. Slack finance channel
        alertingService.postToSlack("#finance-recon",
                ":warning: *HIGH Reconciliation Discrepancy*\n" +
                "*Type:* " + d.getType() + "\n" +
                "*Refund:* " + (d.getLedgerRefundId() != null ? d.getLedgerRefundId() : d.getSettlementRefundId()) + "\n" +
                "*Details:* " + d.getDescription()
        );
    }

    private void handleMedium(ReconciliationEngine.Discrepancy d) {
        // Add to weekly review queue
        ticketingService.addToReviewQueue(
                "RECON-" + d.getType().name(),
                d.getDescription(),
                Map.of("ledgerRefundId", d.getLedgerRefundId())
        );

        log.info("Added to weekly review queue: {}", d.getType());
    }

    private void handleLow(ReconciliationEngine.Discrepancy d) {
        // Try auto-resolve for known patterns
        if (autoResolver.canAutoResolve(d)) {
            autoResolver.resolve(d);
            log.info("Auto-resolved LOW discrepancy: {}", d.getType());
        } else {
            // Log for audit
            log.info("LOW discrepancy logged for audit: {}", d.getDescription());
        }
    }

    // ===== Interfaces for external systems =====

    public interface TicketingService {
        String createTicket(String summary, String description, String priority, Map<String, String> fields);
        void addToReviewQueue(String summary, String description, Map<String, String> fields);
    }

    public interface AlertingService {
        void createCriticalIncident(String title, String details);
        void postToSlack(String channel, String message);
    }

    public interface AutoResolver {
        boolean canAutoResolve(ReconciliationEngine.Discrepancy discrepancy);
        void resolve(ReconciliationEngine.Discrepancy discrepancy);
    }
}