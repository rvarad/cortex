package com.cortex.cortex_ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.model.UploadRejectReasonEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.model.PipelineEvent;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;
import com.cortex.cortex_ingestion.service.FilesService;
import com.cortex.cortex_ingestion.service.GcsStorageService;
import com.cortex.cortex_ingestion.service.PipelineEventsService;
import com.cortex.cortex_ingestion.service.SseEmitterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A terminal file's page always learns how its run ended (failed-states, brick 8). Its terminal
 * event is a glass box send and can be lost while the row's status is written; then the stream
 * sends one, built from the row, before it closes. Without it the page shows "connection lost".
 *
 * <p>Standalone MockMvc, as in FilesErrorResponsesTest, with the real PipelineEventsService. The
 * stream of a terminal file is completed before the handler returns, so its events are the body.
 */
class PipelineEventsStreamTest {

  private static final String USER_ID = "user-1";

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final PipelineEventRepository pipelineEventRepository = mock(PipelineEventRepository.class);

  private final PipelineEventsService pipelineEventsService = new PipelineEventsService(
      mock(SseEmitterRegistry.class), pipelineEventRepository, fileMetadataRepository,
      mock(MediaChunkRepository.class), new ObjectMapper());

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new FilesController(mock(GcsStorageService.class), mock(FilesService.class),
          pipelineEventsService))
      .build();

  private final UUID fileId = UUID.randomUUID();

  @Test
  void aFailedFileWithNoPipelineFailed_getsOneFromTheRow() throws Exception {
    givenTheFile(FileStatusEnum.FOR_CLEANUP, null);
    givenTheHistory(PipelineEventEnum.PIPELINE_STARTED);

    String body = stream();

    assertThat(body).contains("event:PIPELINE_STARTED", "event:PIPELINE_FAILED",
        PipelineEventEnum.FAILED_BY_ERROR_MESSAGE);
    assertThat(body.indexOf("event:PIPELINE_FAILED")).as("after the history")
        .isGreaterThan(body.indexOf("event:PIPELINE_STARTED"));
    verify(pipelineEventRepository, never()).save(any());
  }

  @Test
  void aRejectedFileWithNoUploadRejected_getsOneForItsReason() throws Exception {
    givenTheFile(FileStatusEnum.REJECTED, UploadRejectReasonEnum.TOO_LONG);
    givenTheHistory();

    String body = stream();

    assertThat(body).contains("event:UPLOAD_REJECTED", "This file is too long to be processed.");
    verify(pipelineEventRepository, never()).save(any());
  }

  @Test
  void aFailedFileWithItsPipelineFailed_getsItOnce() throws Exception {
    givenTheFile(FileStatusEnum.FOR_CLEANUP, null);
    givenTheHistory(PipelineEventEnum.PIPELINE_STARTED, PipelineEventEnum.PIPELINE_FAILED);

    String body = stream();

    assertThat(body.split("event:PIPELINE_FAILED", -1)).as("PIPELINE_FAILED events, plus one").hasSize(2);
  }

  private void givenTheFile(FileStatusEnum status, UploadRejectReasonEnum reason) {
    FileMetadata file = new FileMetadata(); // the builder wants every non-null column; the stream reads only these
    file.setId(fileId);
    file.setFileStatus(status);
    file.setRejectionReason(reason);
    when(fileMetadataRepository.findByIdAndUserId(fileId, USER_ID)).thenReturn(Optional.of(file));
  }

  private void givenTheHistory(PipelineEventEnum... types) {
    when(pipelineEventRepository.findByFileIdOrderByCreatedAtAsc(fileId)).thenReturn(List.of(types).stream()
        .map(type -> PipelineEvent.builder().fileId(fileId).eventType(type).message(type.name()).build())
        .toList());
  }

  private String stream() throws Exception {
    return mockMvc.perform(get("/api/v1/files/{fileId}/events", fileId)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .principal(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of())))
        .andReturn().getResponse().getContentAsString();
  }
}
