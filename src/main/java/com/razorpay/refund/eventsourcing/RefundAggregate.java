package com.razorpay.refund.eventsourcing;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.model.PaymentMethod;
import com.razorpay.refund.service.RefundBreakdown;
import com.razorpay.refund.reconciliation.ReconciliationEngine;
import com.razorpay.refund.eventsourcing.RefundEvent.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Event-sourced aggregate root for a refund.
 * All state changes happen through events - no direct setters.
 * Reconstructs state by replaying events from event store.
 */
public final class RefundAggregate {

    // === State (derived from events) ===
    private String aggregateId;
    private int version = 0;
    private String orderId;
    private String returnId;
    private Money orderTotal;
    private PaymentAllocation allocation;
    private String idempotencyKey;
    private String correlationId;

    private RefundBreakdown breakdown;
    private String gatewayRefundId;
    private String ledgerId;
    private String walletTxnId;
    private List<String> notificationIds = new ArrayList<>();

    private Status status = Status.INITIATED;
    private String failedStep;
    private String errorMessage;
    private List<String> compensatedSteps = new ArrayList<>();

    private final List<RefundEvent> uncommittedEvents = new ArrayList<>();

    // === Factory Methods ===

    public static RefundAggregate initiate(String aggregateId, String orderId, String returnId,
                                            Money orderTotal, PaymentAllocation allocation,
                                            String idempotencyKey, String correlationId) {
        RefundAggregate aggregate = new RefundAggregate();
        aggregate.aggregateId = aggregateId;
        aggregate.orderId = orderId;
        aggregate.returnId = returnId;
        aggregate.orderTotal = orderTotal;
        aggregate.allocation = allocation;
        aggregate.idempotencyKey = idempotencyKey;
        aggregate.correlationId = correlationId;

        RefundEvent event = RefundEvent.initiated(aggregateId, 1, orderId, returnId, orderTotal,
                allocation.getAll(), idempotencyKey, correlationId);
        aggregate.apply(event);
        aggregate.uncommittedEvents.add(event);
        return aggregate;
    }

    public static RefundAggregate fromHistory(String aggregateId, List<RefundEvent> events) {
        RefundAggregate aggregate = new RefundAggregate();
        aggregate.aggregateId = aggregateId;
        for (RefundEvent event : events) {
            aggregate.apply(event);
            aggregate.version = event.getVersion();
        }
        return aggregate;
    }

    // === Command Methods (generate events) ===

    public void calculateRefund(RefundBreakdown breakdown) {
        if (status != Status.INITIATED) {
            throw new IllegalStateException("Cannot calculate: already " + status);
        }

        this.breakdown = breakdown;

        RefundEvent event = RefundEvent.calculated(aggregateId, version + 1,
                new RefundEvent.RefundBreakdownData(
                        breakdown.getTotalCustomerRefund(),
                        breakdown.getWalletRefund(),
                        breakdown.getMerchantOnlineRefund(),
                        breakdown.getPlatformFee(),
                        breakdown.getGstOnOnlinePortion(),
                        breakdown.getRefundByMethod()
                ));
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordGatewayRefund(String gatewayRefundId, Money amount) {
        if (status != Status.CALCULATED) {
            throw new IllegalStateException("Cannot record gateway: status=" + status);
        }

        RefundEvent event = RefundEvent.gatewayCreated(aggregateId, version + 1, gatewayRefundId, amount);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordGatewayFailure(String errorMessage, boolean retryable) {
        RefundEvent event = new GatewayRefundFailed(aggregateId, version + 1, errorMessage, retryable);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordLedgerEntry(String ledgerId, String gatewayRefundId) {
        if (status != Status.GATEWAY_REFUND_CREATED) {
            throw new IllegalStateException("Cannot record ledger: status=" + status);
        }

        RefundEvent event = RefundEvent.ledgerCreated(aggregateId, version + 1, ledgerId, gatewayRefundId);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordWalletCredit(String walletTxnId, Money amount) {
        if (amount == null || amount.isZero()) {
            return; // No wallet portion
        }

        RefundEvent event = RefundEvent.walletCredited(aggregateId, version + 1, walletTxnId, amount);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordNotificationSent(String notificationId, List<String> channels) {
        RefundEvent event = new NotificationSent(aggregateId, version + 1, notificationId, channels);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void markCompleted() {
        if (status == Status.COMPLETED || status == Status.FAILED || status == Status.COMPENSATED) {
            throw new IllegalStateException("Cannot complete: already " + status);
        }

        RefundEvent event = RefundEvent.completed(aggregateId, version + 1);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void markFailed(String failedStep, String errorMessage) {
        this.failedStep = failedStep;
        this.errorMessage = errorMessage;

        RefundEvent event = new RefundFailed(aggregateId, version + 1, failedStep, errorMessage);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void compensate(List<String> compensatedSteps) {
        this.compensatedSteps.addAll(compensatedSteps);

        RefundEvent event = new RefundCompensated(aggregateId, version + 1, compensatedSteps);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordSettlementMatch(String settlementId, String razorpayRefundId,
                                       Money expectedNet, Money actualNet) {
        RefundEvent event = new SettlementMatched(aggregateId, version + 1,
                settlementId, razorpayRefundId, expectedNet, actualNet);
        apply(event);
        uncommittedEvents.add(event);
    }

    public void recordSettlementMismatch(String settlementId, String razorpayRefundId,
                                          ReconciliationEngine.Discrepancy.Type type,
                                          Money expectedNet, Money actualNet) {
        RefundEvent event = new SettlementMismatch(aggregateId, version + 1,
                settlementId, razorpayRefundId, type, expectedNet, actualNet);
        apply(event);
        uncommittedEvents.add(event);
    }

    // === Event Application (state reconstruction) ===

    private void apply(RefundEvent event) {
        this.version = event.getVersion();

        switch (event) {
            case RefundInitiated e -> {
                this.orderId = e.getOrderId();
                this.returnId = e.getReturnId();
                this.orderTotal = e.getOrderTotal();
                this.allocation = new PaymentAllocation(e.getAllocation());
                this.idempotencyKey = e.getIdempotencyKey();
                this.correlationId = e.getCorrelationId();
                this.status = Status.INITIATED;
            }
            case RefundCalculated e -> {
                this.breakdown = reconstructBreakdown(e.getBreakdown());
                this.status = Status.CALCULATED;
            }
            case GatewayRefundCreated e -> {
                this.gatewayRefundId = e.getGatewayRefundId();
                this.status = Status.GATEWAY_REFUND_CREATED;
            }
            case GatewayRefundFailed e -> {
                this.status = Status.FAILED;
                this.errorMessage = e.getErrorMessage();
            }
            case LedgerEntryCreated e -> {
                this.ledgerId = e.getLedgerId();
                this.status = Status.LEDGER_CREATED;
            }
            case LedgerEntryCompensated e -> {
                this.status = Status.COMPENSATED;
            }
            case WalletCredited e -> {
                this.walletTxnId = e.getWalletTxnId();
                this.status = Status.WALLET_CREDITED;
            }
            case WalletDebited e -> {
                this.walletTxnId = null;
                this.status = Status.COMPENSATED;
            }
            case NotificationSent e -> {
                this.notificationIds.add(e.getNotificationId());
            }
            case RefundCompleted e -> {
                this.status = Status.COMPLETED;
            }
            case RefundFailed e -> {
                this.failedStep = e.getFailedStep();
                this.errorMessage = e.getErrorMessage();
                this.status = Status.FAILED;
            }
            case RefundCompensated e -> {
                this.compensatedSteps.addAll(e.getCompensatedSteps());
                this.status = Status.COMPENSATED;
            }
            case SettlementMatched e -> {
                // Settlement matched - could update status
            }
            case SettlementMismatch e -> {
                // Settlement mismatch - could trigger alerts
            }
        }
    }

    private RefundBreakdown reconstructBreakdown(RefundBreakdownData data) {
        return new RefundBreakdown(
                data.getTotalRefund(), data.getTotalRefund(),
                data.getPlatformFee(), Money.zero(), data.getPlatformFee(),
                data.getTotalRefund(),
                data.getByMethod(),
                data.getGstAmount(),
                true
        );
    }

    // === Getters ===
    public String getAggregateId() { return aggregateId; }
    public int getVersion() { return version; }
    public String getOrderId() { return orderId; }
    public String getReturnId() { return returnId; }
    public Money getOrderTotal() { return orderTotal; }
    public PaymentAllocation getAllocation() { return allocation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCorrelationId() { return correlationId; }
    public RefundBreakdown getBreakdown() { return breakdown; }
    public String getGatewayRefundId() { return gatewayRefundId; }
    public String getLedgerId() { return ledgerId; }
    public String getWalletTxnId() { return walletTxnId; }
    public List<String> getNotificationIds() { return List.copyOf(notificationIds); }
    public Status getStatus() { return status; }
    public String getFailedStep() { return failedStep; }
    public String getErrorMessage() { return errorMessage; }
    public List<String> getCompensatedSteps() { return List.copyOf(compensatedSteps); }
    public List<RefundEvent> getUncommittedEvents() { return List.copyOf(uncommittedEvents); }
    public void clearUncommittedEvents() { uncommittedEvents.clear(); }

    public enum Status {
        INITIATED, CALCULATED, GATEWAY_REFUND_CREATED, LEDGER_CREATED,
        WALLET_CREDITED, NOTIFIED, COMPLETED, FAILED, COMPENSATED
    }
}