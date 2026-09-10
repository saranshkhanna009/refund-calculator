package com.razorpay.refund.eventsourcing.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.razorpay.refund.eventsourcing.RefundEvent;
import com.razorpay.refund.eventsourcing.projection.Projection;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Properties;

/**
 * Kafka configuration for event sourcing.
 * Provides producer, consumer, serializers, and projection wiring.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ObjectMapper eventObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Polymorphic type handling is via @JsonTypeInfo on RefundEvent
        return mapper;
    }

    @Bean
    public RefundEventSerializer refundEventSerializer(ObjectMapper objectMapper) {
        return new RefundEventSerializer(objectMapper);
    }

    @Bean
    public RefundEventDeserializer refundEventDeserializer(ObjectMapper objectMapper) {
        return new RefundEventDeserializer(objectMapper);
    }

    @Bean
    @Primary
    public KafkaProducer<String, RefundEvent> kafkaProducer(
            @Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers,
            RefundEventSerializer serializer) {

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", serializer.getClass().getName());
        props.put("acks", "all");
        props.put("enable.idempotence", true);
        props.put("retries", Integer.MAX_VALUE);
        props.put("retry.backoff.ms", 100);
        props.put("compression.type", "snappy");
        props.put("linger.ms", 5);

        return new KafkaProducer<>(props);
    }

    @Bean
    public KafkaConsumer<String, RefundEvent> kafkaConsumer(
            @Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers,
            @Value("${kafka.consumer.group.id:refund-projections}") String groupId,
            @Value("${kafka.topic.refund-events:refund-events}") String topic,
            RefundEventDeserializer deserializer) {

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", deserializer.getClass().getName());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", false);
        props.put("max.poll.records", 100);
        props.put("max.poll.interval.ms", 300000);

        KafkaConsumer<String, RefundEvent> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    @Bean
    public KafkaEventPublisher kafkaEventPublisher(KafkaProducer<String, RefundEvent> producer,
                                                    @Value("${kafka.topic.refund-events:refund-events}") String topic) {
        return new KafkaEventPublisher(producer, topic);
    }

    @Bean
    public RefundEventConsumer refundEventConsumer(
            KafkaConsumer<String, RefundEvent> consumer,
            List<Projection> projections) {
        return new RefundEventConsumer(consumer, projections);
    }

    }