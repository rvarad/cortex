package com.cortex.cortex_ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_ingestion.model.PipelineEvent;

/**
 * The sweep's rule 1 query against the real schema (Flyway): a file is silent when its newest event,
 * or its row's created_at when it has none, is older than the cut-off for its status (failed-states,
 * brick 11). The cut-offs here are the real ones: 2 hours for UPLOADED, 45 minutes for the rest.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PipelineEventRepositoryTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

  @Autowired
  PipelineEventRepository repo;

  @Autowired
  TestEntityManager entityManager;

  private final LocalDateTime now = LocalDateTime.now();

  @Test
  void findSilentFiles_findsEachStatusByItsOwnSilence() {
    UUID processingSilent = fileWithLastEvent(FileStatusEnum.PROCESSING, 50);
    UUID processingActive = fileWithLastEvent(FileStatusEnum.PROCESSING, 10);
    UUID uploadedOneHour = fileWithLastEvent(FileStatusEnum.UPLOADED, 60);
    UUID uploadedThreeHours = fileWithLastEvent(FileStatusEnum.UPLOADED, 180);
    UUID chunkedNoEvents = fileCreated(FileStatusEnum.CHUNKED, 60);

    List<UUID> silent = repo.findSilentFiles(now.minusHours(2), now.minusMinutes(45));

    assertThat(silent).as("silent files")
        .contains(processingSilent, uploadedThreeHours, chunkedNoEvents)
        .doesNotContain(processingActive, uploadedOneHour);
  }

  @Test
  void findSilentFiles_leavesFilesWhoseRunHasEnded() {
    UUID completed = fileWithLastEvent(FileStatusEnum.COMPLETED, 180);
    UUID forCleanup = fileWithLastEvent(FileStatusEnum.FOR_CLEANUP, 180);

    List<UUID> silent = repo.findSilentFiles(now.minusHours(2), now.minusMinutes(45));

    assertThat(silent).doesNotContain(completed, forCleanup);
  }

  @Test
  void findSilentFailedFiles_findsAForCleanupFileNothingWrites_only() {
    // Rule 2 (brick 12): 45 minutes, so a media job still running for a file rag failed is left alone.
    UUID silent = fileWithLastEvent(FileStatusEnum.FOR_CLEANUP, 50);
    UUID active = fileWithLastEvent(FileStatusEnum.FOR_CLEANUP, 10);
    UUID cleaned = fileWithLastEvent(FileStatusEnum.FAILED, 180); // so a second run picks nothing
    UUID processing = fileWithLastEvent(FileStatusEnum.PROCESSING, 180); // rule 1's, not rule 2's

    List<UUID> silentFailed = repo.findSilentFailedFiles(now.minusMinutes(45));

    assertThat(silentFailed).contains(silent).doesNotContain(active, cleaned, processing);
  }

  @Test
  void findSilentFilesForDeletion_findsADeletedFileNothingWrites_withItsObjectName() {
    // Rule 3 (brick 13): native SQL, so it sees the FOR_DELETION rows that JPA cannot.
    UUID silent = fileWithLastEvent(FileStatusEnum.FOR_DELETION, 50);
    UUID active = fileWithLastEvent(FileStatusEnum.FOR_DELETION, 10);
    UUID failed = fileWithLastEvent(FileStatusEnum.FOR_CLEANUP, 180); // rule 2's, not rule 3's

    List<PipelineEventRepository.SilentFile> deleted = repo.findSilentFilesForDeletion(now.minusMinutes(45));

    assertThat(deleted).extracting(PipelineEventRepository.SilentFile::getId)
        .contains(silent).doesNotContain(active, failed);
    assertThat(deleted).filteredOn(file -> file.getId().equals(silent))
        .singleElement().extracting(PipelineEventRepository.SilentFile::getObjectName).asString()
        .startsWith("uploads/media/");
  }

  /** A file created long ago, whose newest event is minutesAgo old, after an older one. */
  private UUID fileWithLastEvent(FileStatusEnum status, int minutesAgo) {
    UUID fileId = fileCreated(status, minutesAgo + 30);
    event(fileId, PipelineEventEnum.PIPELINE_STARTED, minutesAgo + 20);
    event(fileId, PipelineEventEnum.PROBE_STARTED, minutesAgo);
    return fileId;
  }

  private UUID fileCreated(FileStatusEnum status, int minutesAgo) {
    UUID fileId = entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + UUID.randomUUID() + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId("user-1")
        .build()).getId();
    // created_at is set on insert and not updatable through JPA: moved back in SQL.
    entityManager.getEntityManager()
        .createNativeQuery("UPDATE file_metadata SET created_at = :at WHERE id = :id")
        .setParameter("at", now.minusMinutes(minutesAgo))
        .setParameter("id", fileId)
        .executeUpdate();
    return fileId;
  }

  private void event(UUID fileId, PipelineEventEnum type, int minutesAgo) {
    UUID eventId = entityManager.persistAndFlush(PipelineEvent.builder()
        .fileId(fileId)
        .eventType(type)
        .message(type.name())
        .metadata("{}")
        .build()).getId();
    entityManager.getEntityManager()
        .createNativeQuery("UPDATE pipeline_events SET created_at = :at WHERE id = :id")
        .setParameter("at", now.minusMinutes(minutesAgo))
        .setParameter("id", eventId)
        .executeUpdate();
  }
}
