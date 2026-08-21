package com.cortex.cortex_common.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;

import jakarta.persistence.LockModeType;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
  Optional<FileMetadata> findByObjectName(String objectName);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT fm FROM FileMetadata AS fm WHERE fm.id = :fileId")
  Optional<FileMetadata> findByIdForUpdate(@Param("fileId") UUID fileId);

  List<FileMetadata> findAllByUserId(String userId);

  Optional<FileMetadata> findByIdAndUserId(UUID fileId, String userId);

  // Targeted update of the chunking result only — deliberately does NOT touch
  // playbackObjectName, which the normalization thread owns via its own update.
  // Disjoint columns => the two writers can't clobber each other, in any order.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = :status, f.totalChunks = :totalChunks, "
      + "f.durationSeconds = :durationSeconds, f.hasVideo = :hasVideo, f.hasAudio = :hasAudio, "
      + "f.videoCodec = :videoCodec WHERE f.id = :fileId")
  void updateChunkingResult(@Param("fileId") UUID fileId, @Param("status") FileStatusEnum status,
      @Param("totalChunks") Integer totalChunks, @Param("durationSeconds") Double durationSeconds,
      @Param("hasVideo") Boolean hasVideo, @Param("hasAudio") Boolean hasAudio,
      @Param("videoCodec") String videoCodec);

  // Playback succeeded: pin the object name and flip status to READY in one write,
  // so the two can never drift (READY <=> playbackObjectName != null).
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.playbackObjectName = :playbackObjectName, "
      + "f.playbackStatus = com.cortex.cortex_common.model.PlaybackStatusEnum.READY WHERE f.id = :fileId")
  void updatePlaybackReady(@Param("fileId") UUID fileId, @Param("playbackObjectName") String playbackObjectName);

  // Normalization failed: no playback version will ever exist. UNAVAILABLE is a
  // terminal playback state the UI reads to show "playback unavailable".
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.playbackStatus = com.cortex.cortex_common.model.PlaybackStatusEnum.UNAVAILABLE "
      + "WHERE f.id = :fileId")
  void updatePlaybackUnavailable(@Param("fileId") UUID fileId);
}
