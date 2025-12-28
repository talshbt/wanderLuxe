package com.wanderluxe.paymentgateway.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Simple request payload for booking creation.
 * Uses Java 21 record for concise immutable representation.
 */
public record BookingRequest(
        String bookingId,
        String propertyId,
        String userId,
        BigDecimal amount,
        String currency,
        LocalDate checkInDate,
        LocalDate checkOutDate
) {
}




