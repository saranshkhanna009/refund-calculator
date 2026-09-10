package com.razorpay.refund.eventsourcing.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.refund.eventsourcing.RefundEvent;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Kafka serializer for RefundEvent (polymorphic JSON with @JsonTypeInfo).
 */
public class RefundEventSerializer implements Serializer<RefundEvent> {

    private final ObjectMapper objectMapper;

    public RefundEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No config needed
    }

    @Override
    public byte[] serialize(String topic, RefundEvent data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize RefundEvent: " + data.getEventType(), e);
        }
    }

    @Override
    public void close() {
        // Nothing to close
    }

    public static class SerializationException extends RuntimeException {
        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}