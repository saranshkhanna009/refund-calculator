package com.razorpay.refund.eventsourcing.kafka;

import com.razorpay.refund.eventsourcing.RefundEvent;
import com.razorpay.refund.eventsourcing.projection.Projection;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consumes refund events from Kafka and feeds them to projections.
 * Runs in background thread, processes in batches.
 * Supports multiple projection handlers (ledger, analytics, reconciliation, etc.)
 */
@Component
public class RefundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundEventConsumer.class);

    private final KafkaConsumer<String, RefundEvent> consumer;
    private final List<Projection> projections;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    public RefundEventConsumer(
            @Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers,
            @Value("${kafka.topic.refund-events:refund-events}") String topic,
            @Value("${kafka.consumer.group.id:refund-projections}") String groupId,
            RefundEventDeserializer deserializer,
            List<Projection> projections) {

        this.projections = projections;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer.getClass());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit after projection
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(List.of(topic));
    }

    public void start() {
        if (running) return;
        running = true;
        executor.submit(this::pollLoop);
        log.info("Refund event consumer started");
    }

    public void stop() {
        running = false;
        executor.shutdown();
        consumer.close();
        log.info("Refund event consumer stopped");
    }

    private void pollLoop() {
        while (running) {
            try {
                ConsumerRecords<String, RefundEvent> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, RefundEvent> record : records) {
                    RefundEvent event = record.value();
                    if (event == null) continue;

                    try {
                        // Feed event to all projections
                        for (Projection projection : projections) {
                            projection.handle(event);
                        }

                    } catch (Exception e) {
                        log.error("Projection failed for event: type={}, aggregateId={}",
                                event.getEventType(), event.getAggregateId(), e);
                        // Could send to DLQ here
                        throw e; // Stop processing, let retry logic handle
                    }
                }

                // Commit offsets after successful projection
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }

            } catch (Exception e) {
                if (running) {
                    log.error("Error in consumer poll loop", e);
                    // Brief pause before retry
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }
}