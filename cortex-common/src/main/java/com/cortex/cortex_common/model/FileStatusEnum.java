package com.cortex.cortex_common.model;

import java.util.Set;

public enum FileStatusEnum {
  PENDING,
  // SCANNING,
  // CLEANED,
  // INFECTED,
  UPLOADED,
  CHUNKED,
  PROCESSING,
  COMPLETED,
  REJECTED,
  FOR_CLEANUP, // failed, its data not yet removed: the sweep's to-do list (CONTEXT.md, failed)
  FAILED,
  FOR_DELETION; // deleted by the user: hidden from every JPA read; the sweep's rule 3 removes its data and rows

  /** The pipeline never changes these. Only the sweep and Delete move a file out of one. */
  public static final Set<FileStatusEnum> TERMINAL_STATUSES = Set.of(COMPLETED, REJECTED, FOR_CLEANUP, FAILED, FOR_DELETION);

  /** Failed, before and after cleanup (CONTEXT.md, failed). */
  public static final Set<FileStatusEnum> FAILED_STATUSES = Set.of(FOR_CLEANUP, FAILED);

  /** Search leaves these files' chunks out (native SQL, which @SQLRestriction does not reach). */
  public static final Set<FileStatusEnum> HIDDEN_FROM_SEARCH = Set.of(FOR_CLEANUP, FAILED, FOR_DELETION);

  public boolean isTerminal() {
    return TERMINAL_STATUSES.contains(this);
  }

  public boolean isFailed() {
    return FAILED_STATUSES.contains(this);
  }
}
