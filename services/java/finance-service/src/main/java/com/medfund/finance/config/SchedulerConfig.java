package com.medfund.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * System clock exposed as a bean so time-sensitive services
     * ({@code RegulatoryDueDateService} and later the scanner cron) can
     * inject a swappable clock for tests.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
