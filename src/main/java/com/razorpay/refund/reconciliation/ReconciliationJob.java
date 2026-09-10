package com.razorpay.refund.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Daily reconciliation job.
 *
 * Runs at 6 AM (after settlement reports are available ~4-5 AM).
 * Reads: Internal Ledger (DB) + Razorpay Settlement Report (SFTP/API)
 * Outputs: Discrepancy report → Finance team + Auto-tickets
 *
 * Settlement timeline:
 * - Day 0: Payment/Refund initiated
 * - Day 1: Refund processed by gateway
 * - Day 2/3: Settlement report generated (T+2 for most, T+3 for some banks)
 * - Day 3/4: Reconciliation runs, discrepancies detected
 */
@Configuration
@EnableScheduling
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final SettlementReportFetcher settlementFetcher;
    private final InternalLedgerRepository ledgerRepository;
    private final DiscrepancyHandler discrepancyHandler;
    private final ReconciliationReportGenerator reportGenerator;

    public ReconciliationJob(
            SettlementReportFetcher settlementFetcher,
            InternalLedgerRepository ledgerRepository,
            DiscrepancyHandler discrepancyHandler,
            ReconciliationReportGenerator reportGenerator) {
        this.settlementFetcher = settlementFetcher;
        this.ledgerRepository = ledgerRepository;
        this.discrepancyHandler = discrepancyHandler;
        this.reportGenerator = reportGenerator;
    }

    /**
     * Scheduled daily at 6 AM IST.
     * Can also be triggered manually via actuator endpoint.
     */
    @Scheduled(cron = "0 0 6 * * ?", zone = "Asia/Kolkata")
    public void runDailyReconciliation() {
        LocalDate settlementDate = LocalDate.now().minusDays(2); // T+2 settlement
        log.info("Starting daily reconciliation for settlement date: {}", settlementDate);

        try {
            // 1. Fetch settlement report from Razorpay (SFTP or API)
            SettlementReport settlement = settlementFetcher.fetch(settlementDate);
            log.info("Fetched settlement report: {}", settlement);

            // 2. Load internal ledger entries for the period
            InternalLedger ledger = ledgerRepository.findForSettlementDate(settlementDate);
            log.info("Loaded internal ledger: {}", ledger);

            // 3. Run reconciliation
            ReconciliationEngine engine = new ReconciliationEngine(ledger, settlement);
            ReconciliationEngine.ReconciliationResult result = engine.reconcile();
            log.info("Reconciliation result: {}", result);

            // 4. Generate report
            String reportPath = reportGenerator.generate(result);
            log.info("Reconciliation report generated: {}", reportPath);

            // 5. Handle discrepancies
            for (ReconciliationEngine.Discrepancy discrepancy : result.getDiscrepancies()) {
                discrepancyHandler.handle(discrepancy);
            }

            // 6. Alert if critical discrepancies
            long criticalCount = result.getDiscrepancies().stream()
                    .filter(d -> d.getSeverity() == ReconciliationEngine.Discrepancy.Severity.CRITICAL)
                    .count();
            if (criticalCount > 0) {
                alertOnCall(criticalCount, settlementDate);
            }

            log.info("Daily reconciliation completed: {}", result);

        } catch (Exception e) {
            log.error("Reconciliation failed for date: {}", settlementDate, e);
            alertOnCall(1, settlementDate); // Treat job failure as critical
            throw new ReconciliationException("Daily reconciliation failed", e);
        }
    }

    /**
     * Manual trigger for re-running reconciliation for a specific date.
     */
    public ReconciliationEngine.ReconciliationResult runForDate(LocalDate settlementDate) throws IOException {
        SettlementReport settlement = settlementFetcher.fetch(settlementDate);
        InternalLedger ledger = ledgerRepository.findForSettlementDate(settlementDate);
        ReconciliationEngine engine = new ReconciliationEngine(ledger, settlement);
        return engine.reconcile();
    }

    private void alertOnCall(long criticalCount, LocalDate date) {
        // PagerDuty / OpsGenie / Slack webhook
        log.error("ALERT: {} critical discrepancies in reconciliation for {}", criticalCount, date);
    }

    // ===== Spring Batch Configuration (alternative to @Scheduled) =====

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step reconciliationStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep)
                .build();
    }

    @Bean
    public Step reconciliationStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    ItemReader<SettlementReport.SettlementEntry> reader,
                                    ItemProcessor<SettlementReport.SettlementEntry, ReconciliationEngine.Discrepancy> processor,
                                    ItemWriter<ReconciliationEngine.Discrepancy> writer) {
        return new StepBuilder("reconciliationStep", jobRepository)
                .<SettlementReport.SettlementEntry, ReconciliationEngine.Discrepancy>chunk(100, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    // ===== Supporting Interfaces =====

    /**
     * Fetches settlement report from Razorpay.
     * Production: SFTP download or Razorpay Settlement API.
     */
    public interface SettlementReportFetcher {
        SettlementReport fetch(LocalDate settlementDate) throws IOException;
    }

    /**
     * Loads internal ledger entries for a settlement date.
     */
    public interface InternalLedgerRepository {
        InternalLedger findForSettlementDate(LocalDate settlementDate);
    }

    /**
     * Handles discrepancies (create tickets, alert, auto-resolve).
     */
    public interface DiscrepancyHandler {
        void handle(ReconciliationEngine.Discrepancy discrepancy);
    }

    /**
     * Generates human-readable reconciliation report.
     */
    public interface ReconciliationReportGenerator {
        String generate(ReconciliationEngine.ReconciliationResult result) throws IOException;
    }

    public static class ReconciliationException extends RuntimeException {
        public ReconciliationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}