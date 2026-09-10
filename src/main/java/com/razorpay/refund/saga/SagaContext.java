package com.razorpay.refund.saga;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.service.RefundBreakdown;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable context passed through all saga steps.
 * Contains all data needed for forward execution and compensation.
 */
public final class SagaContext {

    // === Input Data (set at saga start) ===
    private final String sagaId;
    private final String orderId;
    private final String returnId;
    private final Money orderTotal;
    private final PaymentAllocation allocation;
    private final Money returnAmount;
    private final RefundBreakdown breakdown;
    private final String idempotencyKey;

    // === Step Outputs (populated during execution) ===
    private final Map<String, SagaStep.StepResult> stepResults = new HashMap<>();

    // === Metadata ===
    private final LocalDateTime createdAt;
    private int currentStepIndex = 0;

    private SagaContext(Builder builder) {
        this.sagaId = builder.sagaId != null ? builder.sagaId : UUID.randomUUID().toString();
        this.orderId = builder.orderId;
        this.returnId = builder.returnId;
        this.orderTotal = builder.orderTotal;
        this.allocation = builder.allocation;
        this.returnAmount = builder.returnAmount;
        this.breakdown = builder.breakdown;
        this.idempotencyKey = builder.idempotencyKey;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
    }

    // === Getters for Input Data ===
    public String getSagaId() { return sagaId; }
    public String getOrderId() { return orderId; }
    public String getReturnId() { return returnId; }
    public Money getOrderTotal() { return orderTotal; }
    public PaymentAllocation getAllocation() { return allocation; }
    public Money getReturnAmount() { return returnAmount; }
    public RefundBreakdown getBreakdown() { return breakdown; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // === Step Result Management ===
    public void recordStepResult(String stepName, SagaStep.StepResult result) {
        stepResults.put(stepName, result);
        currentStepIndex++;
    }

    public SagaStep.StepResult getStepResult(String stepName) {
        return stepResults.get(stepName);
    }

    public Map<String, SagaStep.StepResult> getAllStepResults() {
        return Map.copyOf(stepResults);
    }

    public int getCurrentStepIndex() { return currentStepIndex; }

    // === Builder ===
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sagaId;
        private String orderId;
        private String returnId;
        private Money orderTotal;
        private PaymentAllocation allocation;
        private Money returnAmount;
        private RefundBreakdown breakdown;
        private String idempotencyKey;
        private LocalDateTime createdAt;

        public Builder sagaId(String sagaId) { this.sagaId = sagaId; return this; }
        public Builder orderId(String orderId) { this.orderId = orderId; return this; }
        public Builder returnId(String returnId) { this.returnId = returnId; return this; }
        public Builder orderTotal(Money orderTotal) { this.orderTotal = orderTotal; return this; }
        public Builder allocation(PaymentAllocation allocation) { this.allocation = allocation; return this; }
        public Builder returnAmount(Money returnAmount) { this.returnAmount = returnAmount; return this; }
        public Builder breakdown(RefundBreakdown breakdown) { this.breakdown = breakdown; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public SagaContext build() {
            if (orderId == null) throw new IllegalStateException("orderId required");
            if (returnId == null) throw new IllegalStateException("returnId required");
            if (orderTotal == null) throw new IllegalStateException("orderTotal required");
            if (allocation == null) throw new IllegalStateException("allocation required");
            if (returnAmount == null) throw new IllegalStateException("returnAmount required");
            if (breakdown == null) throw new IllegalStateException("breakdown required");
            if (idempotencyKey == null) throw new IllegalStateException("idempotencyKey required");
            return new SagaContext(this);
        }
    }
}