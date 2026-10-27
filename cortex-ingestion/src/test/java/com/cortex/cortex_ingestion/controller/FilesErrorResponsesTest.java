package com.cortex.cortex_ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.exception.ApiExceptionHandler;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;
import com.cortex.cortex_ingestion.service.FilesService;
import com.cortex.cortex_ingestion.service.GcsStorageService;
import com.cortex.cortex_ingestion.service.KafkaProducerService;
import com.cortex.cortex_ingestion.service.PipelineEventsService;
import com.google.cloud.storage.Storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The REST controllers' boundary is ApiExceptionHandler (ADR-0014). Whatever goes wrong below it,
 * the caller gets the right status and an RFC 9457 ProblemDetail body with no exception text, and
 * the failure is logged once, there.
 *
 * <p>Standalone MockMvc, not @WebMvcTest: the application class enables JPA repositories, so a
 * slice would try to build them with no database. The services are the real ones; only their
 * repositories and GCS are fakes. Security isn't under test: the user comes in as the request's
 * principal.
 */
class FilesErrorResponsesTest {

  private static final String USER_ID = "user-1";

  /** Every logger of ours sits under "com.cortex", so this sees all of our lines. */
  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /** Freezes each event as it arrives — logback reads MDC lazily, from whoever asks first. */
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final GcsStorageService gcsStorageService = new GcsStorageService(
      mock(Storage.class), fileMetadataRepository, mock(KafkaProducerService.class));

  private final FilesService filesService = new FilesService(
      mock(PipelineEventRepository.class), mock(MediaChunkRepository.class), fileMetadataRepository,
      gcsStorageService);

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new FilesController(gcsStorageService, filesService, mock(PipelineEventsService.class)))
      .setControllerAdvice(new ApiExceptionHandler())
      .build();

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
  }

  @Test
  void deletingAFileThatIsNotYours_is404_loggedOnceAsAWarning_withTheFileId() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByIdAndUserId(fileId, USER_ID)).thenReturn(Optional.empty());

    mockMvc.perform(asUser(delete("/api/v1/files/{fileId}", fileId)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("File not found"));

    assertThat(linesAt(Level.ERROR)).as("ERROR lines: a missing file is not a server error").isEmpty();
    List<ILoggingEvent> warnings = linesAt(Level.WARN);
    assertThat(warnings).as("WARN lines").hasSize(1);
    assertThat(warnings.get(0).getLoggerName()).as("logged by the boundary")
        .isEqualTo(ApiExceptionHandler.class.getName());
    assertThat(warnings.get(0).getMDCPropertyMap()).containsEntry("fileId", fileId.toString());
  }

  @Test
  void renamingAFileThatIsNotYours_is404() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByIdAndUserId(fileId, USER_ID)).thenReturn(Optional.empty());

    mockMvc.perform(asUser(patch("/api/v1/files/update/{fileId}", fileId))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"displayName\":\"talk.mp4\"}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("File not found"));

    assertThat(linesAt(Level.ERROR)).as("ERROR lines").isEmpty();
    assertThat(linesAt(Level.WARN)).as("WARN lines").hasSize(1);
  }

  @Test
  void renamingToANameWithBadCharacters_is400_withTheReasonInTheDetail() throws Exception {
    // The reason is our own DTO message, so the toast can show it as it is.
    mockMvc.perform(asUser(patch("/api/v1/files/update/{fileId}", UUID.randomUUID()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"displayName\":\"a#b\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Display name contains invalid characters"));
  }

  @Test
  void playbackForARejectedFile_keepsItsOwnStatusAndReason() throws Exception {
    // signPlaybackUrl already throws ResponseStatusExceptions (410, 425, 422). The boundary must
    // keep their status and reason, not turn them into a 500.
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByIdAndUserId(fileId, USER_ID))
        .thenReturn(Optional.of(file(fileId, FileStatusEnum.REJECTED)));

    mockMvc.perform(asUser(get("/api/v1/files/{fileId}/playback-url", fileId)))
        .andExpect(status().isGone())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("This upload was rejected and is no longer available."));
  }

  @Test
  void anUnexpectedFailure_is500_withNoExceptionTextInTheBody_andOneErrorLine() throws Exception {
    when(fileMetadataRepository.findAllByUserId(any()))
        .thenThrow(new IllegalStateException("db password is hunter2"));

    String body = mockMvc.perform(asUser(get("/api/v1/files")))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(500))
        .andReturn().getResponse().getContentAsString();

    assertThat(body).as("the body tells the caller nothing about our internals").doesNotContain("hunter2");

    List<ILoggingEvent> errors = linesAt(Level.ERROR);
    assertThat(errors).as("ERROR lines").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary").isEqualTo(ApiExceptionHandler.class.getName());
    assertThat(error.getThrowableProxy()).as("with the exception attached").isNotNull();
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("db password is hunter2");
  }

  /** The browser's own Accept header, and the user the gateway's token would carry. */
  private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
    return request
        .accept(MediaType.APPLICATION_JSON)
        .principal(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
  }

  private List<ILoggingEvent> linesAt(Level level) {
    return captured.list.stream().filter(logged -> logged.getLevel() == level).toList();
  }

  private static FileMetadata file(UUID fileId, FileStatusEnum status) {
    return FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + fileId + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId(USER_ID)
        .build();
  }
}
