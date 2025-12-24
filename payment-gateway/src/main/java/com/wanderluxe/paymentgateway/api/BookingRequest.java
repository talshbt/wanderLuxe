package com.wanderluxe.paymentgateway.api;

/**
 * Simple request payload for booking creation.
 * Uses Java 21 record for concise immutable representation.
 */
public record BookingRequest(
        String userId,
        String offerId,
        Integer quantity
) {
}




