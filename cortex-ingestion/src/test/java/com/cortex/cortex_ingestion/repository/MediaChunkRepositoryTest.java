package com.cortex.cortex_ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.repository.MediaChunkRepository;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MediaChunkRepositoryTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

  /** The failed list, as the search queries take it: names (native SQL). */
  private static final List<String> FAILED = FileStatusEnum.FAILED_STATUSES.stream().map(Enum::name).toList();

  @Autowired
  MediaChunkRepository repo;

  @Autowired
  TestEntityManager entityManager;

  @Test
  void lexicalSearch_findChunksByKeywords() {

    MediaChunk chunk = MediaChunk.builder()
        .userId("test-user")
        .status(MediaChunk.Status.COMPLETED)
        .transcript("docker setup guide")
        .languageCode("en")
        .fileId(UUID.randomUUID())
        .chunkIndex(0)
        .startTime(0)
        .endTime(0)
        .embedding(new float[768])
        .build();

    UUID saved = repo.saveAndFlush(chunk).getId();

    List<UUID> result = repo.lexicalSearch("test-user", "docker", "en", null, 10, FAILED);

    assertThat(result).contains(saved);
  }

  @ParameterizedTest
  @EnumSource(value = FileStatusEnum.class, names = { "FOR_CLEANUP", "FAILED" })
  void aChunkOfAFailedFile_isFoundByNeitherSearch(FileStatusEnum failed) {
    // ---- Arrange: a finished file, and one chunk of it that both searches find ----
    FileMetadata file = entityManager.persistAndFlush(FileMetadata.builder()
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + UUID.randomUUID() + ".mp4")
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.COMPLETED)
        .contentType("video/mp4")
        .userId("user-1")
        .build());
    float[] vector = new float[768];
    vector[0] = 1f; // a zero vector has no cosine distance
    UUID chunk = repo.saveAndFlush(MediaChunk.builder()
        .userId("user-1")
        .status(MediaChunk.Status.COMPLETED)
        .transcript("docker setup guide")
        .languageCode("en")
        .fileId(file.getId())
        .chunkIndex(0)
        .startTime(0)
        .endTime(0)
        .embedding(vector)
        .build()).getId();
    assertThat(semantic(vector)).as("semantic, file COMPLETED").contains(chunk);
    assertThat(lexical()).as("lexical, file COMPLETED").contains(chunk);

    // ---- Act: the file fails ----
    file.setFileStatus(failed);
    entityManager.flush();
    entityManager.clear();

    // ---- Assert ----
    assertThat(semantic(vector)).as("semantic, file " + failed).doesNotContain(chunk);
    assertThat(lexical()).as("lexical, file " + failed).doesNotContain(chunk);
  }

  private List<UUID> semantic(float[] vector) {
    return repo.semanticSearch("user-1", vector, null, 10, FAILED);
  }

  private List<UUID> lexical() {
    return repo.lexicalSearch("user-1", "docker", "en", null, 10, FAILED);
  }
}
