package com.razorpay.refund.reconciliation;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;

import java.time.LocalDate;
import java.util.List;

/**
 * Razorpay Settlement Report model.
 *
 * Razorpay settles funds T+2/T+3 (2-3 business days after payment).
 * Settlement report contains all transactions (payments, refunds, transfers) for a settlement batch.
 *
 * Key fields from Razorpay settlement report:
 * - settlement_id: Unique settlement batch ID (e.g., "setl_123")
 * - settlement_date: Date funds hit bank account
 * - payment_id: Original payment ID
 * - refund_id: Refund ID (if refund)
 * - type: "payment", "refund", "transfer", "adjustment"
 * - amount: Gross amount in paise
 * - fee: Razorpay fee in paise
 * - tax: GST on fee in paise
 * - net_amount: Amount actually settled (amount - fee - tax)
 * - status: "processed", "pending", "failed"
 * - utr: Unique Transaction Reference for bank traceability
 */
public final class SettlementReport {

    private final String settlementId;
    private final LocalDate settlementDate;
    private final List<SettlementEntry> entries;

    public SettlementReport(String settlementId, LocalDate settlementDate, List<SettlementEntry> entries) {
        this.settlementId = settlementId;
        this.settlementDate = settlementDate;
        this.entries = List.copyOf(entries);
    }

    public String getSettlementId() { return settlementId; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public List<SettlementEntry> getEntries() { return entries; }

    /** Total net amount for this settlement */
    public Money getTotalNetAmount() {
        return entries.stream()
                .map(SettlementEntry::getNetAmount)
                .reduce(Money.zero(), Money::add);
    }

    /** Filter entries by type */
    public List<SettlementEntry> getByType(SettlementType type) {
        return entries.stream()
                .filter(e -> e.getType() == type)
                .toList();
    }

    /** Filter refunds only */
    public List<SettlementEntry> getRefunds() {
        return getByType(SettlementType.REFUND);
    }

    /** Filter payments only */
    public List<SettlementEntry> getPayments() {
        return getByType(SettlementType.PAYMENT);
    }

    @Override
    public String toString() {
        return "SettlementReport{" +
                "settlementId='" + settlementId + '\'' +
                ", settlementDate=" + settlementDate +
                ", entryCount=" + entries.size() +
                ", totalNet=" + getTotalNetAmount() +
                '}';
    }

    /**
     * Single line in settlement report.
     */
    public static final class SettlementEntry {
        private final String paymentId;
        private final String refundId;          // Null for payments
        private final SettlementType type;
        private final Money grossAmount;        // In paise
        private final Money fee;                // Razorpay MDR
        private final Money tax;                // GST on fee
        private final Money netAmount;          // What actually settles
        private final String status;
        private final String utr;               // Bank reference
        private final LocalDate transactionDate; // Original payment/refund date
        private final PaymentMethod method;     // Card, UPI, Netbanking, Wallet

        public SettlementEntry(
                String paymentId,
                String refundId,
                SettlementType type,
                Money grossAmount,
                Money fee,
                Money tax,
                Money netAmount,
                String status,
                String utr,
                LocalDate transactionDate,
                PaymentMethod method) {
            this.paymentId = paymentId;
            this.refundId = refundId;
            this.type = type;
            this.grossAmount = grossAmount;
            this.fee = fee;
            this.tax = tax;
            this.netAmount = netAmount;
            this.status = status;
            this.utr = utr;
            this.transactionDate = transactionDate;
            this.method = method;
        }

        // Getters
        public String getPaymentId() { return paymentId; }
        public String getRefundId() { return refundId; }
        public SettlementType getType() { return type; }
        public Money getGrossAmount() { return grossAmount; }
        public Money getFee() { return fee; }
        public Money getTax() { return tax; }
        public Money getNetAmount() { return netAmount; }
        public String getStatus() { return status; }
        public String getUtr() { return utr; }
        public LocalDate getTransactionDate() { return transactionDate; }
        public PaymentMethod getMethod() { return method; }

        /** Unique key for matching: paymentId + refundId */
        public String getMatchKey() {
            return refundId != null ? refundId : paymentId;
        }

        @Override
        public String toString() {
            return "SettlementEntry{" +
                    "paymentId='" + paymentId + '\'' +
                    ", refundId='" + refundId + '\'' +
                    ", type=" + type +
                    ", gross=" + grossAmount +
                    ", net=" + netAmount +
                    ", status='" + status + '\'' +
                    '}';
        }
    }

    public enum SettlementType {
        PAYMENT,
        REFUND,
        TRANSFER,      // Payout to vendor/linked account
        ADJUSTMENT,    // Chargeback, fee correction, etc.
        FEE            // Standalone fee entry
    }
}