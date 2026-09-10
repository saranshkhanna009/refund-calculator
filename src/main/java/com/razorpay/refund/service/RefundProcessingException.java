package com.razorpay.refund.service;

/**
 * Exception thrown when refund processing fails (calculation + execution).
 * Wraps underlying cause for proper error handling upstream.
 */
public class RefundProcessingException extends RuntimeException {

    private final String orderId;
    private final String returnId;

    public RefundProcessingException(String message) {
        this(message, null, null, null);
    }

    public RefundProcessingException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public RefundProcessingException(String message, String orderId, String returnId, Throwable cause) {
        super(message, cause);
        this.orderId = orderId;
        this.returnId = returnId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getReturnId() {
        return returnId;
    }
}