package com.cortex.cortex_ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.exception.ApiExceptionHandler;
import com.cortex.cortex_ingestion.logging.MdcCleanupFilter;
import com.cortex.cortex_ingestion.service.GcsStorageService;
import com.cortex.cortex_ingestion.service.KafkaProducerService;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The webhook's answer is an instruction to Pub/Sub (ADR-0014): a 2xx means "done", anything else
 * means "send it again", for days. A notification that can never be processed is answered 2xx and
 * logged once as an error. A failure that may pass (GCS, the database, Kafka) is a 5xx, logged once
 * by the boundary with the fileId.
 *
 * <p>Standalone MockMvc, for the same reason as FilesErrorResponsesTest. The service is the real
 * one; GCS, the repository and Kafka are fakes.
 */
class WebhookResponsesTest {

  private static final String OBJECT_NAME = "uploads/media/talk.mp4";

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

  private final Storage storage = mock(Storage.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final KafkaProducerService kafkaProducerService = mock(KafkaProducerService.class);

  private final GcsStorageService gcsStorageService = new GcsStorageService(
      storage, fileMetadataRepository, kafkaProducerService);

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new WebhookController(gcsStorageService))
      .setControllerAdvice(new ApiExceptionHandler())
      .addFilters(new MdcCleanupFilter())
      .build();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(gcsStorageService, "bucketName", "cortex-dev-bucket");
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
    MDC.clear();
  }

  @Test
  void anObjectWithNoFile_isDone_andLoggedOnce() throws Exception {
    when(fileMetadataRepository.findByObjectName(OBJECT_NAME)).thenReturn(Optional.empty());

    mockMvc.perform(pushFor(OBJECT_NAME)).andExpect(status().isOk());

    assertThat(linesAt(Level.ERROR)).as("ERROR lines").hasSize(1);
    verifyNoInteractions(kafkaProducerService);
  }

  @Test
  void anObjectNoLongerInGcs_isDone_andLoggedOnce_withTheFileId() throws Exception {
    // The user deleted the file, or it was rejected as too big, before this notification came.
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByObjectName(OBJECT_NAME)).thenReturn(Optional.of(pendingFile(fileId)));
    // storage.get answers null: no such object.

    mockMvc.perform(pushFor(OBJECT_NAME)).andExpect(status().isOk());

    List<ILoggingEvent> errors = linesAt(Level.ERROR);
    assertThat(errors).as("ERROR lines").hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", fileId.toString());
    verifyNoInteractions(kafkaProducerService);
  }

  @Test
  void aNotificationWithNoObjectName_isDone_andLoggedOnce() throws Exception {
    mockMvc.perform(post("/api/v1/webhook/notify-upload")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"message\":{\"attributes\":{}}}"))
        .andExpect(status().isOk());

    assertThat(linesAt(Level.ERROR)).as("ERROR lines").hasSize(1);
  }

  @Test
  void aBodyThatIsNotJson_isDone_andLoggedOnce() throws Exception {
    mockMvc.perform(post("/api/v1/webhook/notify-upload")
        .contentType(MediaType.APPLICATION_JSON)
        .content("not json"))
        .andExpect(status().isOk());

    assertThat(linesAt(Level.ERROR)).as("ERROR lines").hasSize(1);
  }

  @Test
  void gcsFailing_is5xx_soPubSubSendsItAgain_loggedOnceByTheBoundary_withTheFileId() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByObjectName(OBJECT_NAME)).thenReturn(Optional.of(pendingFile(fileId)));
    when(storage.get(any(BlobId.class), any(Storage.BlobGetOption.class)))
        .thenThrow(new StorageException(503, "backend unavailable"));

    mockMvc.perform(pushFor(OBJECT_NAME)).andExpect(status().is5xxServerError());

    List<ILoggingEvent> errors = linesAt(Level.ERROR);
    assertThat(errors).as("ERROR lines").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary").isEqualTo(ApiExceptionHandler.class.getName());
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("backend unavailable");
    assertThat(error.getMDCPropertyMap()).containsEntry("fileId", fileId.toString());
    assertThat(MDC.getCopyOfContextMap()).as("MDC after the request: nothing left for the next one on this thread")
        .isNullOrEmpty();
  }

  /** What a Pub/Sub push subscription on the bucket's notifications sends. */
  private static MockHttpServletRequestBuilder pushFor(String objectName) {
    return post("/api/v1/webhook/notify-upload")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"message": {"attributes": {"objectId": "%s", "eventType": "OBJECT_FINALIZE"},
                         "messageId": "1", "data": ""},
             "subscription": "projects/cortex/subscriptions/uploads"}
            """.formatted(objectName));
  }

  private List<ILoggingEvent> linesAt(Level level) {
    return captured.list.stream().filter(logged -> logged.getLevel() == level).toList();
  }

  private static FileMetadata pendingFile(UUID fileId) {
    return FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName(OBJECT_NAME)
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.PENDING)
        .contentType("video/mp4")
        .userId("user-1")
        .build();
  }
}
