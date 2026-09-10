package com.razorpay.refund.eventsourcing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.reconciliation.ReconciliationEngine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain events for the refund aggregate.
 * All events are immutable, versioned, and contain full context for projections.
 *
 * Event Versioning Strategy:
 * - v1: Initial version
 * - Add new fields as optional (Jackson ignores unknown)
 * - Never remove/rename fields - add new event types instead
 * - Use JsonTypeInfo for polymorphic deserialization
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RefundEvent.RefundInitiated.class, name = "REFUND_INITIATED"),
        @JsonSubTypes.Type(value = RefundEvent.RefundCalculated.class, name = "REFUND_CALCULATED"),
        @JsonSubTypes.Type(value = RefundEvent.GatewayRefundCreated.class, name = "GATEWAY_REFUND_CREATED"),
        @JsonSubTypes.Type(value = RefundEvent.GatewayRefundFailed.class, name = "GATEWAY_REFUND_FAILED"),
        @JsonSubTypes.Type(value = RefundEvent.LedgerEntryCreated.class, name = "LEDGER_ENTRY_CREATED"),
        @JsonSubTypes.Type(value = RefundEvent.LedgerEntryCompensated.class, name = "LEDGER_ENTRY_COMPENSATED"),
        @JsonSubTypes.Type(value = RefundEvent.WalletCredited.class, name = "WALLET_CREDITED"),
        @JsonSubTypes.Type(value = RefundEvent.WalletDebited.class, name = "WALLET_DEBITED"),
        @JsonSubTypes.Type(value = RefundEvent.NotificationSent.class, name = "NOTIFICATION_SENT"),
        @JsonSubTypes.Type(value = RefundEvent.RefundCompleted.class, name = "REFUND_COMPLETED"),
        @JsonSubTypes.Type(value = RefundEvent.RefundFailed.class, name = "REFUND_FAILED"),
        @JsonSubTypes.Type(value = RefundEvent.RefundCompensated.class, name = "REFUND_COMPENSATED"),
        @JsonSubTypes.Type(value = RefundEvent.SettlementMatched.class, name = "SETTLEMENT_MATCHED"),
        @JsonSubTypes.Type(value = RefundEvent.SettlementMismatch.class, name = "SETTLEMENT_MISMATCH")
})
public abstract sealed class RefundEvent permits
        RefundEvent.RefundInitiated, RefundEvent.RefundCalculated, RefundEvent.GatewayRefundCreated,
        RefundEvent.GatewayRefundFailed, RefundEvent.LedgerEntryCreated, RefundEvent.LedgerEntryCompensated,
        RefundEvent.WalletCredited, RefundEvent.WalletDebited, RefundEvent.NotificationSent,
        RefundEvent.RefundCompleted, RefundEvent.RefundFailed, RefundEvent.RefundCompensated,
        RefundEvent.SettlementMatched, RefundEvent.SettlementMismatch {

    private final String eventId;
    private final String aggregateId;      // Refund saga ID
    private final int version;             // Event version for optimistic locking
    private final LocalDateTime timestamp;
    private final String correlationId;    // For tracing across services
    private final Map<String, String> metadata;

    @JsonCreator
    protected RefundEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("aggregateId") String aggregateId,
            @JsonProperty("version") int version,
            @JsonProperty("timestamp") LocalDateTime timestamp,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.eventId = eventId != null ? eventId : UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.version = version;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.correlationId = correlationId;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // Getters
    public String getEventId() { return eventId; }
    public String getAggregateId() { return aggregateId; }
    public int getVersion() { return version; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getCorrelationId() { return correlationId; }
    public Map<String, String> getMetadata() { return metadata; }

    public abstract String getEventType();

    // === Event Factory Methods ===

    public static RefundInitiated initiated(String aggregateId, String orderId, String returnId,
                                             Money orderTotal, Map<PaymentMethod, Money> allocation,
                                             String idempotencyKey, String correlationId) {
        return new RefundInitiated(aggregateId, 1, orderId, returnId, orderTotal, allocation, idempotencyKey, correlationId);
    }

    public static RefundCalculated calculated(String aggregateId, int version, RefundBreakdownData breakdown) {
        return new RefundCalculated(aggregateId, version, breakdown);
    }

    public static GatewayRefundCreated gatewayCreated(String aggregateId, int version,
                                                       String gatewayRefundId, Money amount) {
        return new GatewayRefundCreated(aggregateId, version, gatewayRefundId, amount);
    }

    public static LedgerEntryCreated ledgerCreated(String aggregateId, int version,
                                                    String ledgerId, String gatewayRefundId) {
        return new LedgerEntryCreated(aggregateId, version, ledgerId, gatewayRefundId);
    }

    public static WalletCredited walletCredited(String aggregateId, int version,
                                                 String walletTxnId, Money amount) {
        return new WalletCredited(aggregateId, version, walletTxnId, amount);
    }

    public static RefundCompleted completed(String aggregateId, int version) {
        return new RefundCompleted(aggregateId, version);
    }

    // === Supporting Data Classes ===

    public static final class RefundBreakdownData {
        private final Money totalRefund;
        private final Money walletRefund;
        private final Money onlineRefund;
        private final Money platformFee;
        private final Money gstAmount;
        private final Map<PaymentMethod, Money> byMethod;

        @JsonCreator
        public RefundBreakdownData(
                @JsonProperty("totalRefund") Money totalRefund,
                @JsonProperty("walletRefund") Money walletRefund,
                @JsonProperty("onlineRefund") Money onlineRefund,
                @JsonProperty("platformFee") Money platformFee,
                @JsonProperty("gstAmount") Money gstAmount,
                @JsonProperty("byMethod") Map<PaymentMethod, Money> byMethod) {
            this.totalRefund = totalRefund;
            this.walletRefund = walletRefund;
            this.onlineRefund = onlineRefund;
            this.platformFee = platformFee;
            this.gstAmount = gstAmount;
            this.byMethod = byMethod != null ? Map.copyOf(byMethod) : Map.of();
        }

        // Getters
        public Money getTotalRefund() { return totalRefund; }
        public Money getWalletRefund() { return walletRefund; }
        public Money getOnlineRefund() { return onlineRefund; }
        public Money getPlatformFee() { return platformFee; }
        public Money getGstAmount() { return gstAmount; }
        public Map<PaymentMethod, Money> getByMethod() { return byMethod; }
    }

    // ============================================
    // Concrete Event Implementations
    // ============================================

    public static final class RefundInitiated extends RefundEvent {
        private final String orderId;
        private final String returnId;
        private final Money orderTotal;
        private final Map<PaymentMethod, Money> allocation;
        private final String idempotencyKey;

        public RefundInitiated(String aggregateId, int version, String orderId, String returnId,
                                Money orderTotal, Map<PaymentMethod, Money> allocation,
                                String idempotencyKey, String correlationId) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), correlationId, Map.of());
            this.orderId = orderId;
            this.returnId = returnId;
            this.orderTotal = orderTotal;
            this.allocation = Map.copyOf(allocation);
            this.idempotencyKey = idempotencyKey;
        }

        @JsonCreator
        public RefundInitiated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("orderId") String orderId,
                @JsonProperty("returnId") String returnId,
                @JsonProperty("orderTotal") Money orderTotal,
                @JsonProperty("allocation") Map<PaymentMethod, Money> allocation,
                @JsonProperty("idempotencyKey") String idempotencyKey) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.orderId = orderId;
            this.returnId = returnId;
            this.orderTotal = orderTotal;
            this.allocation = allocation != null ? Map.copyOf(allocation) : Map.of();
            this.idempotencyKey = idempotencyKey;
        }

        @Override public String getEventType() { return "REFUND_INITIATED"; }
        public String getOrderId() { return orderId; }
        public String getReturnId() { return returnId; }
        public Money getOrderTotal() { return orderTotal; }
        public Map<PaymentMethod, Money> getAllocation() { return allocation; }
        public String getIdempotencyKey() { return idempotencyKey; }
    }

    public static final class RefundCalculated extends RefundEvent {
        private final RefundBreakdownData breakdown;

        public RefundCalculated(String aggregateId, int version, RefundBreakdownData breakdown) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.breakdown = breakdown;
        }

        @JsonCreator
        public RefundCalculated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("breakdown") RefundBreakdownData breakdown) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.breakdown = breakdown;
        }

        @Override public String getEventType() { return "REFUND_CALCULATED"; }
        public RefundBreakdownData getBreakdown() { return breakdown; }
    }

    public static final class GatewayRefundCreated extends RefundEvent {
        private final String gatewayRefundId;
        private final Money amount;

        public GatewayRefundCreated(String aggregateId, int version, String gatewayRefundId, Money amount) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.gatewayRefundId = gatewayRefundId;
            this.amount = amount;
        }

        @JsonCreator
        public GatewayRefundCreated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("gatewayRefundId") String gatewayRefundId,
                @JsonProperty("amount") Money amount) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.gatewayRefundId = gatewayRefundId;
            this.amount = amount;
        }

        @Override public String getEventType() { return "GATEWAY_REFUND_CREATED"; }
        public String getGatewayRefundId() { return gatewayRefundId; }
        public Money getAmount() { return amount; }
    }

    public static final class GatewayRefundFailed extends RefundEvent {
        private final String errorMessage;
        private final boolean retryable;

        public GatewayRefundFailed(String aggregateId, int version, String errorMessage, boolean retryable) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.errorMessage = errorMessage;
            this.retryable = retryable;
        }

        @JsonCreator
        public GatewayRefundFailed(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("errorMessage") String errorMessage,
                @JsonProperty("retryable") boolean retryable) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.errorMessage = errorMessage;
            this.retryable = retryable;
        }

        @Override public String getEventType() { return "GATEWAY_REFUND_FAILED"; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isRetryable() { return retryable; }
    }

    public static final class LedgerEntryCreated extends RefundEvent {
        private final String ledgerId;
        private final String gatewayRefundId;

        public LedgerEntryCreated(String aggregateId, int version, String ledgerId, String gatewayRefundId) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.ledgerId = ledgerId;
            this.gatewayRefundId = gatewayRefundId;
        }

        @JsonCreator
        public LedgerEntryCreated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("ledgerId") String ledgerId,
                @JsonProperty("gatewayRefundId") String gatewayRefundId) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.ledgerId = ledgerId;
            this.gatewayRefundId = gatewayRefundId;
        }

        @Override public String getEventType() { return "LEDGER_ENTRY_CREATED"; }
        public String getLedgerId() { return ledgerId; }
        public String getGatewayRefundId() { return gatewayRefundId; }
    }

    public static final class LedgerEntryCompensated extends RefundEvent {
        private final String ledgerId;
        private final String reason;

        public LedgerEntryCompensated(String aggregateId, int version, String ledgerId, String reason) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.ledgerId = ledgerId;
            this.reason = reason;
        }

        @JsonCreator
        public LedgerEntryCompensated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("ledgerId") String ledgerId,
                @JsonProperty("reason") String reason) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.ledgerId = ledgerId;
            this.reason = reason;
        }

        @Override public String getEventType() { return "LEDGER_ENTRY_COMPENSATED"; }
        public String getLedgerId() { return ledgerId; }
        public String getReason() { return reason; }
    }

    public static final class WalletCredited extends RefundEvent {
        private final String walletTxnId;
        private final Money amount;

        public WalletCredited(String aggregateId, int version, String walletTxnId, Money amount) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.walletTxnId = walletTxnId;
            this.amount = amount;
        }

        @JsonCreator
        public WalletCredited(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("walletTxnId") String walletTxnId,
                @JsonProperty("amount") Money amount) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.walletTxnId = walletTxnId;
            this.amount = amount;
        }

        @Override public String getEventType() { return "WALLET_CREDITED"; }
        public String getWalletTxnId() { return walletTxnId; }
        public Money getAmount() { return amount; }
    }

    public static final class WalletDebited extends RefundEvent {
        private final String walletTxnId;
        private final String reason;

        public WalletDebited(String aggregateId, int version, String walletTxnId, String reason) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.walletTxnId = walletTxnId;
            this.reason = reason;
        }

        @JsonCreator
        public WalletDebited(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("walletTxnId") String walletTxnId,
                @JsonProperty("reason") String reason) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.walletTxnId = walletTxnId;
            this.reason = reason;
        }

        @Override public String getEventType() { return "WALLET_DEBITED"; }
        public String getWalletTxnId() { return walletTxnId; }
        public String getReason() { return reason; }
    }

    public static final class NotificationSent extends RefundEvent {
        private final String notificationId;
        private final List<String> channels;

        public NotificationSent(String aggregateId, int version, String notificationId, List<String> channels) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.notificationId = notificationId;
            this.channels = List.copyOf(channels);
        }

        @JsonCreator
        public NotificationSent(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("notificationId") String notificationId,
                @JsonProperty("channels") List<String> channels) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.notificationId = notificationId;
            this.channels = channels != null ? List.copyOf(channels) : List.of();
        }

        @Override public String getEventType() { return "NOTIFICATION_SENT"; }
        public String getNotificationId() { return notificationId; }
        public List<String> getChannels() { return channels; }
    }

    public static final class RefundCompleted extends RefundEvent {
        public RefundCompleted(String aggregateId, int version) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
        }

        @JsonCreator
        public RefundCompleted(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
        }

        @Override public String getEventType() { return "REFUND_COMPLETED"; }
    }

    public static final class RefundFailed extends RefundEvent {
        private final String failedStep;
        private final String errorMessage;

        public RefundFailed(String aggregateId, int version, String failedStep, String errorMessage) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.failedStep = failedStep;
            this.errorMessage = errorMessage;
        }

        @JsonCreator
        public RefundFailed(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("failedStep") String failedStep,
                @JsonProperty("errorMessage") String errorMessage) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.failedStep = failedStep;
            this.errorMessage = errorMessage;
        }

        @Override public String getEventType() { return "REFUND_FAILED"; }
        public String getFailedStep() { return failedStep; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static final class RefundCompensated extends RefundEvent {
        private final List<String> compensatedSteps;

        public RefundCompensated(String aggregateId, int version, List<String> compensatedSteps) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.compensatedSteps = List.copyOf(compensatedSteps);
        }

        @JsonCreator
        public RefundCompensated(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("compensatedSteps") List<String> compensatedSteps) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.compensatedSteps = compensatedSteps != null ? List.copyOf(compensatedSteps) : List.of();
        }

        @Override public String getEventType() { return "REFUND_COMPENSATED"; }
        public List<String> getCompensatedSteps() { return compensatedSteps; }
    }

    public static final class SettlementMatched extends RefundEvent {
        private final String settlementId;
        private final String razorpayRefundId;
        private final Money expectedNet;
        private final Money actualNet;

        public SettlementMatched(String aggregateId, int version, String settlementId, String razorpayRefundId,
                                  Money expectedNet, Money actualNet) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.settlementId = settlementId;
            this.razorpayRefundId = razorpayRefundId;
            this.expectedNet = expectedNet;
            this.actualNet = actualNet;
        }

        @JsonCreator
        public SettlementMatched(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("settlementId") String settlementId,
                @JsonProperty("razorpayRefundId") String razorpayRefundId,
                @JsonProperty("expectedNet") Money expectedNet,
                @JsonProperty("actualNet") Money actualNet) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.settlementId = settlementId;
            this.razorpayRefundId = razorpayRefundId;
            this.expectedNet = expectedNet;
            this.actualNet = actualNet;
        }

        @Override public String getEventType() { return "SETTLEMENT_MATCHED"; }
        public String getSettlementId() { return settlementId; }
        public String getRazorpayRefundId() { return razorpayRefundId; }
        public Money getExpectedNet() { return expectedNet; }
        public Money getActualNet() { return actualNet; }
    }

    public static final class SettlementMismatch extends RefundEvent {
        private final String settlementId;
        private final String razorpayRefundId;
        private final ReconciliationEngine.Discrepancy.Type discrepancyType;
        private final Money expectedNet;
        private final Money actualNet;

        public SettlementMismatch(String aggregateId, int version, String settlementId, String razorpayRefundId,
                                   ReconciliationEngine.Discrepancy.Type discrepancyType,
                                   Money expectedNet, Money actualNet) {
            super(UUID.randomUUID().toString(), aggregateId, version, LocalDateTime.now(), null, Map.of());
            this.settlementId = settlementId;
            this.razorpayRefundId = razorpayRefundId;
            this.discrepancyType = discrepancyType;
            this.expectedNet = expectedNet;
            this.actualNet = actualNet;
        }

        @JsonCreator
        public SettlementMismatch(
                @JsonProperty("eventId") String eventId,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("version") int version,
                @JsonProperty("timestamp") LocalDateTime timestamp,
                @JsonProperty("correlationId") String correlationId,
                @JsonProperty("metadata") Map<String, String> metadata,
                @JsonProperty("settlementId") String settlementId,
                @JsonProperty("razorpayRefundId") String razorpayRefundId,
                @JsonProperty("discrepancyType") ReconciliationEngine.Discrepancy.Type discrepancyType,
                @JsonProperty("expectedNet") Money expectedNet,
                @JsonProperty("actualNet") Money actualNet) {
            super(eventId, aggregateId, version, timestamp, correlationId, metadata);
            this.settlementId = settlementId;
            this.razorpayRefundId = razorpayRefundId;
            this.discrepancyType = discrepancyType;
            this.expectedNet = expectedNet;
            this.actualNet = actualNet;
        }

        @Override public String getEventType() { return "SETTLEMENT_MISMATCH"; }
        public String getSettlementId() { return settlementId; }
        public String getRazorpayRefundId() { return razorpayRefundId; }
        public ReconciliationEngine.Discrepancy.Type getDiscrepancyType() { return discrepancyType; }
        public Money getExpectedNet() { return expectedNet; }
        public Money getActualNet() { return actualNet; }
    }
}