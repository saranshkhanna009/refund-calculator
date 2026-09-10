package com.razorpay.refund.model;

/**
 * Payment method types. Each has different refund behavior.
 */
public enum PaymentMethod {
    /**
     * Wallet / cashback / store credit — often non-withdrawable, may expire.
     * Usually refunded first in "wallet-first" policies.
     */
    WALLET,

    /**
     * Card, UPI, Net Banking — real money, goes back to source.
     * Subject to gateway fees, GST.
     */
    ONLINE,

    /**
     * Cash on Delivery — refunded as wallet or bank transfer.
     */
    COD,

    /**
     * Gift card / voucher — refunded back to same instrument.
     */
    GIFT_CARD
}