package com.razorpay.refund.eventsourcing.projection;

import com.razorpay.refund.eventsourcing.RefundEvent;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.reconciliation.InternalLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Projection: Builds the internal refund ledger from events.
 * Read model for: reconciliation, audit, customer support.
 */
@Component
public class RefundLedgerProjection implements Projection {

    private static final Logger log = LoggerFactory.getLogger(RefundLedgerProjection.class);

    // In production: write to PostgreSQL/Cassandra
    private final Map<String, LedgerEntry> ledger = new ConcurrentHashMap<>();

    @Override
    public void handle(RefundEvent event) {
        switch (event) {
            case RefundEvent.RefundCalculated e -> handleCalculated(e);
            case RefundEvent.GatewayRefundCreated e -> handleGatewayCreated(e);
            case RefundEvent.LedgerEntryCreated e -> handleLedgerCreated(e);
            case RefundEvent.LedgerEntryCompensated e -> handleLedgerCompensated(e);
            case RefundEvent.WalletCredited e -> handleWalletCredited(e);
            case RefundEvent.WalletDebited e -> handleWalletDebited(e);
            case RefundEvent.RefundCompleted e -> handleCompleted(e);
            case RefundEvent.RefundFailed e -> handleFailed(e);
            case RefundEvent.RefundCompensated e -> handleCompensated(e);
            case RefundEvent.SettlementMatched e -> handleSettlementMatched(e);
            case RefundEvent.SettlementMismatch e -> handleSettlementMismatch(e);
        }
    }

    private void handleCalculated(RefundEvent.RefundCalculated e) {
        RefundEvent.RefundBreakdownData b = e.getBreakdown();
        ledger.put(e.getAggregateId(), new LedgerEntry(
                e.getAggregateId(),
                null, // gatewayRefundId not yet known
                b.getTotalRefund(),
                b.getWalletRefund(),
                b.getOnlineRefund(),
                b.getPlatformFee(),
                b.getGstAmount(),
                InternalLedger.LedgerEntry.Status.PENDING
        ));
        log.debug("Ledger projection: Calculated refund for {}", e.getAggregateId());
    }

    private void handleGatewayCreated(RefundEvent.GatewayRefundCreated e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withGatewayRefundId(e.getGatewayRefundId()));
        log.debug("Ledger projection: Gateway refund created for {}", e.getAggregateId());
    }

    private void handleLedgerCreated(RefundEvent.LedgerEntryCreated e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withLedgerId(e.getLedgerId())
                .withStatus(InternalLedger.LedgerEntry.Status.PROCESSED));
        log.debug("Ledger projection: Ledger entry created for {}", e.getAggregateId());
    }

    private void handleLedgerCompensated(RefundEvent.LedgerEntryCompensated e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withStatus(InternalLedger.LedgerEntry.Status.COMPENSATED));
        log.debug("Ledger projection: Ledger compensated for {}", e.getAggregateId());
    }

    private void handleWalletCredited(RefundEvent.WalletCredited e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withWalletTxnId(e.getWalletTxnId()));
    }

    private void handleWalletDebited(RefundEvent.WalletDebited e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withWalletTxnId(null));
    }

    private void handleCompleted(RefundEvent.RefundCompleted e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withStatus(InternalLedger.LedgerEntry.Status.SETTLED));
    }

    private void handleFailed(RefundEvent.RefundFailed e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withStatus(InternalLedger.LedgerEntry.Status.FAILED)
                .withErrorMessage(e.getErrorMessage()));
    }

    private void handleCompensated(RefundEvent.RefundCompensated e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withStatus(InternalLedger.LedgerEntry.Status.COMPENSATED));
    }

    private void handleSettlementMatched(RefundEvent.SettlementMatched e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withSettlementMatched(true));
    }

    private void handleSettlementMismatch(RefundEvent.SettlementMismatch e) {
        ledger.computeIfPresent(e.getAggregateId(), (k, v) -> v.withSettlementMatched(false)
                .withDiscrepancy(e.getDiscrepancyType().name()));
    }

    @Override
    public String getName() {
        return "RefundLedgerProjection";
    }

    @Override
    public void reset() {
        ledger.clear();
    }

    // Query methods for read model
    public LedgerEntry getEntry(String aggregateId) {
        return ledger.get(aggregateId);
    }

    public java.util.Collection<LedgerEntry> getAllEntries() {
        return ledger.values();
    }

    public record LedgerEntry(
            String aggregateId,
            String gatewayRefundId,
            String ledgerId,
            String walletTxnId,
            Money totalRefund,
            Money walletRefund,
            Money onlineRefund,
            Money platformFee,
            Money gstAmount,
            InternalLedger.LedgerEntry.Status status,
            boolean settlementMatched,
            String discrepancyType,
            String errorMessage
    ) {
        public LedgerEntry(String aggregateId, String gatewayRefundId, Money totalRefund, Money walletRefund,
                           Money onlineRefund, Money platformFee, Money gstAmount, InternalLedger.LedgerEntry.Status status) {
            this(aggregateId, gatewayRefundId, null, null, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, false, null, null);
        }

        public LedgerEntry withGatewayRefundId(String id) { return new LedgerEntry(aggregateId, id, ledgerId, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, settlementMatched, discrepancyType, errorMessage); }
        public LedgerEntry withLedgerId(String id) { return new LedgerEntry(aggregateId, gatewayRefundId, id, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, settlementMatched, discrepancyType, errorMessage); }
        public LedgerEntry withWalletTxnId(String id) { return new LedgerEntry(aggregateId, gatewayRefundId, ledgerId, id, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, settlementMatched, discrepancyType, errorMessage); }
        public LedgerEntry withStatus(InternalLedger.LedgerEntry.Status s) { return new LedgerEntry(aggregateId, gatewayRefundId, ledgerId, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, s, settlementMatched, discrepancyType, errorMessage); }
        public LedgerEntry withSettlementMatched(boolean matched) { return new LedgerEntry(aggregateId, gatewayRefundId, ledgerId, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, matched, discrepancyType, errorMessage); }
        public LedgerEntry withDiscrepancy(String type) { return new LedgerEntry(aggregateId, gatewayRefundId, ledgerId, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, settlementMatched, type, errorMessage); }
        public LedgerEntry withErrorMessage(String msg) { return new LedgerEntry(aggregateId, gatewayRefundId, ledgerId, walletTxnId, totalRefund, walletRefund, onlineRefund, platformFee, gstAmount, status, settlementMatched, discrepancyType, msg); }
    }
}