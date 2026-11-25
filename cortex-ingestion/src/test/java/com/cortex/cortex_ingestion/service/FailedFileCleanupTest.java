package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.model.PipelineEvent;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

/**
 * Rule 2's last step against the real schema (ADR-0016): a cleaned file is FAILED and
 * has no media_chunk rows, but keeps its row and its timeline, so its card and page still show it.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FailedFileCleanupTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

  @Autowired
  FileMetadataRepository fileMetadataRepository;

  @Autowired
  MediaChunkRepository mediaChunkRepository;

  @Autowired
  PipelineEventRepository pipelineEventRepository;

  @Autowired
  TestEntityManager entityManager;

  @Test
  void aCleanedFile_isFailed_withoutChunks_butKeepsItsRowAndEvents() {
    FilesService filesService = new FilesService(pipelineEventRepository, mediaChunkRepository,
        fileMetadataRepository);
    UUID fileId = forCleanupFile();
    chunk(fileId, 0);
    chunk(fileId, 1);
    event(fileId, PipelineEventEnum.PIPELINE_STARTED);
    event(fileId, PipelineEventEnum.PIPELINE_FAILED);

    assertThat(filesService.markCleaned(fileId)).isTrue();

    entityManager.flush();
    entityManager.clear();
    assertThat(fileMetadataRepository.findById(fileId)).get()
        .extracting(FileMetadata::getFileStatus).isEqualTo(FileStatusEnum.FAILED);
    assertThat(mediaChunkRepository.countByFileId(fileId)).as("media_chunk rows").isZero();
    assertThat(pipelineEventRepository.findByFileIdOrderByCreatedAtAsc(fileId)).as("its timeline").hasSize(2);
  }

  @Test
  void aFileDeletedWhileRule2CleansIt_endsDeleted_notFailed() {
    // Rule 2 deleted the objects, then the user's Delete came before its last step.
    FilesService filesService = new FilesService(pipelineEventRepository, mediaChunkRepository,
        fileMetadataRepository);
    UUID fileId = forCleanupFile();
    chunk(fileId, 0);

    filesService.deleteFile(fileId, "user-1");
    assertThat(filesService.markCleaned(fileId)).as("rule 2's last step").isFalse();

    entityManager.flush();
    entityManager.clear();
    assertThat(entityManager.getEntityManager()
        .createNativeQuery("SELECT file_status FROM file_metadata WHERE id = :id")
        .setParameter("id", fileId).getSingleResult()).isEqualTo("FOR_DELETION");
    assertThat(mediaChunkRepository.countByFileId(fileId)).as("its chunks, left for rule 3").isEqualTo(1);
  }

  private UUID forCleanupFile() {
    return entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + UUID.randomUUID() + ".mp4")
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.FOR_CLEANUP)
        .contentType("video/mp4")
        .userId("user-1")
        .totalChunks(2)
        .build()).getId();
  }

  private void chunk(UUID fileId, int index) {
    float[] vector = new float[768];
    vector[0] = 1f;
    mediaChunkRepository.saveAndFlush(MediaChunk.builder()
        .userId("user-1")
        .status(MediaChunk.Status.COMPLETED)
        .transcript("chunk " + index)
        .languageCode("en")
        .fileId(fileId)
        .chunkIndex(index)
        .startTime(index * 60)
        .endTime((index + 1) * 60)
        .embedding(vector)
        .build());
  }

  private void event(UUID fileId, PipelineEventEnum type) {
    entityManager.persistAndFlush(PipelineEvent.builder()
        .fileId(fileId)
        .eventType(type)
        .message(type.name())
        .metadata("{}")
        .build());
  }
}
