package com.autoreg.workflow;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("worker")
@EnableScheduling
public class SchedulingConfig {
}
