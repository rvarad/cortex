package com.cortex.cortex_media_processing_service.config;

import java.util.concurrent.RejectedExecutionException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MediaProcessingConfig {

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
        Thread.currentThread().interrupt();
        // Thrown, not logged: it fails the listener, so the Kafka handler reports the record, with
        // its fileId. Returning would drop the job without a trace.
        throw new RejectedExecutionException("Interrupted while waiting to queue a media job", e);
      }
    });
    // No task decorator: MDC is carried by BackgroundTask, which every task submitted here is
    // wrapped in at the call site (MediaEventConsumer).
    executor.initialize();
    executor.getThreadPoolExecutor().prestartAllCoreThreads();
    return executor;
  }
}
