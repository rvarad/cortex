package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns the chunk ids the model cites into the numbers the frontend reads.
 *
 * <p>One rule, applied the same way by both agent paths: the first time an id appears in the
 * answer, it takes the next number. The blocking path numbers everything once the answer is
 * complete; the streaming path numbers each segment as it arrives. Because the rule is identical,
 * it lives here rather than being written twice.
 *
 * <p>An id the tools never returned is dropped. That check is identity — stricter than the original
 * chat path's "is this integer within range", where an invented number lands on a real but
 * unrelated source.
 *
 * <p>Not thread-safe, and does not need to be: one instance belongs to one question.
 */
class CitationNumberer {

  private final Map<UUID, SourceRefDTO> retrieved;

  /** Insertion-ordered so numbers, and the sources list, come out in the order a reader meets them. */
  private final Map<UUID, Integer> numbers = new LinkedHashMap<>();

  /** Sources numbered but not yet handed to the caller. */
  private final List<SourceRefDTO> pending = new ArrayList<>();

  private final List<String> dropped = new ArrayList<>();

  CitationNumberer(Map<UUID, SourceRefDTO> retrieved) {
    this.retrieved = retrieved;
  }

  /**
   * @return the number for this cite, or empty if it isn't a UUID or was never retrieved — in which
   *     case it is recorded in {@link #dropped()}.
   */
  Optional<Integer> numberFor(String cite) {
    UUID id = toUuid(cite);

    if (id == null || !retrieved.containsKey(id)) {
      dropped.add(cite);
      return Optional.empty();
    }

    Integer existing = numbers.get(id);

    if (existing != null) {
      return Optional.of(existing);
    }

    int sourceNo = numbers.size() + 1;
    numbers.put(id, sourceNo);

    SourceRefDTO found = retrieved.get(id);

    pending.add(SourceRefDTO.builder()
        .sourceNo(sourceNo)
        .fileId(found.getFileId())
        .fileDisplayName(found.getFileDisplayName())
        .startTime(found.getStartTime())
        .endTime(found.getEndTime())
        .chunkIndex(found.getChunkIndex())
        .build());

    return Optional.of(sourceNo);
  }

  /**
   * Sources first seen since the last call, then forgets them. The streaming path drains after each
   * segment so a source event goes out just before the segment citing it; the blocking path drains
   * once at the end.
   */
  List<SourceRefDTO> drainNewSources() {
    List<SourceRefDTO> drained = List.copyOf(pending);
    pending.clear();
    return drained;
  }

  /** Cites that matched nothing. Worth logging: this is how mangled ids become visible. */
  List<String> dropped() {
    return dropped;
  }

  private UUID toUuid(String cite) {
    try {
      return UUID.fromString(cite);
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }
}
