package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
 * Rule 3's last step against the real schema (failed-states, brick 13): every row of a deleted file
 * goes, in one transaction, and another file's rows stay. Counted in native SQL: JPA cannot see a
 * FOR_DELETION row.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeletedFileRemovalTest {

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
  void aDeletedFile_losesEveryRow_andAnotherFileKeepsItsOwn() {
    FilesService filesService = new FilesService(pipelineEventRepository, mediaChunkRepository,
        fileMetadataRepository, mock(GcsStorageService.class));
    UUID deleted = fileWithChunksAndEvents(FileStatusEnum.FOR_DELETION, 2);
    UUID other = fileWithChunksAndEvents(FileStatusEnum.COMPLETED, 1);
    entityManager.clear();

    assertThat(filesService.deleteRowsOfDeletedFile(deleted)).isTrue();
    assertThat(filesService.deleteRowsOfDeletedFile(deleted)).as("a second time: nothing left").isFalse();
    entityManager.flush();

    assertThat(count("file_metadata", "id", deleted)).as("its row").isZero();
    assertThat(count("media_chunk", "file_id", deleted)).as("its media_chunk rows").isZero();
    assertThat(count("pipeline_events", "file_id", deleted)).as("its events").isZero();
    assertThat(count("file_metadata", "id", other)).as("the other file's row").isEqualTo(1);
    assertThat(count("media_chunk", "file_id", other)).as("the other file's chunks").isEqualTo(1);
    assertThat(count("pipeline_events", "file_id", other)).as("the other file's events").isEqualTo(1);
  }

  private UUID fileWithChunksAndEvents(FileStatusEnum status, int count) {
    UUID fileId = entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + UUID.randomUUID() + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId("user-1")
        .totalChunks(count)
        .build()).getId();
    for (int index = 0; index < count; index++) {
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
      entityManager.persistAndFlush(PipelineEvent.builder()
          .fileId(fileId)
          .eventType(PipelineEventEnum.EMBEDDING_COMPLETE)
          .message("Embedding complete")
          .metadata("{}")
          .build());
    }
    return fileId;
  }

  private long count(String table, String column, UUID fileId) {
    return ((Number) entityManager.getEntityManager()
        .createNativeQuery("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
        .setParameter("id", fileId)
        .getSingleResult()).longValue();
  }
}
