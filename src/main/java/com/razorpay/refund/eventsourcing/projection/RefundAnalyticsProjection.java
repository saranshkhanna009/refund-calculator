package com.razorpay.refund.eventsourcing.projection;

import com.razorpay.refund.eventsourcing.RefundEvent;
import com.razorpay.refund.model.Money;
import com.razorpay.refund.model.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Projection: Real-time analytics metrics from refund events.
 * Read model for: dashboards, alerting, business metrics.
 */
@Component
public class RefundAnalyticsProjection implements Projection {

    private static final Logger log = LoggerFactory.getLogger(RefundAnalyticsProjection.class);

    // Daily metrics (key: date)
    private final Map<LocalDate, DailyMetrics> dailyMetrics = new ConcurrentHashMap<>();
    // Merchant-level metrics (key: merchantId - would come from correlationId or
    // metadata)
    private final Map<String, MerchantMetrics> merchantMetrics = new ConcurrentHashMap<>();

    @Override
    public void handle(RefundEvent event) {
        LocalDate date = event.getTimestamp().toLocalDate();

        // Using instanceof checks for compatibility with Java <21
        if (event instanceof RefundEvent.RefundCalculated e) {
            onCalculated(date, e);
        } else if (event instanceof RefundEvent.GatewayRefundCreated e) {
            onGatewayCreated(date, e);
        } else if (event instanceof RefundEvent.RefundCompleted e) {
            onCompleted(date, e);
        } else if (event instanceof RefundEvent.RefundFailed e) {
            onFailed(date, e);
        } else if (event instanceof RefundEvent.RefundCompensated e) {
            onCompensated(date, e);
        } else if (event instanceof RefundEvent.SettlementMismatch e) {
            onSettlementMismatch(date, e);
        }

    }

    private void onCalculated(LocalDate date, RefundEvent.RefundCalculated e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, k -> new DailyMetrics());
        m.refundsInitiated.incrementAndGet();
        m.totalRefundAmount = m.totalRefundAmount.add(e.getBreakdown().getTotalRefund());

        RefundEvent.RefundBreakdownData b = e.getBreakdown();
        if (b.getWalletRefund().isPositive())
            m.walletRefunds.incrementAndGet();
        if (b.getOnlineRefund().isPositive())
            m.onlineRefunds.incrementAndGet();
    }

    private void onGatewayCreated(LocalDate date, RefundEvent.GatewayRefundCreated e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, k -> new DailyMetrics());
        m.gatewayCalls.incrementAndGet();
    }

    private void onCompleted(LocalDate date, RefundEvent.RefundCompleted e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, DailyMetrics::new);
        m.refundsCompleted.incrementAndGet();
    }

    private void onFailed(LocalDate date, RefundEvent.RefundFailed e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, DailyMetrics::new);
        m.refundsFailed.incrementAndGet();
        m.failuresByStep.merge(e.getFailedStep(), 1, Integer::sum);
    }

    private void onCompensated(LocalDate date, RefundEvent.RefundCompensated e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, DailyMetrics::new);
        m.refundsCompensated.incrementAndGet();
        m.compensatedSteps.addAll(e.getCompensatedSteps());
    }

    private void onSettlementMismatch(LocalDate date, RefundEvent.SettlementMismatch e) {
        DailyMetrics m = dailyMetrics.computeIfAbsent(date, DailyMetrics::new);
        m.settlementMismatches.incrementAndGet();
        m.mismatchesByType.merge(e.getDiscrepancyType().name(), 1, Integer::sum);
    }

    @Override
    public String getName() {
        return "RefundAnalyticsProjection";
    }

    @Override
    public void reset() {
        dailyMetrics.clear();
        merchantMetrics.clear();
    }

    // Query methods
    public DailyMetrics getDailyMetrics(LocalDate date) {
        return dailyMetrics.get(date);
    }

    public Map<LocalDate, DailyMetrics> getAllDailyMetrics() {
        return Map.copyOf(dailyMetrics);
    }

    // Metrics data classes
    public static class DailyMetrics {
        public final AtomicLong refundsInitiated = new AtomicLong(0);
        public final AtomicLong refundsCompleted = new AtomicLong(0);
        public final AtomicLong refundsFailed = new AtomicLong(0);
        public final AtomicLong refundsCompensated = new AtomicLong(0);
        public final AtomicLong gatewayCalls = new AtomicLong(0);
        public final AtomicLong walletRefunds = new AtomicLong(0);
        public final AtomicLong onlineRefunds = new AtomicLong(0);
        public final AtomicLong settlementMismatches = new AtomicLong(0);
        public Money totalRefundAmount = Money.zero();
        public final Map<String, Integer> failuresByStep = new ConcurrentHashMap<>();
        public final Map<String, Integer> mismatchesByType = new ConcurrentHashMap<>();
        public final Map<String, Integer> compensatedSteps = new ConcurrentHashMap<>();

        public double getSuccessRate() {
            long total = refundsInitiated.get();
            return total > 0 ? (double) refundsCompleted.get() / total : 1.0;
        }

        public double getFailureRate() {
            long total = refundsInitiated.get();
            return total > 0 ? (double) refundsFailed.get() / total : 0.0;
        }
    }

    public static class MerchantMetrics {
        // Would be populated from event metadata
    }
}