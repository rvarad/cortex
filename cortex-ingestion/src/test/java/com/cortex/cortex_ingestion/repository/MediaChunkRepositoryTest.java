package com.cortex.cortex_ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

  @Autowired
  MediaChunkRepository repo;

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

    List<UUID> result = repo.lexicalSearch("test-user", "docker", "en", null, 10);

    assertThat(result).contains(saved);
  }
}