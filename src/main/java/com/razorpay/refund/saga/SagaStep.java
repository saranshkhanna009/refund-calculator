package com.razorpay.refund.saga;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.service.RefundBreakdown;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single step in a refund saga.
 * Each step has a forward action and a compensating action.
 */
public interface SagaStep<T extends SagaContext> {

    /**
     * Unique step name for logging/metrics.
     */
    String getName();

    /**
     * Execute the forward action.
     * @param context saga context with all data
     * @return step result with output data
     */
    StepResult execute(T context) throws SagaStepException;

    /**
     * Compensate (rollback) this step.
     * Called when a later step fails.
     * @param context saga context
     * @param previousResult result from forward execution (for reference)
     */
    void compensate(T context, StepResult previousResult) throws SagaStepException;

    /**
     * Whether this step is idempotent (safe to retry).
     * Idempotent steps don't need compensation on retry.
     */
    default boolean isIdempotent() {
        return false;
    }

    /**
     * Result of a step execution.
     */
    final class StepResult {
        private final boolean success;
        private final String outputRef;      // Reference to created resource (refund ID, ledger ID, etc.)
        private final Map<String, Object> outputData; // Additional data for compensation
        private final LocalDateTime executedAt;

        private StepResult(boolean success, String outputRef, Map<String, Object> outputData) {
            this.success = success;
            this.outputRef = outputRef;
            this.outputData = outputData;
            this.executedAt = LocalDateTime.now();
        }

        public static StepResult success(String outputRef, Map<String, Object> outputData) {
            return new StepResult(true, outputRef, outputData);
        }

        public static StepResult success(String outputRef) {
            return new StepResult(true, outputRef, Map.of());
        }

        public static StepResult failure() {
            return new StepResult(false, null, Map.of());
        }

        public boolean isSuccess() { return success; }
        public String getOutputRef() { return outputRef; }
        public Map<String, Object> getOutputData() { return outputData; }
        public LocalDateTime getExecutedAt() { return executedAt; }
    }
}

/**
 * Exception thrown during saga step execution.
 * Contains info for compensation decision.
 */
class SagaStepException extends Exception {
    private final boolean compensatable;  // Can this step be compensated?
    private final String stepName;

    public SagaStepException(String stepName, String message, boolean compensatable) {
        super(message);
        this.stepName = stepName;
        this.compensatable = compensatable;
    }

    public SagaStepException(String stepName, String message, Throwable cause, boolean compensatable) {
        super(message, cause);
        this.stepName = stepName;
        this.compensatable = compensatable;
    }

    public boolean isCompensatable() { return compensatable; }
    public String getStepName() { return stepName; }

    public static SagaStepException nonCompensatable(String stepName, String message) {
        return new SagaStepException(stepName, message, false);
    }

    public static SagaStepException compensatable(String stepName, String message) {
        return new SagaStepException(stepName, message, true);
    }

    public static SagaStepException compensatable(String stepName, String message, Throwable cause) {
        return new SagaStepException(stepName, message, cause, true);
    }
}