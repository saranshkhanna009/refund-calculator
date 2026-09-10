package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.service.RefundBreakdown;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Our internal ledger of refunds processed.
 * This is what we compare against Razorpay settlement reports.
 *
 * In production, this would be a database table with:
 * - refund_id (our internal ID)
 * - razorpay_refund_id (gateway ID)
 * - order_id, payment_id
 * - breakdown (JSON)
 * - status: PENDING, PROCESSED, SETTLED, FAILED
 * - created_at, processed_at, settled_at
 */
public final class InternalLedger {

    private final List<LedgerEntry> entries;

    public InternalLedger(List<LedgerEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<LedgerEntry> getEntries() { return entries; }

    /** Find entry by our internal refund ID */
    public java.util.Optional<LedgerEntry> findByRefundId(String refundId) {
        return entries.stream().filter(e -> e.getRefundId().equals(refundId)).findFirst();
    }

    /** Find entry by Razorpay refund ID (for settlement matching) */
    public java.util.Optional<LedgerEntry> findByRazorpayRefundId(String razorpayRefundId) {
        return entries.stream().filter(e -> e.getRazorpayRefundId().equals(razorpayRefundId)).findFirst();
    }

    /** All entries pending settlement */
    public List<LedgerEntry> getPendingSettlement() {
        return entries.stream()
                .filter(e -> e.getStatus() == Status.PROCESSED || e.getStatus() == Status.PENDING)
                .toList();
    }

    @Override
    public String toString() {
        return "InternalLedger{entries=" + entries.size() + "}";
    }

    /**
     * Single ledger entry for a refund.
     */
    public static final class LedgerEntry {
        private final String refundId;              // Our internal ID
        private final String razorpayRefundId;      // Razorpay's ID (rfnd_xxx)
        private final String orderId;
        private final String paymentId;             // Original payment ID
        private final RefundBreakdown breakdown;    // Full calculation
        private final Status status;
        private final LocalDateTime createdAt;
        private final LocalDateTime processedAt;    // When we called gateway
        private final LocalDateTime settledAt;      // When settlement confirmed
        private final String settledBy;             // Batch job ID that confirmed settlement

        public LedgerEntry(
                String refundId,
                String razorpayRefundId,
                String orderId,
                String paymentId,
                RefundBreakdown breakdown,
                Status status,
                LocalDateTime createdAt,
                LocalDateTime processedAt,
                LocalDateTime settledAt,
                String settledBy) {
            this.refundId = refundId;
            this.razorpayRefundId = razorpayRefundId;
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.breakdown = breakdown;
            this.status = status;
            this.createdAt = createdAt;
            this.processedAt = processedAt;
            this.settledAt = settledAt;
            this.settledBy = settledBy;
        }

        // Getters
        public String getRefundId() { return refundId; }
        public String getRazorpayRefundId() { return razorpayRefundId; }
        public String getOrderId() { return orderId; }
        public String getPaymentId() { return paymentId; }
        public RefundBreakdown getBreakdown() { return breakdown; }
        public Status getStatus() { return status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getProcessedAt() { return processedAt; }
        public LocalDateTime getSettledAt() { return settledAt; }
        public String getSettledBy() { return settledBy; }

        /** Total customer refund amount (for matching with settlement) */
        public Money getTotalRefundAmount() {
            return breakdown.getTotalCustomerRefund();
        }

        /** Online portion (subject to gateway settlement) */
        public Money getOnlineRefundAmount() {
            return breakdown.getMerchantOnlineRefund();
        }

        /** Wallet portion (internal, no gateway settlement) */
        public Money getWalletRefundAmount() {
            return breakdown.getWalletRefund();
        }

        /** Expected net settlement = online refund - gateway fee - GST */
        public Money getExpectedNetSettlement() {
            Money online = getOnlineRefundAmount();
            // In reality, we'd fetch actual fee from Razorpay refund response
            // For now, estimate: 2% fee + 18% GST on fee
            Money fee = online.multiply(0.02);
            Money tax = fee.multiply(0.18);
            return online.subtract(fee).subtract(tax);
        }

        public enum Status {
            PENDING,      // Created, not yet sent to gateway
            PROCESSED,    // Sent to gateway, awaiting settlement
            SETTLED,      // Confirmed in settlement report
            FAILED,       // Gateway rejected
            MANUAL_REVIEW // Discrepancy detected
        }

        @Override
        public String toString() {
            return "LedgerEntry{" +
                    "refundId='" + refundId + '\'' +
                    ", razorpayRefundId='" + razorpayRefundId + '\'' +
                    ", totalRefund=" + getTotalRefundAmount() +
                    ", online=" + getOnlineRefundAmount() +
                    ", status=" + status +
                    '}';
        }
    }
}