package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a retrieved chunk into the citation reference the frontend reads.
 *
 * <p>Two places do this — {@link LibraryTools} when the model searches, and {@link
 * AttachmentLoader} when a file was attached — and they differ in exactly one flag. Written twice,
 * that flag is the thing most likely to end up wrong, and it fails silently: the citation still
 * points at a real file at a real timestamp, just wearing the wrong badge. Since provenance exists
 * precisely to tell the truth about where an answer came from, a bug here inverts the feature
 * rather than breaking it.
 *
 * <p>Static because it has no state and no collaborators — it is a mapping, not a service.
 */
final class SourceRefs {

  private SourceRefs() {
  }

  /** {@code sourceNo} is left unset; numbers are assigned once the answer names its cites. */
  static SourceRefDTO from(SearchResultDTO result, boolean attached) {
    return SourceRefDTO.builder()
        .fileId(result.getFileId())
        .fileDisplayName(result.getFileDisplayName())
        .startTime(result.getStartTime())
        .endTime(result.getEndTime())
        .chunkIndex(result.getChunkIndex())
        .attached(attached)
        .build();
  }

  /**
   * Records what was retrieved so the loop can resolve the ids the model cites. A chunk found twice
   * keeps its first entry — in particular, a chunk that arrived as an attachment stays marked as
   * one even if a later search turns it up again.
   */
  static void remember(Map<UUID, SourceRefDTO> into, List<SearchResultDTO> results, boolean attached) {
    for (SearchResultDTO result : results) {
      into.putIfAbsent(result.getId(), from(result, attached));
    }
  }
}
