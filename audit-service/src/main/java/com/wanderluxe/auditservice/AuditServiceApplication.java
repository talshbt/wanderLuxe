package com.wanderluxe.auditservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AuditServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner auditServiceStartupRunner() {
        return args -> log.info("Audit Service started - ready for compliance logging");
    }
}




