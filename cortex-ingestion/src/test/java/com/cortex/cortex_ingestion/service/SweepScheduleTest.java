package com.cortex.cortex_ingestion.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

/**
 * The sweep's first run waits the processing silence after startup, so events that waited in Kafka
 * while ingestion was down are stored before anything is judged (ADR-0016). Real
 * scheduling, with the silences set to seconds.
 */
@SpringJUnitConfig(SweepScheduleTest.Config.class)
@TestPropertySource(properties = {
    "cortex.sweep.processing-silence=PT2S",
    "cortex.sweep.interval=PT0.2S" })
class SweepScheduleTest {

  @Configuration
  @EnableScheduling
  static class Config {

    @Bean
    PipelineEventRepository pipelineEventRepository() {
      return mock(PipelineEventRepository.class);
    }

    @Bean
    Sweep sweep(PipelineEventRepository pipelineEventRepository) {
      return new Sweep(pipelineEventRepository, mock(PipelineEventsService.class),
          mock(FileMetadataRepository.class), mock(GcsStorageService.class), mock(FilesService.class),
          Duration.ofHours(2), Duration.ofSeconds(2), Duration.ofHours(2));
    }
  }

  @Autowired
  PipelineEventRepository pipelineEventRepository;

  @Test
  void nothingIsJudgedBeforeTheFirstRunDelay_thenTheSweepRuns() {
    verify(pipelineEventRepository, after(1_500).never()).findSilentFiles(any(), any());
    verify(pipelineEventRepository, timeout(3_000).atLeastOnce()).findSilentFiles(any(), any());
  }
}
