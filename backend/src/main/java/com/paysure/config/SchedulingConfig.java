package com.paysure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled jobs (bank simulator clock, idempotency cleanup). Each job has its own on/off property. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
