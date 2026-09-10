package com.razorpay.refund.eventsourcing.kafka;

import com.razorpay.refund.eventsourcing.RefundEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Publishes refund events to Kafka.
 * Uses aggregateId as key for partitioning (ensures ordering per refund).
 * Provides exactly-once semantics via idempotent producer.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaProducer<String, RefundEvent> producer;
    private final String topic;

    public KafkaEventPublisher(
            @Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers,
            @Value("${kafka.topic.refund-events:refund-events}") String topic,
            RefundEventSerializer serializer) {

        this.topic = topic;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializer.getClass());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // Exactly-once
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5); // Batch for throughput

        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public void publish(RefundEvent event) {
        String key = event.getAggregateId(); // Partition by refund saga ID
        ProducerRecord<String, RefundEvent> record = new ProducerRecord<>(topic, key, event);

        try {
            Future<org.apache.kafka.clients.producer.RecordMetadata> future = producer.send(record);
            // Fire-and-forget for throughput; use callback for critical events
            log.debug("Published event: type={}, aggregateId={}, topic={}, partition={}",
                    event.getEventType(), event.getAggregateId(), topic, future.get().partition());
        } catch (Exception e) {
            log.error("Failed to publish event: type={}, aggregateId={}", event.getEventType(), event.getAggregateId(), e);
            throw new PublishingException("Kafka publish failed", e);
        }
    }

    @Override
    public void publishBatch(java.util.List<RefundEvent> events) {
        for (RefundEvent event : events) {
            String key = event.getAggregateId();
            ProducerRecord<String, RefundEvent> record = new ProducerRecord<>(topic, key, event);
            producer.send(record);
        }
        producer.flush();
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }

    public static class PublishingException extends RuntimeException {
        public PublishingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}