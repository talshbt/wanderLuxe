package com.wanderluxe.paymentgateway.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    @PostMapping
    public ResponseEntity<Void> createBooking(@RequestBody BookingRequest request) {
        // Fire-and-forget semantics: just log and immediately acknowledge
        log.info("Received booking request: bookingId={}, propertyId={}, userId={}, amount={} {}, checkIn={}, checkOut={}",
                request.bookingId(), request.propertyId(), request.userId(),
                request.amount(), request.currency(), request.checkInDate(), request.checkOutDate());

        return ResponseEntity.accepted().build();
    }
}




