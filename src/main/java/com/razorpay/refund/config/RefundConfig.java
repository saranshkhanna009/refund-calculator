package com.razorpay.refund.config;

import com.razorpay.refund.idempotency.IdempotencyStore;
import com.razorpay.refund.idempotency.InMemoryIdempotencyStore;
import com.razorpay.refund.idempotency.RedisIdempotencyStore;
import com.razorpay.refund.policy.RefundPolicy;
import com.razorpay.refund.policy.StandardRefundPolicy;
import com.razorpay.refund.service.IdempotentRefundService;
import com.razorpay.refund.service.MockRefundExecutor;
import com.razorpay.refund.service.RazorpayRefundExecutor;
import com.razorpay.refund.service.RefundCalculator;
import com.razorpay.refund.service.RefundExecutor;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Spring configuration for the refund system.
 *
 * Profiles:
 * - "test" / default: In-memory store, mock executor
 * - "prod": Redis/Redisson store, real Razorpay executor
 */
@Configuration
public class RefundConfig {

    @Bean
    public RefundPolicy refundPolicy() {
        return new StandardRefundPolicy();
    }

    @Bean
    public RefundCalculator refundCalculator(RefundPolicy policy) {
        return new RefundCalculator(policy);
    }

    // ===== Test / Local Profile =====

    @Bean
    @Profile({"test", "local", "default"})
    public IdempotencyStore testIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @Profile({"test", "local", "default"})
    public RefundExecutor testRefundExecutor() {
        return new MockRefundExecutor();
    }

    // ===== Production Profile =====

    @Bean
    @Profile("prod")
    public RedissonClient redissonClient(
            @Value("${spring.redis.host:localhost}") String host,
            @Value("${spring.redis.port:6379}") int port,
            @Value("${spring.redis.password:}") String password) {

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                .setConnectionPoolSize(20)
                .setConnectionMinimumIdleSize(5);

        return org.redisson.Redisson.create(config);
    }

    @Bean
    @Profile("prod")
    public IdempotencyStore prodIdempotencyStore(RedissonClient redisson) {
        return new RedisIdempotencyStore(redisson);
    }

    @Bean
    @Profile("prod")
    public RefundExecutor prodRefundExecutor() {
        // Return real implementation that calls Razorpay API
        return new RazorpayRefundExecutor();
    }

    // ===== Common =====

    @Bean
    public IdempotentRefundService idempotentRefundService(
            RefundCalculator calculator,
            IdempotencyStore idempotencyStore,
            RefundExecutor refundExecutor) {
        return new IdempotentRefundService(calculator, idempotencyStore, refundExecutor);
    }
}