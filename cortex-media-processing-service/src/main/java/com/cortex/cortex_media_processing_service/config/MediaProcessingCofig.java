package com.cortex.cortex_media_processing_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class MediaProcessingCofig {

  @Bean(name = "mediaProcessingExecutor")
  public ThreadPoolTaskExecutor mediaProcessingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(5);
    executor.setThreadNamePrefix("media-processing-");
    executor.setRejectedExecutionHandler((runnable, exec) -> {
      try {
        exec.getQueue().put(runnable);
      } catch (InterruptedException e) {
        log.error("InterruptedException in mediaProcessingExecutor", e);
        Thread.currentThread().interrupt();
      }
    });
    executor.initialize();
    executor.getThreadPoolExecutor().prestartAllCoreThreads();
    return executor;
  }
}
