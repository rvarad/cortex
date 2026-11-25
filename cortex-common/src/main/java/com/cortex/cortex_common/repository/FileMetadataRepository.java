package com.cortex.cortex_common.repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
import com.cortex.cortex_common.model.UploadRejectReasonEnum;

import jakarta.persistence.LockModeType;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
  Optional<FileMetadata> findByObjectName(String objectName);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT fm FROM FileMetadata AS fm WHERE fm.id = :fileId")
  Optional<FileMetadata> findByIdForUpdate(@Param("fileId") UUID fileId);

  List<FileMetadata> findAllByUserId(String userId);

  /** The sweep: uploads whose signed URL was issued before the cut-off and that never landed. */
  List<FileMetadata> findByFileStatusAndCreatedAtBefore(FileStatusEnum status, LocalDateTime before);

  // Only while still PENDING: a webhook that marked it UPLOADED first wins, and the row stays.
  @Transactional
  @Modifying
  @Query("DELETE FROM FileMetadata f WHERE f.id = :fileId "
      + "AND f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.PENDING")
  int deleteIfPending(@Param("fileId") UUID fileId);

  Optional<FileMetadata> findByIdAndUserId(UUID fileId, String userId);

  // Rule 3's last step. Native: JPA cannot see a FOR_DELETION row (@SQLRestriction on FileMetadata).
  @Transactional
  @Modifying
  @Query(value = "DELETE FROM file_metadata WHERE id = :fileId AND file_status = 'FOR_DELETION'", nativeQuery = true)
  int deleteIfForDeletion(@Param("fileId") UUID fileId);

  // The status writes below change a row only when its status is not in `terminal`
  // (FileStatusEnum.TERMINAL_STATUSES): the pipeline never moves a terminal file. Each
  // returns the rows it changed; 0 means the file is terminal or gone, and the caller stops.

  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.UPLOADED, "
      + "f.fileSize = :fileSize WHERE f.id = :fileId AND f.fileStatus NOT IN :terminal")
  int markUploaded(@Param("fileId") UUID fileId, @Param("fileSize") Long fileSize,
      @Param("terminal") Collection<FileStatusEnum> terminal);

  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.REJECTED, "
      + "f.rejectionReason = :reason, f.fileSize = :fileSize "
      + "WHERE f.id = :fileId AND f.fileStatus NOT IN :terminal")
  int markRejected(@Param("fileId") UUID fileId, @Param("reason") UploadRejectReasonEnum reason,
      @Param("fileSize") Long fileSize, @Param("terminal") Collection<FileStatusEnum> terminal);

  // Failed, its data not yet removed: the sweep's to-do list.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.FOR_CLEANUP "
      + "WHERE f.id = :fileId AND f.fileStatus NOT IN :terminal")
  int markForCleanup(@Param("fileId") UUID fileId, @Param("terminal") Collection<FileStatusEnum> terminal);

  // Rule 2 is done: failed, and its data removed. Only from FOR_CLEANUP: a Delete that came first wins.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.FAILED "
      + "WHERE f.id = :fileId AND f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.FOR_CLEANUP")
  int markCleaned(@Param("fileId") UUID fileId);

  // Targeted update of the chunking result only — deliberately does NOT touch
  // playbackObjectName, which the normalization thread owns via its own update.
  // Disjoint columns => the two writers can't clobber each other, in any order.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = :status, f.totalChunks = :totalChunks, "
      + "f.durationSeconds = :durationSeconds, f.hasVideo = :hasVideo, f.hasAudio = :hasAudio, "
      + "f.videoCodec = :videoCodec WHERE f.id = :fileId AND f.fileStatus NOT IN :terminal")
  int updateChunkingResult(@Param("fileId") UUID fileId, @Param("status") FileStatusEnum status,
      @Param("totalChunks") Integer totalChunks, @Param("durationSeconds") Double durationSeconds,
      @Param("hasVideo") Boolean hasVideo, @Param("hasAudio") Boolean hasAudio,
      @Param("videoCodec") String videoCodec, @Param("terminal") Collection<FileStatusEnum> terminal);

  // Delete: from any status, so no status condition. A row already FOR_DELETION is hidden
  // (@SQLRestriction), so a second Delete changes nothing and answers 404. Rule 3 removes it.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileStatus = com.cortex.cortex_common.model.FileStatusEnum.FOR_DELETION "
      + "WHERE f.id = :fileId AND f.userId = :userId")
  int markForDeletion(@Param("fileId") UUID fileId, @Param("userId") String userId);

  // Only the name: a save of the whole row would write back the status it read.
  @Transactional
  @Modifying
  @Query("UPDATE FileMetadata f SET f.fileDisplayName = :name WHERE f.id = :fileId AND f.userId = :userId")
  int rename(@Param("fileId") UUID fileId, @Param("userId") String userId, @Param("name") String name);

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
