package com.razorpay.refund.saga;

import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentAllocation;
import com.razorpay.refund.service.RefundBreakdown;
import com.razorpay.refund.service.RefundCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the refund saga: executes steps in order, compensates on failure.
 *
 * Saga State Machine:
 * CREATED → RUNNING → COMPLETED
 *                ↓
 *             COMPENSATING → COMPENSATED / COMPENSATION_FAILED
 *                ↓
 *              FAILED
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final List<SagaStep<SagaContext>> steps;
    private final RefundCalculator calculator;
    private final SagaRepository sagaRepository;

    public SagaOrchestrator(
            List<SagaStep<SagaContext>> steps,
            RefundCalculator calculator,
            SagaRepository sagaRepository) {
        this.steps = List.copyOf(steps); // Immutable, ordered
        this.calculator = calculator;
        this.sagaRepository = sagaRepository;
    }

    /**
     * Execute full refund saga.
     * @return SagaResult with final state and any errors
     */
    public SagaResult executeRefundSaga(
            String orderId,
            String returnId,
            Money orderTotal,
            PaymentAllocation allocation,
            Money returnAmount,
            String idempotencyKey) {

        // 1. Calculate refund breakdown
        RefundBreakdown breakdown = calculator.calculate(orderTotal, allocation, returnAmount);

        // 2. Create saga context
        SagaContext context = SagaContext.builder()
                .orderId(orderId)
                .returnId(returnId)
                .orderTotal(orderTotal)
                .allocation(allocation)
                .returnAmount(returnAmount)
                .breakdown(breakdown)
                .idempotencyKey(idempotencyKey)
                .build();

        // 3. Persist saga start
        SagaState state = SagaState.create(context);
        sagaRepository.save(state);

        log.info("Starting refund saga: sagaId={}, orderId={}, steps={}",
                context.getSagaId(), orderId, steps.size());

        // 4. Execute steps sequentially
        try {
            for (SagaStep<SagaContext> step : steps) {
                executeStep(context, step, state);
            }

            // All steps succeeded
            state.markCompleted();
            sagaRepository.save(state);
            log.info("Saga completed successfully: sagaId={}", context.getSagaId());

            return SagaResult.success(context.getSagaId(), context.getAllStepResults());

        } catch (SagaExecutionException e) {
            // Failure - compensate in reverse order
            log.error("Saga failed at step {}: sagaId={}, error={}",
                    e.getFailedStep(), context.getSagaId(), e.getMessage());

            state.markFailed(e.getFailedStep(), e.getMessage());
            sagaRepository.save(state);

            compensate(context, state, e.getFailedStep());
            return SagaResult.failure(context.getSagaId(), e.getFailedStep(), e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected saga error: sagaId={}", context.getSagaId(), e);
            state.markFailed("UNKNOWN", e.getMessage());
            sagaRepository.save(state);
            return SagaResult.failure(context.getSagaId(), "UNKNOWN", e.getMessage());
        }
    }

    private void executeStep(SagaContext context, SagaStep<SagaContext> step, SagaState state) {
        state.markStepStarted(step.getName());
        sagaRepository.save(state);

        try {
            SagaStep.StepResult result = step.execute(context);
            context.recordStepResult(step.getName(), result);

            state.markStepCompleted(step.getName(), result);
            sagaRepository.save(state);

            log.debug("Step completed: sagaId={}, step={}, success={}",
                    context.getSagaId(), step.getName(), result.isSuccess());

        } catch (SagaStepException e) {
            // Convert to saga execution exception with step info
            throw new SagaExecutionException(step.getName(), e.getMessage(), e);
        }
    }

    private void compensate(SagaContext context, SagaState state, String failedStepName) {
        state.markCompensating();
        sagaRepository.save(state);

        // Find index of failed step
        int failedIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getName().equals(failedStepName)) {
                failedIndex = i;
                break;
            }
        }

        // Compensate in REVERSE order (only completed steps)
        List<String> compensatedSteps = new ArrayList<>();
        List<String> failedCompensations = new ArrayList<>();

        for (int i = failedIndex - 1; i >= 0; i--) {
            SagaStep<SagaContext> step = steps.get(i);
            SagaStep.StepResult result = context.getStepResult(step.getName());

            if (result == null || !result.isSuccess()) {
                log.debug("Skipping compensation for unexecuted/failed step: {}", step.getName());
                continue;
            }


            try {
                log.info("Compensating step: sagaId={}, step={}", context.getSagaId(), step.getName());
                step.compensate(context, result);
                compensatedSteps.add(step.getName());
                state.markStepCompensated(step.getName());
                sagaRepository.save(state);

            } catch (Exception e) {
                log.error("Compensation failed for step {}: sagaId={}", step.getName(), context.getSagaId(), e);
                failedCompensations.add(step.getName() + ": " + e.getMessage());
                // Continue compensating other steps
            }
        }

        if (!failedCompensations.isEmpty()) {
            state.markCompensationFailed(failedCompensations);
            log.error("Saga compensation partially failed: sagaId={}, failed={}",
                    context.getSagaId(), failedCompensations);
        } else {
            state.markCompensated();
            log.info("Saga fully compensated: sagaId={}, compensatedSteps={}",
                    context.getSagaId(), compensatedSteps);
        }

        sagaRepository.save(state);
    }

    // === Result Types ===

    public static final class SagaResult {
        private final boolean success;
        private final String sagaId;
        private final String failedStep;
        private final String errorMessage;
        private final java.util.Map<String, SagaStep.StepResult> stepResults;

        private SagaResult(boolean success, String sagaId, String failedStep, String errorMessage,
                           java.util.Map<String, SagaStep.StepResult> stepResults) {
            this.success = success;
            this.sagaId = sagaId;
            this.failedStep = failedStep;
            this.errorMessage = errorMessage;
            this.stepResults = stepResults;
        }

        public static SagaResult success(String sagaId, java.util.Map<String, SagaStep.StepResult> stepResults) {
            return new SagaResult(true, sagaId, null, null, stepResults);
        }

        public static SagaResult failure(String sagaId, String failedStep, String errorMessage) {
            return new SagaResult(false, sagaId, failedStep, errorMessage, java.util.Map.of());
        }

        public boolean isSuccess() { return success; }
        public String getSagaId() { return sagaId; }
        public String getFailedStep() { return failedStep; }
        public String getErrorMessage() { return errorMessage; }
        public java.util.Map<String, SagaStep.StepResult> getStepResults() { return stepResults; }
    }

    public static final class SagaExecutionException extends RuntimeException {
        private final String failedStep;

        public SagaExecutionException(String failedStep, String message, Throwable cause) {
            super(message, cause);
            this.failedStep = failedStep;
        }

        public String getFailedStep() { return failedStep; }
    }

    // === Saga State Persistence ===

    public static final class SagaState {
        private final String sagaId;
        private final String orderId;
        private final String returnId;
        private SagaStatus status;
        private String currentStep;
        private String failedStep;
        private String errorMessage;
        private final LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private final java.util.Map<String, StepState> stepStates = new java.util.HashMap<>();

        private SagaState(SagaContext context) {
            this.sagaId = context.getSagaId();
            this.orderId = context.getOrderId();
            this.returnId = context.getReturnId();
            this.status = SagaStatus.CREATED;
            this.createdAt = LocalDateTime.now();
            this.updatedAt = LocalDateTime.now();
        }

        public static SagaState create(SagaContext context) {
            return new SagaState(context);
        }

        public void markStepStarted(String stepName) {
            this.status = SagaStatus.RUNNING;
            this.currentStep = stepName;
            this.updatedAt = LocalDateTime.now();
            stepStates.put(stepName, new StepState(stepName, StepStatus.STARTED, LocalDateTime.now(), null));
        }

        public void markStepCompleted(String stepName, SagaStep.StepResult result) {
            this.updatedAt = LocalDateTime.now();
            StepState stepState = stepStates.get(stepName);
            if (stepState != null) {
                stepState.status = result.isSuccess() ? StepStatus.COMPLETED : StepStatus.FAILED;
                stepState.outputRef = result.getOutputRef();
                stepState.completedAt = LocalDateTime.now();
            }
        }

        public void markStepCompensated(String stepName) {
            this.updatedAt = LocalDateTime.now();
            StepState stepState = stepStates.get(stepName);
            if (stepState != null) {
                stepState.status = StepStatus.COMPENSATED;
            }
        }

        public void markCompleted() {
            this.status = SagaStatus.COMPLETED;
            this.updatedAt = LocalDateTime.now();
        }

        public void markFailed(String failedStep, String errorMessage) {
            this.status = SagaStatus.FAILED;
            this.failedStep = failedStep;
            this.errorMessage = errorMessage;
            this.updatedAt = LocalDateTime.now();
        }

        public void markCompensating() {
            this.status = SagaStatus.COMPENSATING;
            this.updatedAt = LocalDateTime.now();
        }

        public void markCompensated() {
            this.status = SagaStatus.COMPENSATED;
            this.updatedAt = LocalDateTime.now();
        }

        public void markCompensationFailed(List<String> failedSteps) {
            this.status = SagaStatus.COMPENSATION_FAILED;
            this.errorMessage = "Compensation failed for: " + String.join(", ", failedSteps);
            this.updatedAt = LocalDateTime.now();
        }

        // Getters
        public String getSagaId() { return sagaId; }
        public String getOrderId() { return orderId; }
        public String getReturnId() { return returnId; }
        public SagaStatus getStatus() { return status; }
        public String getCurrentStep() { return currentStep; }
        public String getFailedStep() { return failedStep; }
        public String getErrorMessage() { return errorMessage; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public java.util.Map<String, StepState> getStepStates() { return stepStates; }

        public enum SagaStatus {
            CREATED, RUNNING, COMPLETED, FAILED, COMPENSATING, COMPENSATED, COMPENSATION_FAILED
        }

        public enum StepStatus {
            STARTED, COMPLETED, FAILED, COMPENSATED
        }

        public static final class StepState {
            private final String stepName;
            private StepStatus status;
            private final LocalDateTime startedAt;
            private LocalDateTime completedAt;
            private String outputRef;

            public StepState(String stepName, StepStatus status, LocalDateTime startedAt, String outputRef) {
                this.stepName = stepName;
                this.status = status;
                this.startedAt = startedAt;
                this.outputRef = outputRef;
            }
        }
    }

    public interface SagaRepository {
        void save(SagaState state);
        java.util.Optional<SagaState> findById(String sagaId);
        java.util.List<SagaState> findByOrderId(String orderId);
    }
}