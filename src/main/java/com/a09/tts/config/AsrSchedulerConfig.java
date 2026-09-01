package com.a09.tts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AsrSchedulerConfig {
    @Bean
    public ThreadPoolTaskScheduler asrWebSocketScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("asr-ws-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}