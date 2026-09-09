package com.hoangluongtran0309.dbbackup;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Composition root. This module is the only one that sees both the use cases
 * and the adapters that satisfy their ports; component scanning from this
 * package is what wires the two together.
 */
@SpringBootApplication
public class DbBackupApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbBackupApplication.class, args);
    }

    /**
     * Injected rather than called statically so that use cases can be tested
     * against a fixed instant.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
