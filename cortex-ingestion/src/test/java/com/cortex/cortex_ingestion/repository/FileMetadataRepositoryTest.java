package com.cortex.cortex_ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.cortex.cortex_common.model.UploadRejectReasonEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;

/**
 * The status writes against the real schema (Flyway): each changes a file that is not
 * terminal, and leaves a terminal one as it is (ADR-0015).
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileMetadataRepositoryTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

  @Autowired
  FileMetadataRepository repo;

  @Autowired
  TestEntityManager entityManager;

  @Test
  void markUploaded_changesAPendingFile_butLeavesAForCleanupOne() {
    UUID pending = saved(FileStatusEnum.PENDING);
    UUID forCleanup = saved(FileStatusEnum.FOR_CLEANUP);

    assertThat(repo.markUploaded(pending, 5_000L, FileStatusEnum.TERMINAL_STATUSES)).isEqualTo(1);
    assertThat(repo.markUploaded(forCleanup, 5_000L, FileStatusEnum.TERMINAL_STATUSES)).isEqualTo(0);

    entityManager.clear();
    assertThat(read(pending).getFileStatus()).isEqualTo(FileStatusEnum.UPLOADED);
    assertThat(read(pending).getFileSize()).isEqualTo(5_000L);
    assertThat(read(forCleanup).getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
    assertThat(read(forCleanup).getFileSize()).isEqualTo(1_000L);
  }

  @Test
  void markRejected_changesAnUploadedFile_butLeavesAForCleanupOne() {
    UUID uploaded = saved(FileStatusEnum.UPLOADED);
    UUID forCleanup = saved(FileStatusEnum.FOR_CLEANUP);

    assertThat(repo.markRejected(uploaded, UploadRejectReasonEnum.TOO_LONG, 1_000L, FileStatusEnum.TERMINAL_STATUSES))
        .isEqualTo(1);
    assertThat(repo.markRejected(forCleanup, UploadRejectReasonEnum.TOO_LONG, 1_000L, FileStatusEnum.TERMINAL_STATUSES))
        .isEqualTo(0);

    entityManager.clear();
    assertThat(read(uploaded).getFileStatus()).isEqualTo(FileStatusEnum.REJECTED);
    assertThat(read(uploaded).getRejectionReason()).isEqualTo(UploadRejectReasonEnum.TOO_LONG);
    assertThat(read(forCleanup).getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
    assertThat(read(forCleanup).getRejectionReason()).isNull();
  }

  @Test
  void updateChunkingResult_changesAnUploadedFile_butLeavesAForCleanupOne() {
    UUID uploaded = saved(FileStatusEnum.UPLOADED);
    UUID forCleanup = saved(FileStatusEnum.FOR_CLEANUP);

    assertThat(chunked(uploaded)).isEqualTo(1);
    assertThat(chunked(forCleanup)).isEqualTo(0);

    entityManager.clear();
    assertThat(read(uploaded).getFileStatus()).isEqualTo(FileStatusEnum.CHUNKED);
    assertThat(read(uploaded).getTotalChunks()).isEqualTo(3);
    assertThat(read(forCleanup).getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
    assertThat(read(forCleanup).getTotalChunks()).isNull();
  }

  @Test
  void markForCleanup_changesAProcessingFile_butLeavesACompletedOne() {
    UUID processing = saved(FileStatusEnum.PROCESSING);
    UUID completed = saved(FileStatusEnum.COMPLETED);

    assertThat(repo.markForCleanup(processing, FileStatusEnum.TERMINAL_STATUSES)).isEqualTo(1);
    assertThat(repo.markForCleanup(completed, FileStatusEnum.TERMINAL_STATUSES)).isEqualTo(0);

    entityManager.clear();
    assertThat(read(processing).getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
    assertThat(read(completed).getFileStatus()).isEqualTo(FileStatusEnum.COMPLETED);
  }

  @Test
  void rename_changesOnlyTheName() {
    UUID forCleanup = saved(FileStatusEnum.FOR_CLEANUP);

    assertThat(repo.rename(forCleanup, "user-1", "renamed.mp4")).isEqualTo(1);
    assertThat(repo.rename(forCleanup, "someone-else", "theirs.mp4")).as("not their file").isEqualTo(0);

    entityManager.clear();
    assertThat(read(forCleanup).getFileDisplayName()).isEqualTo("renamed.mp4");
    assertThat(read(forCleanup).getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
  }

  private int chunked(UUID fileId) {
    return repo.updateChunkingResult(fileId, FileStatusEnum.CHUNKED, 3, 90.0, true, true, "h264",
        FileStatusEnum.TERMINAL_STATUSES);
  }

  @Test
  void aForDeletionRow_isHiddenFromEveryRead() {
    // @SQLRestriction (ADR-0015): every JPA read treats a deleted file as gone; only native SQL sees it.
    String objectName = "uploads/media/" + UUID.randomUUID() + ".mp4";
    UUID deleted = entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName(objectName)
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.FOR_DELETION)
        .contentType("video/mp4")
        .userId("user-1")
        .build()).getId();
    UUID visible = saved(FileStatusEnum.COMPLETED);
    entityManager.clear();

    assertThat(repo.findById(deleted)).as("findById").isEmpty();
    assertThat(repo.findByIdAndUserId(deleted, "user-1")).as("findByIdAndUserId").isEmpty();
    assertThat(repo.findByIdForUpdate(deleted)).as("findByIdForUpdate").isEmpty();
    assertThat(repo.findByObjectName(objectName)).as("findByObjectName").isEmpty();
    assertThat(repo.findAllByUserId("user-1")).extracting(FileMetadata::getId).as("findAllByUserId")
        .contains(visible).doesNotContain(deleted);
    assertThat(repo.rename(deleted, "user-1", "renamed.mp4")).as("rename").isZero();
  }

  @Test
  void markCleaned_changesAForCleanupFile_butLeavesAProcessingOne() {
    UUID forCleanup = saved(FileStatusEnum.FOR_CLEANUP);
    UUID processing = saved(FileStatusEnum.PROCESSING);

    assertThat(repo.markCleaned(forCleanup)).isEqualTo(1);
    assertThat(repo.markCleaned(processing)).isEqualTo(0);

    entityManager.clear();
    assertThat(read(forCleanup).getFileStatus()).isEqualTo(FileStatusEnum.FAILED);
    assertThat(read(processing).getFileStatus()).isEqualTo(FileStatusEnum.PROCESSING);
  }

  @Test
  void deleteIfPending_deletesAPendingRow_butLeavesAnUploadedOne() {
    // The sweep's abandoned uploads (ADR-0016): a webhook that marked the row UPLOADED first wins.
    UUID pending = saved(FileStatusEnum.PENDING);
    UUID uploaded = saved(FileStatusEnum.UPLOADED);

    assertThat(repo.deleteIfPending(pending)).isEqualTo(1);
    assertThat(repo.deleteIfPending(uploaded)).isEqualTo(0);

    entityManager.clear();
    assertThat(read(pending)).isNull();
    assertThat(read(uploaded)).isNotNull();
  }

  @ParameterizedTest
  @EnumSource(value = FileStatusEnum.class, names = "FOR_DELETION", mode = EnumSource.Mode.EXCLUDE)
  void markForDeletion_marksAFileOfAnyStatus_onlyForItsOwner(FileStatusEnum status) {
    UUID fileId = saved(status);

    assertThat(repo.markForDeletion(fileId, "someone-else")).as("not their file").isZero();
    assertThat(nativeStatus(fileId)).isEqualTo(status.name());

    assertThat(repo.markForDeletion(fileId, "user-1")).isEqualTo(1);
    assertThat(nativeStatus(fileId)).isEqualTo("FOR_DELETION");

    assertThat(repo.markForDeletion(fileId, "user-1")).as("deleted twice: already hidden").isZero();
  }

  @Test
  void aForDeletionRow_ignoresThePipelinesWrites() {
    // A job still running for a deleted file cannot bring it back (ADR-0015).
    UUID fileId = saved(FileStatusEnum.PROCESSING);
    repo.markForDeletion(fileId, "user-1");
    Object[] playbackBefore = nativePlayback(fileId);

    assertThat(repo.markUploaded(fileId, 5_000L, FileStatusEnum.TERMINAL_STATUSES)).as("markUploaded").isZero();
    assertThat(repo.markRejected(fileId, UploadRejectReasonEnum.TOO_LONG, 1_000L, FileStatusEnum.TERMINAL_STATUSES))
        .as("markRejected").isZero();
    assertThat(chunked(fileId)).as("updateChunkingResult").isZero();
    assertThat(repo.markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES)).as("markForCleanup").isZero();
    assertThat(repo.markCleaned(fileId)).as("markCleaned").isZero();
    repo.updatePlaybackReady(fileId, "playback/" + fileId + "/index.mp4");
    repo.updatePlaybackUnavailable(fileId);

    assertThat(nativeStatus(fileId)).isEqualTo("FOR_DELETION");
    assertThat(nativePlayback(fileId)).as("playback columns").containsExactly(playbackBefore);
  }

  /** Native: JPA cannot see a FOR_DELETION row. */
  private Object nativeStatus(UUID fileId) {
    return entityManager.getEntityManager()
        .createNativeQuery("SELECT file_status FROM file_metadata WHERE id = :id")
        .setParameter("id", fileId).getSingleResult();
  }

  private Object[] nativePlayback(UUID fileId) {
    return (Object[]) entityManager.getEntityManager()
        .createNativeQuery("SELECT playback_status, playback_object_name FROM file_metadata WHERE id = :id")
        .setParameter("id", fileId).getSingleResult();
  }

  private UUID saved(FileStatusEnum status) {
    return entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + UUID.randomUUID() + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId("user-1")
        .build()).getId();
  }

  private FileMetadata read(UUID fileId) {
    return entityManager.find(FileMetadata.class, fileId);
  }
}
