package com.wanderluxe.bookingprocessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BookingProcessorApplication {

    private static final Logger log = LoggerFactory.getLogger(BookingProcessorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(BookingProcessorApplication.class, args);
    }

    @Bean
    CommandLineRunner bookingProcessorStartupRunner() {
        return args -> log.info("Booking Processor started - waiting for events");
    }
}




