package com.razorpay.refund.eventsourcing.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.refund.eventsourcing.RefundEvent;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

/**
 * Kafka deserializer for RefundEvent.
 */
public class RefundEventDeserializer implements Deserializer<RefundEvent> {

    private final ObjectMapper objectMapper;

    public RefundEventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public RefundEvent deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(data, RefundEvent.class);
        } catch (Exception e) {
            throw new DeserializationException("Failed to deserialize RefundEvent from topic: " + topic, e);
        }
    }

    @Override
    public void close() {
    }

    public static class DeserializationException extends RuntimeException {
        public DeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}