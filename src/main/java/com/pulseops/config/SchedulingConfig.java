package com.pulseops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled task execution.
 * Powers the MonitorScheduler tick loop.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
